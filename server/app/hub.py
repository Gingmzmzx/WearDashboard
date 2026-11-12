"""In-process WebSocket fan-out. Run one uvicorn worker when using this hub."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket
from starlette.websockets import WebSocketDisconnect

logger = logging.getLogger(__name__)


@dataclass(eq=False)
class Connection:
    device_id: str
    websocket: WebSocket
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    ready: bool = False
    buffered: list[dict[str, Any]] = field(default_factory=list)


class ConnectionHub:
    def __init__(self) -> None:
        self._connections: dict[str, set[Connection]] = {}
        self._lock = asyncio.Lock()

    async def connect(self, device_id: str, websocket: WebSocket) -> Connection:
        connection = Connection(device_id=device_id, websocket=websocket)
        async with self._lock:
            self._connections.setdefault(device_id, set()).add(connection)
        logger.info("device %s connected (%d total)", device_id, self.count())
        return connection

    async def disconnect(self, connection: Connection) -> None:
        async with self._lock:
            connections = self._connections.get(connection.device_id)
            if connections is not None:
                connections.discard(connection)
                if not connections:
                    self._connections.pop(connection.device_id, None)
        logger.info("device %s disconnected (%d total)",
                    connection.device_id, self.count())

    def count(self) -> int:
        return sum(len(connections) for connections in self._connections.values())

    async def send(self, connection: Connection, message: dict[str, Any]) -> bool:
        try:
            async with connection.send_lock:
                await connection.websocket.send_json(message)
            return True
        except (WebSocketDisconnect, RuntimeError, OSError):
            return False

    async def activate(self, connection: Connection) -> None:
        """Flush events buffered while the initial snapshot was being sent.

        The connection stays non-ready while draining, so events produced
        during the drain keep their order behind the snapshot and earlier
        events. Ready flips only when the buffer is observed empty under lock.
        """
        while True:
            async with self._lock:
                buffered = connection.buffered
                connection.buffered = []
                if not buffered:
                    connection.ready = True
                    return
            for message in buffered:
                if not await self.send(connection, message):
                    await self.disconnect(connection)
                    return

    async def send_to_device(self, device_id: str, message: dict[str, Any]) -> None:
        async with self._lock:
            connections = list(self._connections.get(device_id, ()))
        for connection in connections:
            if connection.ready:
                await self.send(connection, message)

    async def broadcast(self, message: dict[str, Any]) -> None:
        direct: list[Connection] = []
        async with self._lock:
            for group in self._connections.values():
                for connection in group:
                    if connection.ready:
                        direct.append(connection)
                    elif len(connection.buffered) < 1000:
                        connection.buffered.append(message)
        results = await asyncio.gather(
            *(self.send(connection, message) for connection in direct),
            return_exceptions=True,
        )
        failed = [connection for connection, result in zip(direct, results)
                  if result is not True]
        for connection in failed:
            await self.disconnect(connection)

    async def close_device(self, device_id: str, code: int = 4003,
                           reason: str = "device revoked") -> None:
        async with self._lock:
            connections = list(self._connections.get(device_id, ()))
        for connection in connections:
            try:
                await connection.websocket.close(code=code, reason=reason)
            except (WebSocketDisconnect, RuntimeError, OSError):
                pass

    async def close_all(self) -> None:
        async with self._lock:
            connections = [
                connection
                for group in self._connections.values()
                for connection in group
            ]
            self._connections.clear()
        for connection in connections:
            try:
                await connection.websocket.close(code=1001, reason="server shutdown")
            except (WebSocketDisconnect, RuntimeError, OSError):
                pass
