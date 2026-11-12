"""Demo agent workflow against a running gateway.

    export GATEWAY_URL=http://127.0.0.1:8787
    export API_TOKEN=dev-api-token
    python examples/demo_flow.py
"""

from __future__ import annotations

import asyncio
import os
import time

from agent_client import GatewayClient


async def main() -> None:
    base_url = os.getenv("GATEWAY_URL", "http://127.0.0.1:8787")
    api_token = os.getenv("API_TOKEN", "dev-api-token")
    async with GatewayClient(
        base_url,
        api_token,
        agent_id="claude-code",
        agent_name="Claude Code",
    ) as gateway:
        await gateway.set_status(
            "working", detail="Preparing a refactor", task="Refactor auth middleware"
        )

        decision = await gateway.ask_permission(
            "运行构建清理命令？",
            detail="rm -rf build && ./gradlew assembleDebug",
            risk="high",
            ttl_sec=120,
        )
        print(f"decision: {decision}")
        if decision == "deny":
            await gateway.complete_task(
                "重构登录中间件",
                summary="用户拒绝了构建清理命令，任务已中止",
                outcome="cancelled",
            )
            await gateway.set_status("idle", detail="Waiting for a new task")
            return

        started = int(time.time() * 1000)
        await asyncio.sleep(1.0)
        await gateway.complete_task(
            "重构登录中间件",
            summary="已更新 4 个文件，全部测试通过",
            outcome="success",
            duration_ms=int(time.time() * 1000) - started,
        )
        await gateway.set_status("idle", detail="Waiting for a new task")


if __name__ == "__main__":
    asyncio.run(main())
