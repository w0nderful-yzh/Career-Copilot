"""聊天消息协议：请求、响应与结构化消息块。

块类型与 Career Copilot 前端渲染器约定一致，禁止 LLM 直接返回任意 UI 代码。
受控 Block：text / action / choice / resume_summary / interview_summary / knowledge_citations。
"""

from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field

from career_copilot.schemas.action import ActionSelected


class AttachmentRef(BaseModel):
    """用户随消息附带的结构化资源引用（文件二进制不经 Agent，只传资源 id）。"""

    kind: Literal["resume"] = "resume"
    resume_id: int = Field(description="简历资源 id（Java resume 主键）")
    filename: str | None = Field(default=None, description="原始文件名")
    duplicate: bool = Field(
        default=False,
        description="Java 判定为内容重复、未新增记录时置 true（复用已有简历）",
    )


class ChatRequest(BaseModel):
    """用户发送给 Copilot 的消息。"""

    message: str = Field(
        default="",
        max_length=4000,
        description="用户消息（仅附件或 action 提交时可为空）",
    )
    conversation_id: str | int | None = Field(
        default=None, description="会话 ID（Java conversation 主键，前端传数字）"
    )
    attachments: list[AttachmentRef] = Field(
        default_factory=list, description="消息附带的结构化资源引用"
    )
    action: ActionSelected | None = Field(
        default=None, description="按钮点击回传的确定性动作（与 message 二选一）"
    )


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


class ChoiceOption(BaseModel):
    """选择块中的单个选项：点击后回传 ActionSelected。"""

    action: str = Field(description="动作 key，前端原样回传")
    label: str = Field(description="按钮文案")
    payload: dict[str, Any] = Field(
        default_factory=dict, description="动作参数（如 resumeId）"
    )


class ChoiceBlock(BaseModel):
    """选择块：向用户展示一组确定性动作选项（如附件识别后的操作选择）。"""

    type: Literal["choice"] = "choice"
    title: str | None = Field(default=None, description="选择说明标题")
    options: list[ChoiceOption] = Field(default_factory=list, description="可选动作")


MessageBlock = Annotated[
    TextBlock
    | ActionBlock
    | ChoiceBlock
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