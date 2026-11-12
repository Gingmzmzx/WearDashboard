from __future__ import annotations

import warnings

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


@pytest.fixture()
def settings(tmp_path) -> Settings:
    return Settings(
        api_token="test-api-token",
        watch_token="test-watch-token",
        database_url=f"sqlite+aiosqlite:///{tmp_path}/gateway.db",
        server_name="test-gateway",
        request_ttl_seconds=120,
        question_ttl_seconds=300,
        expiry_interval_seconds=0.1,
    )


@pytest.fixture()
def client(settings: Settings):
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        app = create_app(settings)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def api_headers() -> dict[str, str]:
    return {"Authorization": "Bearer test-api-token"}


@pytest.fixture()
def watch_headers() -> dict[str, str]:
    return {"Authorization": "Bearer test-watch-token"}


def hello_message(cursor: int = 0) -> dict:
    return {
        "v": 1,
        "id": "test_hello",
        "type": "hello",
        "ts": 1760000000000,
        "payload": {
            "protocol": 1,
            "deviceId": "client-generated-id",
            "deviceName": "Test Watch",
            "appVersion": "1.0",
            "cursor": cursor,
        },
    }


def permission_payload(**overrides) -> dict:
    payload = {
        "kind": "permission",
        "agentId": "claude-code",
        "agentName": "Claude Code",
        "title": "Run build command?",
        "detail": "rm -rf build && ./gradlew assembleDebug",
        "risk": "high",
        "ttlSec": 120,
        "options": [
            {"id": "allow_once", "label": "Allow once", "style": "approve"},
            {"id": "deny", "label": "Deny", "style": "deny"},
        ],
    }
    payload.update(overrides)
    return payload
