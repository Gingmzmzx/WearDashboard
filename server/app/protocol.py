"""Protocol v1 envelope and event payload helpers."""

from __future__ import annotations

import time
import uuid
from typing import Any


def now_ms() -> int:
    return int(time.time() * 1000)


def envelope(
    event_type: str,
    payload: dict[str, Any],
    seq: int = 0,
    *,
    event_id: str | None = None,
) -> dict[str, Any]:
    return {
        "v": 1,
        "id": event_id or f"evt_{uuid.uuid4().hex}",
        "seq": int(seq),
        "type": event_type,
        "ts": now_ms(),
        "payload": payload,
    }


def snapshot_envelope(payload: dict[str, Any], seq: int) -> dict[str, Any]:
    return envelope(
        "snapshot",
        payload,
        seq,
        event_id=f"snap_{uuid.uuid4().hex}",
    )


def hello_ok_payload(server_name: str, session_id: str) -> dict[str, Any]:
    return {
        "protocol": 1,
        "serverName": server_name,
        "sessionId": session_id,
        "heartbeatSec": 25,
    }


def error_payload(code: str, message: str, request_id: str | None = None) -> dict[str, Any]:
    payload: dict[str, Any] = {"code": code, "message": message}
    if request_id:
        payload["requestId"] = request_id
    return payload


def error_envelope(code: str, message: str, request_id: str | None = None) -> dict[str, Any]:
    return envelope("error", error_payload(code, message, request_id), 0)


def resolved_payload(
    request_id: str,
    *,
    option_id: str | None,
    resolution: str,
    by: str,
    resolved_at: int,
    device_name: str | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "requestId": request_id,
        "resolution": resolution,
        "by": by,
        "resolvedAt": resolved_at,
    }
    if option_id is not None:
        payload["optionId"] = option_id
    if device_name:
        payload["deviceName"] = device_name
    return payload
