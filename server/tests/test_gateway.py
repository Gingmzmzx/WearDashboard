from __future__ import annotations

import time

import warnings

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import create_app
from .conftest import hello_message, permission_payload


def open_socket(client: TestClient, headers: dict[str, str], cursor: int = 0):
    websocket = client.websocket_connect("/agent", headers=headers)
    socket = websocket.__enter__()
    socket.send_json(hello_message(cursor))
    hello_ok = socket.receive_json()
    snapshot = socket.receive_json()
    assert hello_ok["type"] == "hello.ok"
    assert hello_ok["payload"]["protocol"] == 1
    assert snapshot["type"] == "snapshot"
    return websocket, socket, snapshot


def test_watch_handshake_and_permission_response(client, api_headers, watch_headers):
    manager, socket, snapshot = open_socket(client, watch_headers)
    try:
        assert snapshot["payload"]["pending"] == []

        created = client.post(
            "/api/v1/requests", json=permission_payload(), headers=api_headers
        )
        assert created.status_code == 201, created.text
        request = created.json()
        assert request["requestId"].startswith("req_")
        assert request["status"] == "pending"
        assert request["options"][0]["id"] == "allow_once"

        event = socket.receive_json()
        assert event["type"] == "permission.request"
        assert event["payload"]["requestId"] == request["requestId"]
        assert event["payload"]["risk"] == "high"
        assert event["seq"] > 0

        socket.send_json({
            "v": 1,
            "id": "respond_1",
            "type": "request.respond",
            "ts": int(time.time() * 1000),
            "payload": {
                "requestId": request["requestId"],
                "optionId": "allow_once",
            },
        })
        resolved = socket.receive_json()
        assert resolved["type"] == "request.resolved"
        assert resolved["payload"]["requestId"] == request["requestId"]
        assert resolved["payload"]["optionId"] == "allow_once"
        assert resolved["payload"]["resolution"] == "answered"

        detail = client.get(
            f"/api/v1/requests/{request['requestId']}", headers=api_headers
        )
        assert detail.status_code == 200
        assert detail.json()["status"] == "answered"
        assert detail.json()["optionId"] == "allow_once"
        assert detail.json()["resolvedByDeviceId"].startswith("dev_")

        # Repeating the same decision is idempotent and returns resolved again.
        socket.send_json({
            "v": 1,
            "id": "respond_2",
            "type": "request.respond",
            "ts": int(time.time() * 1000),
            "payload": {
                "requestId": request["requestId"],
                "optionId": "allow_once",
            },
        })
        repeated = socket.receive_json()
        assert repeated["type"] == "request.resolved"
        assert repeated["payload"]["requestId"] == request["requestId"]
    finally:
        manager.__exit__(None, None, None)


def test_duplicate_different_option_is_rejected(client, api_headers, watch_headers):
    manager, socket, _ = open_socket(client, watch_headers)
    try:
        request = client.post(
            "/api/v1/requests", json=permission_payload(risk="low"), headers=api_headers
        ).json()
        socket.receive_json()  # permission.request

        socket.send_json({
            "v": 1, "id": "r1", "type": "request.respond", "ts": 1,
            "payload": {"requestId": request["requestId"], "optionId": "allow_once"},
        })
        assert socket.receive_json()["type"] == "request.resolved"

        socket.send_json({
            "v": 1, "id": "r2", "type": "request.respond", "ts": 2,
            "payload": {"requestId": request["requestId"], "optionId": "deny"},
        })
        error = socket.receive_json()
        assert error["type"] == "error"
        assert error["payload"]["code"] == "already_resolved"
        assert error["payload"]["requestId"] == request["requestId"]

        detail = client.get(
            f"/api/v1/requests/{request['requestId']}", headers=api_headers
        ).json()
        assert detail["status"] == "answered"
        assert detail["optionId"] == "allow_once"
    finally:
        manager.__exit__(None, None, None)


def test_snapshot_restores_pending_and_recent_tasks(client, api_headers, watch_headers):
    pending = client.post(
        "/api/v1/requests",
        json=permission_payload(kind="question", title="Which path?", question="Which path?",
                                context="Two files conflict.", risk="low",
                                options=[
                                    {"id": "a", "label": "Option A", "style": "neutral"},
                                    {"id": "b", "label": "Option B", "style": "neutral"},
                                ]),
        headers=api_headers,
    ).json()
    task = client.post(
        "/api/v1/tasks",
        json={
            "taskId": "task_snapshot",
            "agentId": "claude-code",
            "agentName": "Claude Code",
            "title": "Build finished",
            "summary": "All tests passed",
            "outcome": "success",
            "durationMs": 1234,
        },
        headers=api_headers,
    )
    assert task.status_code == 201, task.text

    manager, socket, snapshot = open_socket(client, watch_headers)
    try:
        assert len(snapshot["payload"]["pending"]) == 1
        assert snapshot["payload"]["pending"][0]["requestId"] == pending["requestId"]
        assert len(snapshot["payload"]["recent"]) == 1
        assert snapshot["payload"]["recent"][0]["taskId"] == "task_snapshot"
        assert any(agent["id"] == "claude-code" for agent in snapshot["payload"]["agents"])
    finally:
        manager.__exit__(None, None, None)


def test_expiry_and_long_poll(client, api_headers, watch_headers):
    manager, socket, _ = open_socket(client, watch_headers)
    try:
        request = client.post(
            "/api/v1/requests",
            json=permission_payload(ttlSec=1),
            headers=api_headers,
        ).json()
        assert socket.receive_json()["type"] == "permission.request"

        pending = client.get(
            f"/api/v1/requests/{request['requestId']}/wait?timeout=0.1",
            headers=api_headers,
        )
        assert pending.status_code == 200
        assert pending.json()["status"] == "pending"

        resolved = socket.receive_json()
        assert resolved["type"] == "request.resolved"
        assert resolved["payload"]["resolution"] == "expired"

        detail = client.get(
            f"/api/v1/requests/{request['requestId']}", headers=api_headers
        ).json()
        assert detail["status"] == "expired"
    finally:
        manager.__exit__(None, None, None)


def test_agent_status_and_task_events(client, api_headers, watch_headers):
    manager, socket, _ = open_socket(client, watch_headers)
    try:
        status = client.post(
            "/api/v1/agents/planner/status",
            json={
                "name": "Planner",
                "status": "working",
                "detail": "Planning the next step",
                "task": "Refactor auth",
            },
            headers=api_headers,
        )
        assert status.status_code == 200, status.text
        event = socket.receive_json()
        assert event["type"] == "agent.status"
        assert event["payload"]["id"] == "planner"
        assert event["payload"]["status"] == "working"

        task = client.post(
            "/api/v1/tasks",
            json={
                "agentId": "planner",
                "agentName": "Planner",
                "title": "Plan ready",
                "summary": "3 steps",
                "outcome": "success",
            },
            headers=api_headers,
        )
        assert task.status_code == 201
        event = socket.receive_json()
        assert event["type"] == "task.completed"
        assert event["payload"]["agentId"] == "planner"
    finally:
        manager.__exit__(None, None, None)


def test_device_token_lifecycle(client, api_headers):
    issued = client.post(
        "/api/v1/devices", json={"name": "Kitchen Phone"}, headers=api_headers
    )
    assert issued.status_code == 201, issued.text
    device = issued.json()
    assert device["token"]
    assert device["tokenHint"] == device["token"][-4:]

    new_headers = {"Authorization": f"Bearer {device['token']}"}
    manager, socket, _ = open_socket(client, new_headers)
    manager.__exit__(None, None, None)

    devices = client.get("/api/v1/devices", headers=api_headers).json()
    assert any(item["id"] == device["id"] for item in devices)

    revoked = client.delete(f"/api/v1/devices/{device['id']}", headers=api_headers)
    assert revoked.status_code == 204

    with client.websocket_connect("/agent", headers=new_headers) as socket:
        with pytest.raises(WebSocketDisconnect) as excinfo:
            socket.receive_json()
        assert excinfo.value.code == 4401


def test_authentication_is_required(client, watch_headers):
    assert client.get("/api/v1/state").status_code == 401
    assert client.get("/api/v1/state", headers={
        "Authorization": "Bearer wrong"
    }).status_code == 401
    assert client.get("/healthz").status_code == 200

    with client.websocket_connect("/agent", headers={
        "Authorization": "Bearer wrong"
    }) as socket:
        with pytest.raises(WebSocketDisconnect) as excinfo:
            socket.receive_json()
        assert excinfo.value.code == 4401

    # A watch token cannot call the plugin API.
    assert client.get("/api/v1/state", headers=watch_headers).status_code == 401


def test_default_options_and_validation(client, api_headers):
    created = client.post(
        "/api/v1/requests",
        json={
            "kind": "permission",
            "agentId": "claude-code",
            "title": "Read config?",
            "risk": "low",
        },
        headers=api_headers,
    )
    assert created.status_code == 201, created.text
    options = created.json()["options"]
    assert [option["id"] for option in options] == ["allow_once", "deny"]

    invalid = client.post(
        "/api/v1/requests",
        json={
            "kind": "question",
            "agentId": "claude-code",
            "question": "Which one?",
        },
        headers=api_headers,
    )
    assert invalid.status_code == 422

    pending = client.get(
        "/api/v1/requests?status=pending", headers=api_headers
    )
    assert pending.status_code == 200
    assert any(item["requestId"] == created.json()["requestId"] for item in pending.json())


def test_pending_state_survives_restart(settings, api_headers, watch_headers):
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        first_app = create_app(settings)
    with TestClient(first_app) as first:
        created = first.post(
            "/api/v1/requests", json=permission_payload(), headers=api_headers
        ).json()
        assert created["status"] == "pending"

    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        second_app = create_app(settings)
    with TestClient(second_app) as second:
        with second.websocket_connect("/agent", headers=watch_headers) as socket:
            socket.send_json(hello_message())
            assert socket.receive_json()["type"] == "hello.ok"
            snapshot = socket.receive_json()
            assert snapshot["type"] == "snapshot"
            pending_ids = [item["requestId"] for item in snapshot["payload"]["pending"]]
            assert created["requestId"] in pending_ids
            assert snapshot["seq"] >= 1
