"""聊天消息协议：请求、响应与结构化消息块。

块类型与 Career Copilot 前端渲染器约定一致，禁止 LLM 直接返回任意 UI 代码。
"""

from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    """用户发送给 Copilot 的消息。"""

    message: str = Field(min_length=1, max_length=4000, description="用户消息")
    conversation_id: str | None = Field(default=None, description="会话 ID（可选）")


class TextBlock(BaseModel):
    """纯文本块。"""

    type: Literal["text"] = "text"
    content: str


class NavigationBlock(BaseModel):
    """导航建议块：前端根据 route key 映射到具体路由并渲染按钮。"""

    type: Literal["navigation"] = "navigation"
    route: str = Field(description="路由 key（如 INTERVIEW_CREATE），由前端映射")
    label: str = Field(description="按钮文案")
    params: dict[str, Any] = Field(default_factory=dict, description="路由参数")


MessageBlock = Annotated[TextBlock | NavigationBlock, Field(discriminator="type")]


class CopilotResponse(BaseModel):
    """Agent 统一响应：文本 + 结构化块列表。"""

    content: str = Field(description="自然语言回复")
    blocks: list[MessageBlock] = Field(default_factory=list, description="结构化块")