"""聊天消息协议：请求、响应与结构化消息块。

块类型与 Career Copilot 前端渲染器约定一致，禁止 LLM 直接返回任意 UI 代码。
首批受控 Block：text / action / resume_summary / interview_summary / knowledge_citations。
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


class ActionBlock(BaseModel):
    """动作建议块：前端根据白名单路由映射到真实路由，仅由用户点击执行。"""

    type: Literal["action"] = "action"
    route: str = Field(description="白名单路由 key（如 INTERVIEW_CREATE），由前端映射")
    label: str = Field(description="按钮文案")
    params: dict[str, Any] = Field(default_factory=dict, description="路由参数")


class ResumeSummaryBlock(BaseModel):
    """简历摘要卡片：展示简历列表与最新分析分数。"""

    type: Literal["resume_summary"] = "resume_summary"
    resumes: list[dict[str, Any]] = Field(
        default_factory=list, description="简历摘要（已裁剪字段）"
    )


class InterviewSummaryBlock(BaseModel):
    """面试总结卡片：展示最近模拟面试记录。"""

    type: Literal["interview_summary"] = "interview_summary"
    interviews: list[dict[str, Any]] = Field(
        default_factory=list, description="面试摘要（已裁剪字段）"
    )


class KnowledgeCitationsBlock(BaseModel):
    """知识引用卡片：展示 RAG 回答引用的知识库来源。"""

    type: Literal["knowledge_citations"] = "knowledge_citations"
    citations: list[dict[str, Any]] = Field(
        default_factory=list, description="引用来源（知识库 id 与名称）"
    )


MessageBlock = Annotated[
    TextBlock
    | ActionBlock
    | ResumeSummaryBlock
    | InterviewSummaryBlock
    | KnowledgeCitationsBlock,
    Field(discriminator="type"),
]


class CopilotResponse(BaseModel):
    """Agent 统一响应：文本 + 结构化块列表。"""

    content: str = Field(description="自然语言回复")
    blocks: list[MessageBlock] = Field(default_factory=list, description="结构化块")


class StreamEvent(BaseModel):
    """SSE 流式事件：block / message_delta / error / done。"""

    type: Literal["block", "message_delta", "error", "done"]
    payload: dict[str, Any] = Field(default_factory=dict)