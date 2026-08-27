"""FastAPI entrypoint for the Career Copilot Agent Runtime."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI

from career_copilot import __version__
from career_copilot.api.chat import router as chat_router
from career_copilot.api.chat import sync_agent_llm_config
from career_copilot.config import settings

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # 启动时从 Java 同步 Agent 模型配置（失败回落 .env，不阻断启动）
    await sync_agent_llm_config()
    yield


app = FastAPI(
    title="Career Copilot Agent Service",
    description="Agent orchestration runtime for Career Copilot.",
    version=__version__,
    lifespan=lifespan,
)

# Agent 对话统一入口：意图识别 + 短路执行
app.include_router(chat_router)


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "career-copilot-agent-service",
        "backend_base_url": settings.backend_base_url,
    }