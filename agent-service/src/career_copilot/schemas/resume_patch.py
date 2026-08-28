"""简历优化 Patch 协议（P2-1）：Agent 产出的结构化修改建议。

path 指向 ResumeContentJson 结构内的位置（如 "projects[0].bullets[1]"），
oldValue 用于应用时一致性校验。REORDER 一期 schema 保留、校验器直接拒绝
（TodoList 已确认决策）。
"""

from enum import StrEnum

from pydantic import BaseModel, Field


class PatchType(StrEnum):
    REPLACE = "REPLACE"
    ADD = "ADD"
    DELETE = "DELETE"
    REORDER = "REORDER"


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


class ResumePatchProposal(BaseModel):
    """一轮优化的完整提案：Agent 结构化输出。"""

    summary: str = Field(description="本轮优化的一句话总结")
    patches: list[ResumePatch] = Field(default_factory=list, description="修改建议列表")
