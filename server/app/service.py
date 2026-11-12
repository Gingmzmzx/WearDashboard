"""Domain logic for requests, agents, tasks, devices and event fan-out."""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from dataclasses import dataclass
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from .auth import generate_token, hash_token, token_hint
from .config import Settings
from .db import Database
from .hub import ConnectionHub
from .models import Agent, AgentRequest, Decision, Device, Task
from .protocol import envelope, now_ms, resolved_payload, snapshot_envelope
from .schemas import (
    AgentOptionOut,
    AgentOut,
    AgentStatusIn,
    CreateRequestIn,
    DeviceIssuedOut,
    DeviceOut,
    RequestDetailOut,
    RequestOut,
    TaskIn,
    TaskOut,
)

logger = logging.getLogger(__name__)


@dataclass
class RespondResult:
    status: str
    detail: RequestDetailOut | None = None
    resolved: dict[str, Any] | None = None


class GatewayService:
    def __init__(self, db: Database, hub: ConnectionHub, settings: Settings) -> None:
        self.db = db
        self.hub = hub
        self.settings = settings

    # ---- devices ---------------------------------------------------------

    async def seed_env_device(self) -> None:
        token_hash = hash_token(self.settings.watch_token)
        async with self.db.session_factory() as session:
            existing = await session.scalar(
                select(Device).where(Device.token_hash == token_hash)
            )
            if existing is not None:
                return
            now = now_ms()
            session.add(
                Device(
                    id=f"dev_{uuid.uuid4().hex[:12]}",
                    name="seed-watch",
                    token_hash=token_hash,
                    token_hint=token_hint(self.settings.watch_token),
                    created_at=now,
                )
            )
            await session.commit()
        logger.info("seeded watch device from WATCH_TOKEN")

    async def authenticate_device(self, raw_token: str | None) -> Device | None:
        if not raw_token:
            return None
        token_hash = hash_token(raw_token)
        async with self.db.session_factory() as session:
            device = await session.scalar(
                select(Device).where(
                    Device.token_hash == token_hash,
                    Device.revoked_at.is_(None),
                )
            )
            return device

    async def touch_device(self, device_id: str) -> None:
        async with self.db.session_factory() as session:
            await session.execute(
                update(Device)
                .where(Device.id == device_id)
                .values(last_seen_at=now_ms())
            )
            await session.commit()

    async def issue_device(self, name: str) -> DeviceIssuedOut:
        token = generate_token()
        now = now_ms()
        device = Device(
            id=f"dev_{uuid.uuid4().hex[:12]}",
            name=name.strip(),
            token_hash=hash_token(token),
            token_hint=token_hint(token),
            created_at=now,
        )
        async with self.db.session_factory() as session:
            session.add(device)
            await session.commit()
        return DeviceIssuedOut(
            id=device.id,
            name=device.name,
            token_hint=device.token_hint,
            created_at=device.created_at,
            last_seen_at=None,
            revoked_at=None,
            token=token,
        )

    async def list_devices(self) -> list[DeviceOut]:
        async with self.db.session_factory() as session:
            devices = (
                await session.scalars(select(Device).order_by(Device.created_at.desc()))
            ).all()
            return [self._device_out(device) for device in devices]

    async def revoke_device(self, device_id: str) -> bool:
        async with self.db.session_factory() as session:
            device = await session.get(Device, device_id)
            if device is None:
                return False
            if device.revoked_at is None:
                device.revoked_at = now_ms()
                await session.commit()
        await self.hub.close_device(device_id)
        return True

    # ---- requests --------------------------------------------------------

    async def create_request(self, data: CreateRequestIn) -> RequestDetailOut:
        now = now_ms()
        ttl = data.ttl_sec or (
            self.settings.question_ttl_seconds
            if data.kind == "question"
            else self.settings.request_ttl_seconds
        )
        expires_at = now + ttl * 1000
        agent_name = data.agent_name.strip() or data.agent_id
        options_json = json.dumps(
            [option.model_dump(mode="json") for option in data.options],
            ensure_ascii=False,
        )
        row = AgentRequest(
            request_id=f"req_{uuid.uuid4().hex[:16]}",
            kind=data.kind,
            agent_id=data.agent_id,
            agent_name=agent_name,
            title=data.title.strip(),
            detail=data.detail,
            risk=data.risk,
            question=data.question,
            context=data.context,
            options_json=options_json,
            allow_free_text=data.allow_free_text,
            created_at=now,
            expires_at=expires_at,
            status="pending",
        )
        async with self.db.session_factory() as session:
            async with session.begin():
                await self._ensure_agent(session, data.agent_id, agent_name, now)
                session.add(row)
                await session.flush()
                seq = await self.db.next_seq(session)
                detail = self._request_detail(row)
            event_type = (
                "question.request" if data.kind == "question" else "permission.request"
            )
            await self.hub.broadcast(
                envelope(event_type, self._request_payload(row), seq)
            )
        return detail

    async def get_request(self, request_id: str) -> RequestDetailOut | None:
        async with self.db.session_factory() as session:
            row = await session.get(AgentRequest, request_id)
            return None if row is None else self._request_detail(row)

    async def list_requests(
        self, status: str | None = None, limit: int = 50
    ) -> list[RequestDetailOut]:
        async with self.db.session_factory() as session:
            statement = select(AgentRequest).order_by(AgentRequest.created_at.desc())
            if status:
                statement = statement.where(AgentRequest.status == status)
            statement = statement.limit(limit)
            rows = (await session.scalars(statement)).all()
            return [self._request_detail(row) for row in rows]

    async def wait_for_resolution(
        self, request_id: str, timeout: float
    ) -> RequestDetailOut | None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + max(0.0, timeout)
        while True:
            detail = await self.get_request(request_id)
            if detail is None or detail.status != "pending":
                return detail
            remaining = deadline - loop.time()
            if remaining <= 0:
                return detail
            await asyncio.sleep(min(0.5, remaining))

    async def respond(
        self, device: Device, request_id: str, option_id: str
    ) -> RespondResult:
        now = now_ms()
        broadcast: dict[str, Any] | None = None
        result: RespondResult

        async with self.db.session_factory() as session:
            async with session.begin():
                row = await session.get(AgentRequest, request_id)
                if row is None:
                    return RespondResult("not_found")
                if row.status != "pending":
                    if row.status == "answered" and row.option_id == option_id:
                        return RespondResult(
                            "already_answered_same",
                            self._request_detail(row),
                            resolved_payload(
                                row.request_id,
                                option_id=row.option_id,
                                resolution="answered",
                                by="server",
                                resolved_at=row.resolved_at or now,
                            ),
                        )
                    return RespondResult("already_resolved", self._request_detail(row))

                if row.expires_at <= now:
                    statement = (
                        update(AgentRequest)
                        .where(
                            AgentRequest.request_id == request_id,
                            AgentRequest.status == "pending",
                        )
                        .values(
                            status="expired",
                            resolution="expired",
                            resolved_at=now,
                        )
                    )
                    updated = await session.execute(statement)
                    if updated.rowcount == 1:
                        row.status = "expired"
                        row.resolution = "expired"
                        row.resolved_at = now
                        seq = await self.db.next_seq(session)
                        broadcast = envelope(
                            "request.resolved",
                            resolved_payload(
                                row.request_id,
                                option_id=None,
                                resolution="expired",
                                by="server",
                                resolved_at=now,
                            ),
                            seq,
                        )
                    result = RespondResult("expired", self._request_detail(row))
                else:
                    options = json.loads(row.options_json)
                    if not any(option.get("id") == option_id for option in options):
                        return RespondResult(
                            "invalid_option", self._request_detail(row)
                        )
                    statement = (
                        update(AgentRequest)
                        .where(
                            AgentRequest.request_id == request_id,
                            AgentRequest.status == "pending",
                        )
                        .values(
                            status="answered",
                            option_id=option_id,
                            resolution="answered",
                            resolved_by_device_id=device.id,
                            resolved_at=now,
                        )
                    )
                    updated = await session.execute(statement)
                    if updated.rowcount != 1:
                        fresh = await session.get(AgentRequest, request_id)
                        if fresh is None:
                            return RespondResult("not_found")
                        return RespondResult(
                            "already_resolved", self._request_detail(fresh)
                        )
                    row.status = "answered"
                    row.option_id = option_id
                    row.resolution = "answered"
                    row.resolved_by_device_id = device.id
                    row.resolved_at = now
                    session.add(
                        Decision(
                            request_id=row.request_id,
                            option_id=option_id,
                            device_id=device.id,
                            device_name=device.name,
                            created_at=now,
                        )
                    )
                    seq = await self.db.next_seq(session)
                    broadcast = envelope(
                        "request.resolved",
                        resolved_payload(
                            row.request_id,
                            option_id=option_id,
                            resolution="answered",
                            by="watch",
                            resolved_at=now,
                            device_name=device.name,
                        ),
                        seq,
                    )
                    result = RespondResult("answered", self._request_detail(row))

        if broadcast is not None:
            await self.hub.broadcast(broadcast)
        return result

    async def cancel_request(self, request_id: str) -> tuple[str, RequestDetailOut | None]:
        now = now_ms()
        broadcast: dict[str, Any] | None = None
        detail: RequestDetailOut | None = None
        status: str
        async with self.db.session_factory() as session:
            async with session.begin():
                row = await session.get(AgentRequest, request_id)
                if row is None:
                    return "not_found", None
                if row.status != "pending":
                    return "already_resolved", self._request_detail(row)
                statement = (
                    update(AgentRequest)
                    .where(
                        AgentRequest.request_id == request_id,
                        AgentRequest.status == "pending",
                    )
                    .values(
                        status="cancelled",
                        resolution="cancelled",
                        resolved_at=now,
                    )
                )
                updated = await session.execute(statement)
                if updated.rowcount != 1:
                    return "already_resolved", self._request_detail(row)
                row.status = "cancelled"
                row.resolution = "cancelled"
                row.resolved_at = now
                seq = await self.db.next_seq(session)
                detail = self._request_detail(row)
                broadcast = envelope(
                    "request.resolved",
                    resolved_payload(
                        row.request_id,
                        option_id=None,
                        resolution="cancelled",
                        by="server",
                        resolved_at=now,
                    ),
                    seq,
                )
                status = "cancelled"
        if broadcast is not None:
            await self.hub.broadcast(broadcast)
        return status, detail

    async def expire_due_requests(self) -> int:
        now = now_ms()
        async with self.db.session_factory() as session:
            ids = (
                await session.scalars(
                    select(AgentRequest.request_id).where(
                        AgentRequest.status == "pending",
                        AgentRequest.expires_at <= now,
                    )
                )
            ).all()
        expired = 0
        for request_id in ids:
            broadcast: dict[str, Any] | None = None
            async with self.db.session_factory() as session:
                async with session.begin():
                    row = await session.get(AgentRequest, request_id)
                    if row is None or row.status != "pending" or row.expires_at > now:
                        continue
                    statement = (
                        update(AgentRequest)
                        .where(
                            AgentRequest.request_id == request_id,
                            AgentRequest.status == "pending",
                        )
                        .values(
                            status="expired",
                            resolution="expired",
                            resolved_at=now,
                        )
                    )
                    updated = await session.execute(statement)
                    if updated.rowcount != 1:
                        continue
                    seq = await self.db.next_seq(session)
                    broadcast = envelope(
                        "request.resolved",
                        resolved_payload(
                            request_id,
                            option_id=None,
                            resolution="expired",
                            by="server",
                            resolved_at=now,
                        ),
                        seq,
                    )
                    expired += 1
            if broadcast is not None:
                await self.hub.broadcast(broadcast)
        return expired

    # ---- agents and tasks ------------------------------------------------

    async def update_agent_status(
        self, agent_id: str, data: AgentStatusIn
    ) -> AgentOut:
        now = now_ms()
        async with self.db.session_factory() as session:
            async with session.begin():
                row = await session.get(Agent, agent_id)
                if row is None:
                    row = Agent(
                        id=agent_id,
                        name=data.name.strip() or agent_id,
                        status=data.status,
                        detail=data.detail,
                        task=data.task,
                        updated_at=now,
                    )
                    session.add(row)
                else:
                    row.status = data.status
                    row.detail = data.detail
                    row.task = data.task
                    row.updated_at = now
                    if data.name.strip():
                        row.name = data.name.strip()
                await session.flush()
                seq = await self.db.next_seq(session)
                payload = self._agent_payload(row)
            await self.hub.broadcast(envelope("agent.status", payload, seq))
        return self._agent_out(row)

    async def complete_task(self, data: TaskIn) -> TaskOut:
        now = now_ms()
        task_id = (data.task_id or "").strip() or f"task_{uuid.uuid4().hex[:16]}"
        agent_name = data.agent_name.strip() or data.agent_id
        async with self.db.session_factory() as session:
            async with session.begin():
                existing = await session.get(Task, task_id)
                if existing is not None:
                    return self._task_out(existing)
                await self._ensure_agent(session, data.agent_id, agent_name, now)
                row = Task(
                    task_id=task_id,
                    agent_id=data.agent_id,
                    agent_name=agent_name,
                    title=data.title,
                    summary=data.summary,
                    outcome=data.outcome,
                    duration_ms=data.duration_ms,
                    completed_at=now,
                    created_at=now,
                )
                session.add(row)
                await session.flush()
                seq = await self.db.next_seq(session)
                payload = self._task_payload(row)
            await self.hub.broadcast(envelope("task.completed", payload, seq))
        return self._task_out(row)

    # ---- snapshots -------------------------------------------------------

    async def snapshot_message(self) -> dict[str, Any]:
        now = now_ms()
        async with self.db.session_factory() as session:
            seq = await self.db.seq(session)
            agents = (
                await session.scalars(
                    select(Agent).order_by(Agent.updated_at.desc()).limit(50)
                )
            ).all()
            pending = (
                await session.scalars(
                    select(AgentRequest)
                    .where(
                        AgentRequest.status == "pending",
                        AgentRequest.expires_at > now,
                    )
                    .order_by(AgentRequest.expires_at.asc())
                )
            ).all()
            tasks = (
                await session.scalars(
                    select(Task).order_by(Task.completed_at.desc()).limit(10)
                )
            ).all()
            payload = {
                "agents": [self._agent_payload(agent) for agent in agents],
                "pending": [self._request_payload(row) for row in pending],
                "recent": [self._task_payload(task) for task in tasks],
                "serverTime": now,
            }
        return snapshot_envelope(payload, seq)

    async def state(self, connections: int) -> dict[str, Any]:
        now = now_ms()
        async with self.db.session_factory() as session:
            seq = await self.db.seq(session)
            agents = (
                await session.scalars(select(Agent).order_by(Agent.updated_at.desc()))
            ).all()
            pending = (
                await session.scalars(
                    select(AgentRequest)
                    .where(
                        AgentRequest.status == "pending",
                        AgentRequest.expires_at > now,
                    )
                    .order_by(AgentRequest.expires_at.asc())
                )
            ).all()
            tasks = (
                await session.scalars(
                    select(Task).order_by(Task.completed_at.desc()).limit(10)
                )
            ).all()
            return {
                "seq": seq,
                "connections": connections,
                "agent_count": len(agents),
                "pending_count": len(pending),
                "agents": [self._agent_payload(agent) for agent in agents],
                "pending": [self._request_detail(row).model_dump(by_alias=True)
                            for row in pending],
                "recent": [self._task_payload(task) for task in tasks],
            }

    # ---- helpers ---------------------------------------------------------

    async def _ensure_agent(
        self,
        session: AsyncSession,
        agent_id: str,
        agent_name: str,
        now: int,
    ) -> None:
        row = await session.get(Agent, agent_id)
        if row is None:
            session.add(
                Agent(
                    id=agent_id,
                    name=agent_name,
                    status="waiting",
                    detail="",
                    task="",
                    updated_at=now,
                )
            )
        elif agent_name and row.name != agent_name:
            row.name = agent_name

    @staticmethod
    def _options(row: AgentRequest) -> list[AgentOptionOut]:
        try:
            raw = json.loads(row.options_json)
        except (TypeError, ValueError):
            raw = []
        return [AgentOptionOut(**option) for option in raw if isinstance(option, dict)]

    def _request_out(self, row: AgentRequest) -> RequestOut:
        return RequestOut(
            request_id=row.request_id,
            kind=row.kind,
            agent_id=row.agent_id,
            agent_name=row.agent_name,
            title=row.title,
            detail=row.detail,
            risk=row.risk,
            question=row.question,
            context=row.context,
            created_at=row.created_at,
            expires_at=row.expires_at,
            allow_free_text=row.allow_free_text,
            options=self._options(row),
        )

    def _request_detail(self, row: AgentRequest) -> RequestDetailOut:
        base = self._request_out(row).model_dump()
        base.update(
            status=row.status,
            option_id=row.option_id,
            resolution=row.resolution,
            resolved_at=row.resolved_at,
            resolved_by_device_id=row.resolved_by_device_id,
        )
        return RequestDetailOut(**base)

    def _request_payload(self, row: AgentRequest) -> dict[str, Any]:
        return self._request_out(row).model_dump(by_alias=True, exclude_none=True)

    @staticmethod
    def _agent_out(row: Agent) -> AgentOut:
        return AgentOut(
            id=row.id,
            name=row.name,
            status=row.status,
            detail=row.detail,
            task=row.task,
            updated_at=row.updated_at,
        )

    def _agent_payload(self, row: Agent) -> dict[str, Any]:
        return self._agent_out(row).model_dump(by_alias=True, exclude_none=True)

    @staticmethod
    def _task_out(row: Task) -> TaskOut:
        return TaskOut(
            task_id=row.task_id,
            agent_id=row.agent_id,
            agent_name=row.agent_name,
            title=row.title,
            summary=row.summary,
            outcome=row.outcome,
            duration_ms=row.duration_ms,
            completed_at=row.completed_at,
        )

    def _task_payload(self, row: Task) -> dict[str, Any]:
        return self._task_out(row).model_dump(by_alias=True, exclude_none=True)

    @staticmethod
    def _device_out(row: Device) -> DeviceOut:
        return DeviceOut(
            id=row.id,
            name=row.name,
            token_hint=row.token_hint,
            created_at=row.created_at,
            last_seen_at=row.last_seen_at,
            revoked_at=row.revoked_at,
        )
