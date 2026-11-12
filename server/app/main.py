"""FastAPI application factory and lifespan."""

from __future__ import annotations

import asyncio
import logging
import warnings
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .api import router as api_router
from .config import Settings
from .db import Database
from .hub import ConnectionHub
from .service import GatewayService
from .websocket import router as websocket_router

logger = logging.getLogger(__name__)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)


async def _expiry_loop(service: GatewayService, interval: float) -> None:
    while True:
        try:
            await service.expire_due_requests()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("expiry sweep failed")
        await asyncio.sleep(interval)


def _ensure_sqlite_parent(url: str) -> None:
    marker = ":///"
    if not url.startswith("sqlite") or marker not in url:
        return
    path = url.split(marker, 1)[1]
    if not path or path == ":memory:" or path.startswith("file:"):
        return
    Path(path).expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.validate()
    _ensure_sqlite_parent(settings.database_url)

    if settings.api_token == "dev-api-token" or settings.watch_token == "dev-watch-token":
        warnings.warn(
            "Using development tokens. Set API_TOKEN and WATCH_TOKEN before exposing "
            "the gateway to a network.",
            RuntimeWarning,
            stacklevel=2,
        )

    db = Database(settings.database_url)
    hub = ConnectionHub()
    service = GatewayService(db, hub, settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        await db.init()
        await service.seed_env_device()
        expiry_task = asyncio.create_task(
            _expiry_loop(service, settings.expiry_interval_seconds)
        )
        logger.info("gateway ready, database=%s", settings.database_url)
        try:
            yield
        finally:
            expiry_task.cancel()
            try:
                await expiry_task
            except asyncio.CancelledError:
                pass
            await hub.close_all()
            await db.dispose()

    app = FastAPI(
        title="Watch Agent Gateway",
        version=__version__,
        description=(
            "WebSocket protocol v1 gateway for the Watch Agent watch/phone app, "
            "plus a REST API for agent plugins. See /docs for the plugin endpoints."
        ),
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.db = db
    app.state.hub = hub
    app.state.service = service

    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=list(settings.cors_origins),
            allow_credentials=False,
            allow_methods=["GET", "POST", "DELETE"],
            allow_headers=["Authorization", "Content-Type", "X-API-Key"],
        )

    app.include_router(api_router)
    app.include_router(websocket_router)

    @app.get("/", tags=["system"])
    async def root() -> dict[str, object]:
        return {
            "name": "Watch Agent Gateway",
            "version": __version__,
            "websocket": "/agent",
            "docs": "/docs",
            "health": "/healthz",
        }

    return app


app = create_app()
