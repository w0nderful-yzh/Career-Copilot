"""简历优化 Patch 协议（P2-1）：Agent 产出的结构化修改建议。

path 指向 ResumeContentJson 结构内的位置（如 "projects[0].bullets[1]"），
oldValue 用于应用时一致性校验。REORDER 一期 schema 保留、校验器直接拒绝
（TodoList 已确认决策）。
"""

from enum import StrEnum

from pydantic import BaseModel, Field, field_validator, model_validator


class PatchType(StrEnum):
    REPLACE = "REPLACE"
    ADD = "ADD"
    DELETE = "DELETE"
    REORDER = "REORDER"


class JdGapStatus(StrEnum):
    MATCHED = "MATCHED"
    PARTIAL = "PARTIAL"
    MISSING = "MISSING"
    UNKNOWN = "UNKNOWN"


class JdMatchLevel(StrEnum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    UNKNOWN = "UNKNOWN"


class JdGapItem(BaseModel):
    """单条 JD 要求与简历证据的对照。"""

    requirement: str = Field(description="JD 要求的精炼表达")
    status: JdGapStatus = Field(description="已匹配 / 部分匹配 / 缺失 / 无法确认")
    resumeEvidence: list[str] = Field(
        default_factory=list, description="简历中支撑结论的原文证据"
    )
    impact: str = Field(description="该要求对投递匹配的影响")
    verificationRequired: list[str] = Field(
        default_factory=list, description="必须由用户确认、不得由 AI 猜测的事实"
    )

    @field_validator("resumeEvidence", "verificationRequired", mode="before")
    @classmethod
    def normalize_explanation_list(cls, value: object) -> object:
        """真实模型偶尔把单条说明返回为字符串，边界处归一为列表。"""
        if isinstance(value, str):
            stripped = value.strip()
            return [stripped] if stripped else []
        return value

    @model_validator(mode="after")
    def validate_evidence_boundary(self) -> "JdGapItem":
        """已匹配结论必须有简历证据；无法确认时必须告诉用户核实什么。"""
        if self.status in (JdGapStatus.MATCHED, JdGapStatus.PARTIAL):
            if not self.resumeEvidence:
                raise ValueError("匹配或部分匹配必须提供简历原文证据")
        if self.status in (JdGapStatus.MISSING, JdGapStatus.UNKNOWN):
            if not self.verificationRequired:
                raise ValueError("缺失或无法确认的 JD 要求必须列出待核实事实")
        return self


class JdGapAnalysis(BaseModel):
    """JD 差距分析：与 Patch 分开表达，先说明差距再建议改写。"""

    jobTitle: str = Field(description="目标岗位名称")
    matchLevel: JdMatchLevel = Field(description="整体匹配等级")
    summary: str = Field(description="差距分析摘要")
    items: list[JdGapItem] = Field(
        min_length=1, description="JD 要求逐条对照，至少包含一项有效要求"
    )


class ResumePatch(BaseModel):
    """单条简历修改建议：JSON-path 定位 + 前后值 + 修改理由。"""

    id: str = Field(description="patch 唯一标识（如 patch_1）")
    type: PatchType = Field(description="修改类型")
    path: str = Field(
        description='JSON path，指向简历结构化数据中的位置（如 "projects[0].bullets[1]"）'
    )
    oldValue: str | None = Field(
        default=None, description="原值（REPLACE/DELETE 必填，应用时做一致性校验）"
    )
    newValue: str | None = Field(
        default=None, description="新值（REPLACE/ADD 必填；DELETE 为空）"
    )
    reason: str = Field(description="修改理由（向用户解释为什么这样改）")
    evidence: list[str] = Field(
        default_factory=list, description="支撑该建议的简历 / JD 原文依据"
    )
    impact: str | None = Field(default=None, description="修改会影响的简历区域与预期效果")
    verificationRequired: list[str] = Field(
        default_factory=list, description="需用户核实的事实；仅重组原文时为空"
    )

    @field_validator("evidence", "verificationRequired", mode="before")
    @classmethod
    def normalize_explanation_list(cls, value: object) -> object:
        """兼容模型输出单条字符串，落库与前端协议始终保持数组。"""
        if isinstance(value, str):
            stripped = value.strip()
            return [stripped] if stripped else []
        return value


class ResumePatchProposal(BaseModel):
    """一轮优化的完整提案：Agent 结构化输出。"""

    summary: str = Field(description="本轮优化的一句话总结")
    jdGapAnalysis: JdGapAnalysis | None = Field(
        default=None, description="JD 定向时的独立差距分析；其他模式为空"
    )
    patches: list[ResumePatch] = Field(default_factory=list, description="修改建议列表")
