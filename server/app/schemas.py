"""Pydantic models for the REST plugin API and WebSocket payloads."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )


class HelloIn(CamelModel):
    protocol: int = Field(ge=1, le=1)
    device_id: str = Field(default="", max_length=128, alias="deviceId")
    device_name: str = Field(default="", max_length=120, alias="deviceName")
    app_version: str = Field(default="", max_length=32, alias="appVersion")
    cursor: int = Field(default=0, ge=0, alias="cursor")


class AckIn(CamelModel):
    seq: int = Field(ge=0)


class RespondIn(CamelModel):
    request_id: str = Field(min_length=1, max_length=64, alias="requestId")
    option_id: str = Field(min_length=1, max_length=64, alias="optionId")


class AgentOptionIn(CamelModel):
    id: str = Field(min_length=1, max_length=32)
    label: str = Field(min_length=1, max_length=64)
    style: Literal["approve", "deny", "neutral"] = "neutral"


class AgentOptionOut(CamelModel):
    id: str
    label: str
    style: str


DEFAULT_PERMISSION_OPTIONS = (
    AgentOptionIn(id="allow_once", label="允许一次", style="approve"),
    AgentOptionIn(id="deny", label="拒绝", style="deny"),
)


class CreateRequestIn(CamelModel):
    kind: Literal["permission", "question"]
    agent_id: str = Field(min_length=1, max_length=64, alias="agentId")
    agent_name: str = Field(default="", max_length=120, alias="agentName")
    title: str = Field(default="", max_length=120)
    detail: str = Field(default="", max_length=4000)
    risk: Literal["low", "medium", "high"] = "low"
    question: str = Field(default="", max_length=500)
    context: str = Field(default="", max_length=2000)
    ttl_sec: int | None = Field(default=None, ge=1, le=3600, alias="ttlSec")
    options: list[AgentOptionIn] = Field(default_factory=list, max_length=4)
    allow_free_text: bool = Field(default=False, alias="allowFreeText")

    @model_validator(mode="after")
    def normalize(self) -> "CreateRequestIn":
        if self.kind == "question":
            if not self.question.strip():
                raise ValueError("question is required for kind=question")
            if not self.options:
                raise ValueError("options are required for kind=question")
            if not self.title.strip():
                self.title = self.question.strip()[:120]
        else:
            if not self.title.strip():
                raise ValueError("title is required for kind=permission")
            if not self.options:
                self.options = [option.model_copy() for option in DEFAULT_PERMISSION_OPTIONS]
        if len(self.options) > 4:
            raise ValueError("at most 4 options are allowed")
        ids = [option.id for option in self.options]
        if len(ids) != len(set(ids)):
            raise ValueError("option ids must be unique")
        return self


class RequestOut(CamelModel):
    request_id: str
    kind: str
    agent_id: str
    agent_name: str
    title: str
    detail: str = ""
    risk: str = ""
    question: str = ""
    context: str = ""
    created_at: int
    expires_at: int
    allow_free_text: bool = False
    options: list[AgentOptionOut] = Field(default_factory=list)


class RequestDetailOut(RequestOut):
    status: str
    option_id: str | None = None
    resolution: str | None = None
    resolved_at: int | None = None
    resolved_by_device_id: str | None = None


class AgentStatusIn(CamelModel):
    name: str = Field(default="", max_length=120)
    status: Literal["idle", "working", "waiting", "error", "offline"]
    detail: str = Field(default="", max_length=500)
    task: str = Field(default="", max_length=500)


class AgentOut(CamelModel):
    id: str
    name: str
    status: str
    detail: str = ""
    task: str = ""
    updated_at: int


class TaskIn(CamelModel):
    task_id: str | None = Field(default=None, max_length=64, alias="taskId")
    agent_id: str = Field(min_length=1, max_length=64, alias="agentId")
    agent_name: str = Field(default="", max_length=120, alias="agentName")
    title: str = Field(min_length=1, max_length=120)
    summary: str = Field(default="", max_length=1000)
    outcome: Literal["success", "failed", "cancelled"] = "success"
    duration_ms: int = Field(default=0, ge=0, alias="durationMs")


class TaskOut(CamelModel):
    task_id: str
    agent_id: str
    agent_name: str
    title: str
    summary: str = ""
    outcome: str
    duration_ms: int = 0
    completed_at: int


class DeviceCreateIn(CamelModel):
    name: str = Field(min_length=1, max_length=120)


class DeviceOut(CamelModel):
    id: str
    name: str
    token_hint: str
    created_at: int
    last_seen_at: int | None = None
    revoked_at: int | None = None


class DeviceIssuedOut(DeviceOut):
    token: str


class StateOut(CamelModel):
    seq: int
    connections: int
    agent_count: int
    pending_count: int
    agents: list[AgentOut]
    pending: list[RequestDetailOut]
    recent: list[TaskOut]
