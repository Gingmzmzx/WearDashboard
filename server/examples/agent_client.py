"""Small async client for an agent plugin / DeepSeek harness.

The plugin owns the agent loop; this client only asks the gateway for a human
decision and reports agent/task state. A timeout or transport failure resolves
to ``deny`` on the caller side.
"""

from __future__ import annotations

import asyncio
from typing import Any, Sequence

import httpx


class GatewayError(RuntimeError):
    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class GatewayClient:
    def __init__(
        self,
        base_url: str,
        api_token: str,
        agent_id: str,
        agent_name: str = "",
        *,
        timeout: float = 30.0,
        verify: bool = True,
    ) -> None:
        self.agent_id = agent_id
        self.agent_name = agent_name or agent_id
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_token}"},
            timeout=timeout,
            verify=verify,
        )

    async def __aenter__(self) -> "GatewayClient":
        return self

    async def __aexit__(self, *args: object) -> None:
        await self.close()

    async def close(self) -> None:
        await self._client.aclose()

    # ---- high level -----------------------------------------------------

    async def ask_permission(
        self,
        title: str,
        *,
        detail: str = "",
        risk: str = "low",
        options: Sequence[dict[str, str]] | None = None,
        ttl_sec: int | None = 120,
        wait_timeout: float | None = None,
    ) -> str:
        """Create a permission request and wait for the watch decision.

        Returns the selected option id, or ``"deny"`` on timeout/failure.
        """
        request = await self.create_permission(
            title,
            detail=detail,
            risk=risk,
            options=options,
            ttl_sec=ttl_sec,
        )
        timeout = wait_timeout if wait_timeout is not None else float(ttl_sec or 120)
        result = await self.wait_for(request["requestId"], timeout=timeout)
        if result["status"] != "answered":
            return "deny"
        return result.get("optionId") or "deny"

    async def ask_question(
        self,
        question: str,
        options: Sequence[dict[str, str]],
        *,
        context: str = "",
        title: str = "",
        ttl_sec: int | None = 300,
        wait_timeout: float | None = None,
    ) -> str | None:
        request = await self.create_question(
            question,
            options,
            context=context,
            title=title,
            ttl_sec=ttl_sec,
        )
        timeout = wait_timeout if wait_timeout is not None else float(ttl_sec or 300)
        result = await self.wait_for(request["requestId"], timeout=timeout)
        if result["status"] != "answered":
            return None
        return result.get("optionId")

    # ---- primitive calls ------------------------------------------------

    async def create_permission(
        self,
        title: str,
        *,
        detail: str = "",
        risk: str = "low",
        options: Sequence[dict[str, str]] | None = None,
        ttl_sec: int | None = 120,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "kind": "permission",
            "agentId": self.agent_id,
            "agentName": self.agent_name,
            "title": title,
            "detail": detail,
            "risk": risk,
        }
        if options is not None:
            payload["options"] = list(options)
        if ttl_sec is not None:
            payload["ttlSec"] = ttl_sec
        return await self._request("POST", "/api/v1/requests", json=payload)

    async def create_question(
        self,
        question: str,
        options: Sequence[dict[str, str]],
        *,
        context: str = "",
        title: str = "",
        ttl_sec: int | None = 300,
    ) -> dict[str, Any]:
        if not options:
            raise ValueError("question requires at least one option")
        payload: dict[str, Any] = {
            "kind": "question",
            "agentId": self.agent_id,
            "agentName": self.agent_name,
            "title": title or question,
            "question": question,
            "context": context,
            "risk": "low",
            "options": list(options),
        }
        if ttl_sec is not None:
            payload["ttlSec"] = ttl_sec
        return await self._request("POST", "/api/v1/requests", json=payload)

    async def wait_for(self, request_id: str, *, timeout: float = 30.0) -> dict[str, Any]:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + max(0.0, timeout)
        while True:
            remaining = deadline - loop.time()
            detail = await self.get_request(request_id)
            if detail["status"] != "pending":
                return detail
            if remaining <= 0:
                return detail
            chunk = min(20.0, remaining)
            detail = await self._request(
                "GET",
                f"/api/v1/requests/{request_id}/wait",
                params={"timeout": chunk},
            )
            if detail["status"] != "pending":
                return detail
            if loop.time() >= deadline:
                return detail

    async def get_request(self, request_id: str) -> dict[str, Any]:
        return await self._request("GET", f"/api/v1/requests/{request_id}")

    async def cancel(self, request_id: str) -> dict[str, Any]:
        return await self._request("POST", f"/api/v1/requests/{request_id}/cancel")

    async def set_status(
        self,
        status: str,
        *,
        detail: str = "",
        task: str = "",
        name: str = "",
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/api/v1/agents/{self.agent_id}/status",
            json={
                "name": name or self.agent_name,
                "status": status,
                "detail": detail,
                "task": task,
            },
        )

    async def complete_task(
        self,
        title: str,
        *,
        summary: str = "",
        outcome: str = "success",
        duration_ms: int = 0,
        task_id: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "agentId": self.agent_id,
            "agentName": self.agent_name,
            "title": title,
            "summary": summary,
            "outcome": outcome,
            "durationMs": duration_ms,
        }
        if task_id:
            payload["taskId"] = task_id
        return await self._request("POST", "/api/v1/tasks", json=payload)

    # ---- transport ------------------------------------------------------

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            response = await self._client.request(method, path, **kwargs)
        except httpx.HTTPError as exc:
            raise GatewayError(f"gateway transport error: {exc}") from exc
        if response.status_code >= 400:
            try:
                detail = response.json().get("detail")
            except ValueError:
                detail = response.text
            raise GatewayError(
                f"gateway returned {response.status_code}: {detail}",
                response.status_code,
            )
        return response.json()
