"""聊天消息协议：请求、响应与结构化消息块。

块类型与 Career Copilot 前端渲染器约定一致，禁止 LLM 直接返回任意 UI 代码。
受控 Block：text / action / choice / resume_summary / interview_summary / knowledge_citations。
"""

from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field

from career_copilot.schemas.action import ActionSelected


class AttachmentRef(BaseModel):
    """用户随消息附带的结构化资源引用（文件二进制不经 Agent，只传资源 id）。"""

    kind: Literal["resume", "job_description"] = "resume"
    resume_id: int | None = Field(
        default=None, description="简历资源 id（Java resume 主键；kind=resume 时必填）"
    )
    job_id: int | None = Field(
        default=None,
        description="JD 资源 id（Java job_descriptions 主键；kind=job_description 时必填）",
    )
    filename: str | None = Field(default=None, description="原始文件名")
    duplicate: bool = Field(
        default=False,
        description="Java 判定为内容重复、未新增记录时置 true（复用已有简历；JD 不去重）",
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


class NavigationBlock(BaseModel):
    """导航跳转块：Agent 建议进入某个业务页（如创建成功的面试会话页）。

    与 ActionBlock 的区别：ActionBlock 由用户点击后才跳转；
    NavigationBlock 是 Agent 完成确定性写操作后（如面试创建成功）主动给出的
    导航入口，仍由前端白名单校验路由与参数，禁止任意 URL。
    """

    type: Literal["navigation"] = "navigation"
    route: str = Field(description="白名单路由 key（如 INTERVIEW_SESSION），由前端映射")
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


class SkillEvidenceItem(BaseModel):
    """单条技能证据：一次可追溯的评分来源（如某场面试的某道题）。"""

    sourceType: Literal["RESUME", "INTERVIEW_SESSION", "INTERVIEW_TURN"] = Field(
        description="证据来源类型"
    )
    sourceId: str = Field(description="来源标识（面试轮次为 sessionId:questionIndex）")
    score: int = Field(description="该证据的评分 (0-100)")
    occurredAt: str | None = Field(default=None, description="证据发生时间")


class SkillProfileBlock(BaseModel):
    """技能画像卡片：Evidence-driven 聚合分 + 证据明细。

    score 由 Java Profile Aggregator 按证据均值计算，LLM 不得改写数值，
    只负责在自然语言中解读强弱项。
    """

    type: Literal["skill_profile"] = "skill_profile"
    skills: list[dict[str, Any]] = Field(
        default_factory=list,
        description="技能列表：skill/score/evidenceCount/evidences（已裁剪字段）",
    )


class ResumeOptimizationPatch(BaseModel):
    """单条简历优化建议（前端 Diff 卡片渲染）。"""

    id: str = Field(description="patch id（勾选回传用）")
    type: Literal["REPLACE", "ADD", "DELETE"] = Field(description="修改类型")
    path: str = Field(description="JSON path")
    oldValue: str | None = Field(default=None, description="原值")
    newValue: str | None = Field(default=None, description="新值")
    reason: str = Field(description="修改理由")


class ResumeOptimizationBlock(BaseModel):
    """简历优化提案块（P2-1）：Patch Diff 卡片 + 勾选应用（HITL 确认）。

    用户勾选后前端回传 APPLY_RESUME_PATCHES action（只带 proposalId + patchIds），
    由 apply_resume_patches CONFIRM_WRITE Tool 在 Java 侧应用并生成新版本。
    """

    type: Literal["resume_optimization"] = "resume_optimization"
    proposalId: int = Field(description="提案 id（应用回传必带）")
    resumeId: int = Field(description="目标简历 id")
    versionId: int = Field(description="基于的版本 id")
    summary: str = Field(default="", description="Agent 对本轮优化的总结")
    patches: list[ResumeOptimizationPatch] = Field(
        default_factory=list, description="修改建议列表（默认全部勾选）"
    )
    rejectedNote: str | None = Field(
        default=None, description="被校验器剔除的建议说明（如实告知）"
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


class InterviewProposalBlock(BaseModel):
    """面试提案确认块：Agent 推荐的面试配置 + [按推荐开始] / [调整配置]。

    direction 使用 Java 面试方向 skillId（如 java-backend），difficulty 使用
    Java 难度枚举（junior/mid/senior）。focus 为候选重点分类 key（如 JVM/Redis）。
    """

    type: Literal["interview_proposal"] = "interview_proposal"
    direction: str = Field(description="面试方向 skillId（与 list_skills 对齐）")
    direction_name: str = Field(description="面试方向展示名（如 Java 后端）")
    difficulty: str = Field(description="难度枚举（junior/mid/senior）")
    difficulty_name: str = Field(description="难度展示名（如 校招）")
    mode: Literal["TEXT", "VOICE"] = Field(default="TEXT", description="面试模式（一期仅文字）")
    focus: list[str] = Field(default_factory=list, description="重点考察方向（分类 key）")
    question_count: int = Field(default=8, description="题目数量")
    resume_id: int | None = Field(default=None, description="基于的简历（可选）")
    summary: str = Field(default="", description="推荐理由（一句话）")


class InterviewSessionBlock(BaseModel):
    """内嵌面试会话块（P4-0）：面试创建成功后的原地内嵌载体。

    只携带展示信息（方向/难度/模式/focus），不含每轮问答；
    前端块组件持有 sessionId 后直连 Java Interview API 拉取会话/提交答案/轮询评估，
    不经过 Agent Graph（实时面试边界，见 Inline 设计 §3）。
    """

    type: Literal["interview_session"] = "interview_session"
    session_id: str = Field(description="Java 面试会话 ID")
    skill_id: str | None = Field(default=None, description="Java skillId（如 java-backend）")
    difficulty: str | None = Field(default=None, description="难度枚举（junior/mid/senior）")
    mode: Literal["TEXT", "VOICE"] = Field(default="TEXT", description="面试模式")
    focus: list[str] = Field(default_factory=list, description="重点考察方向")
    question_count: int | None = Field(default=None, description="题目数量")
    direction_name: str | None = Field(default=None, description="方向展示名（如 Java 后端）")


MessageBlock = Annotated[
    TextBlock
    | ActionBlock
    | NavigationBlock
    | ChoiceBlock
    | ResumeSummaryBlock
    | InterviewSummaryBlock
    | KnowledgeCitationsBlock
    | SkillProfileBlock
    | ResumeOptimizationBlock
    | InterviewProposalBlock
    | InterviewSessionBlock,
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