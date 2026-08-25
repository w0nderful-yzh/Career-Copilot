"""FastAPI entrypoint for the Career Copilot Agent Runtime."""

from fastapi import FastAPI

from career_copilot import __version__
from career_copilot.config import settings

app = FastAPI(
    title="Career Copilot Agent Service",
    description="Agent orchestration runtime for Career Copilot.",
    version=__version__,
)


@app.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "service": "career-copilot-agent-service",
        "backend_base_url": settings.backend_base_url,
    }
