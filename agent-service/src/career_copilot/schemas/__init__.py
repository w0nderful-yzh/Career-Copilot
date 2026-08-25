"""Pydantic 消息协议模型，Agent Runtime 与前端/后端之间的结构化契约。"""

from career_copilot.schemas.message import (
    ChatRequest,
    CopilotResponse,
    MessageBlock,
    NavigationBlock,
    TextBlock,
)

__all__ = [
    "ChatRequest",
    "CopilotResponse",
    "MessageBlock",
    "NavigationBlock",
    "TextBlock",
]