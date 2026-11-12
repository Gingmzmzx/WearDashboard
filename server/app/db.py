"""Async SQLite engine/session setup."""

from __future__ import annotations

from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

from .models import Base, Counter


class Database:
    def __init__(self, url: str) -> None:
        self.url = url
        connect_args = {}
        if url.startswith("sqlite"):
            connect_args["timeout"] = 30
        self.engine: AsyncEngine = create_async_engine(
            url,
            connect_args=connect_args,
            pool_pre_ping=True,
        )
        if url.startswith("sqlite"):
            event.listen(self.engine.sync_engine, "connect", self._enable_sqlite_pragmas)
        self.session_factory = async_sessionmaker(
            self.engine, class_=AsyncSession, expire_on_commit=False
        )

    @staticmethod
    def _enable_sqlite_pragmas(dbapi_connection, _connection_record) -> None:
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA synchronous=NORMAL")
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.close()

    async def init(self) -> None:
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        async with self.session_factory() as session:
            counter = await session.get(Counter, "seq")
            if counter is None:
                session.add(Counter(name="seq", value=0))
                await session.commit()

    async def dispose(self) -> None:
        await self.engine.dispose()

    async def seq(self, session: AsyncSession) -> int:
        """Read the current event sequence without allocating a new one."""
        result = await session.execute(text("SELECT value FROM counters WHERE name = 'seq'"))
        value = result.scalar_one_or_none()
        return int(value or 0)

    async def next_seq(self, session: AsyncSession) -> int:
        """Atomically allocate the next event sequence inside the caller's transaction."""
        result = await session.execute(
            text(
                "UPDATE counters SET value = value + 1 "
                "WHERE name = 'seq' RETURNING value"
            )
        )
        value = result.scalar_one()
        return int(value)
