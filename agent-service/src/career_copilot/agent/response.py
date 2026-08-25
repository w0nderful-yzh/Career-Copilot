"""结构化响应构建：把意图执行结果组装成前端可渲染的 CopilotResponse。"""

from typing import Any

from career_copilot.schemas.message import (
    CopilotResponse,
    NavigationBlock,
    TextBlock,
)


def text_response(content: str) -> CopilotResponse:
    """纯文本回复。"""
    return CopilotResponse(content=content, blocks=[TextBlock(content=content)])


def navigation_response(
    content: str,
    route: str,
    label: str,
    params: dict[str, Any] | None = None,
) -> CopilotResponse:
    """文本 + 导航建议块：前端渲染按钮，用户点击后由前端路由跳转。"""
    return CopilotResponse(
        content=content,
        blocks=[
            TextBlock(content=content),
            NavigationBlock(route=route, label=label, params=params or {}),
        ],
    )