"""Environment-backed settings for the gateway."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _env_tuple(name: str) -> tuple[str, ...]:
    raw = os.getenv(name, "")
    return tuple(item.strip() for item in raw.split(",") if item.strip())


@dataclass(slots=True)
class Settings:
    """Runtime configuration. Values come from environment variables."""

    api_token: str = "dev-api-token"
    watch_token: str = "dev-watch-token"
    database_url: str = "sqlite+aiosqlite:///./data/gateway.db"
    server_name: str = "agent-gateway"
    request_ttl_seconds: int = 120
    question_ttl_seconds: int = 300
    expiry_interval_seconds: float = 1.0
    allow_query_token: bool = False
    cors_origins: tuple[str, ...] = field(default_factory=tuple)

    @classmethod
    def from_env(cls) -> "Settings":
        env_file = Path(__file__).resolve().parent.parent / ".env"
        if env_file.is_file():
            load_dotenv(env_file)
        return cls(
            api_token=os.getenv("API_TOKEN", "dev-api-token"),
            watch_token=os.getenv("WATCH_TOKEN", "dev-watch-token"),
            database_url=os.getenv(
                "DATABASE_URL", "sqlite+aiosqlite:///./data/gateway.db"
            ),
            server_name=os.getenv("SERVER_NAME", "agent-gateway"),
            request_ttl_seconds=_env_int("REQUEST_TTL_SECONDS", 120),
            question_ttl_seconds=_env_int("QUESTION_TTL_SECONDS", 300),
            expiry_interval_seconds=_env_float("EXPIRY_INTERVAL_SECONDS", 1.0),
            allow_query_token=_env_bool("ALLOW_QUERY_TOKEN", False),
            cors_origins=_env_tuple("CORS_ORIGINS"),
        )

    def validate(self) -> None:
        if not self.api_token:
            raise ValueError("API_TOKEN must not be empty")
        if not self.watch_token:
            raise ValueError("WATCH_TOKEN must not be empty")
        if self.request_ttl_seconds < 1 or self.question_ttl_seconds < 1:
            raise ValueError("TTL values must be positive")
        if self.expiry_interval_seconds <= 0:
            raise ValueError("EXPIRY_INTERVAL_SECONDS must be positive")
