"""WebSocket endpoint implementing watch protocol v1."""

from __future__ import annotations

import json
import logging
import uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from .auth import parse_bearer
from .protocol import envelope, error_envelope, hello_ok_payload
from .schemas import HelloIn, RespondIn
from .service import GatewayService

logger = logging.getLogger(__name__)
router = APIRouter()


def _extract_token(websocket: WebSocket) -> str | None:
    token = parse_bearer(websocket.headers.get("authorization"))
    if token:
        return token
    settings = websocket.app.state.settings
    if settings.allow_query_token:
        return websocket.query_params.get("token")
    return None


@router.websocket("/agent")
async def agent_socket(websocket: WebSocket) -> None:
    settings = websocket.app.state.settings
    service: GatewayService = websocket.app.state.service
    hub = websocket.app.state.hub

    token = _extract_token(websocket)
    device = await service.authenticate_device(token)
    if device is None:
        logger.warning("websocket auth failed for %s", websocket.client)
        await websocket.accept()
        await websocket.close(code=4401, reason="unauthorized")
        return

    await websocket.accept()
    logger.info("websocket accepted for device=%s from=%s", device.id, websocket.client)
    connection = await hub.connect(device.id, websocket)
    await service.touch_device(device.id)
    hello_received = False

    try:
        while True:
            try:
                raw = await websocket.receive_text()
            except WebSocketDisconnect:
                break
            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                await hub.send(connection, error_envelope("bad_json", "Frame is not valid JSON"))
                continue
            if not isinstance(message, dict):
                await hub.send(connection, error_envelope("bad_json", "Frame must be an object"))
                continue
            if message.get("v") != 1:
                await hub.send(connection, error_envelope("bad_version", "Only protocol v1 is supported"))
                continue
            message_type = message.get("type")

            if message_type == "hello":
                if hello_received:
                    await hub.send(
                        connection,
                        error_envelope("already_hello", "This connection already said hello"),
                    )
                    continue
                try:
                    hello = HelloIn.model_validate(message.get("payload") or {})
                except ValidationError as exc:
                    await hub.send(
                        connection,
                        error_envelope("bad_request", f"Invalid hello: {exc.errors()[0]['msg']}"),
                    )
                    continue
                session_id = f"session_{uuid.uuid4().hex[:12]}"
                hello_message = envelope(
                    "hello.ok",
                    hello_ok_payload(settings.server_name, session_id),
                )
                if not await hub.send(connection, hello_message):
                    break
                snapshot = await service.snapshot_message()
                logger.info(
                    "sending snapshot to device=%s seq=%s",
                    device.id,
                    snapshot.get("seq"),
                )
                if not await hub.send(connection, snapshot):
                    break
                await hub.activate(connection)
                hello_received = True
                logger.info(
                    "hello from device=%s client=%s name=%s cursor=%s",
                    device.id,
                    hello.device_id,
                    hello.device_name,
                    hello.cursor,
                )
                continue

            if not hello_received:
                await hub.send(
                    connection,
                    error_envelope("hello_required", "Send hello before other messages"),
                )
                continue

            if message_type == "ack":
                continue

            if message_type == "request.respond":
                try:
                    response = RespondIn.model_validate(message.get("payload") or {})
                except ValidationError as exc:
                    await hub.send(
                        connection,
                        error_envelope(
                            "bad_request",
                            f"Invalid response: {exc.errors()[0]['msg']}",
                        ),
                    )
                    continue
                result = await service.respond(
                    device, response.request_id, response.option_id
                )
                if result.status == "answered":
                    continue
                if result.status == "already_answered_same" and result.resolved:
                    await hub.send(
                        connection,
                        envelope("request.resolved", result.resolved, 0),
                    )
                    continue
                if result.status == "expired":
                    await hub.send(
                        connection,
                        error_envelope(
                            "request_expired",
                            "Request expired before the decision arrived",
                            response.request_id,
                        ),
                    )
                    continue
                if result.status == "already_resolved":
                    await hub.send(
                        connection,
                        error_envelope(
                            "already_resolved",
                            "Request was already resolved",
                            response.request_id,
                        ),
                    )
                    continue
                if result.status == "invalid_option":
                    await hub.send(
                        connection,
                        error_envelope(
                            "invalid_option",
                            "Unknown optionId for this request",
                            response.request_id,
                        ),
                    )
                    continue
                await hub.send(
                    connection,
                    error_envelope(
                        "request_not_found",
                        "Unknown or cleaned-up request",
                        response.request_id,
                    ),
                )
                continue

            await hub.send(
                connection,
                error_envelope("unknown_type", f"Unsupported client type: {message_type}"),
            )
    except WebSocketDisconnect as exc:
        logger.info("websocket disconnected device=%s code=%s", device.id, exc.code)
    except Exception:
        logger.exception("websocket failure for device %s", device.id)
    finally:
        await hub.disconnect(connection)
