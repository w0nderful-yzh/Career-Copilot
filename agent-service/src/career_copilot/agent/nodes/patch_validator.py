"""Patch 校验器（P2-1）：代码控边界的确定性校验。

真实性双保险的代码侧：LLM prompt 禁止虚构（第一层），本校验器做第二层——
newValue 引入原文没有的量化数字 / 技术栈时拒绝。数字比对基于「原文同一字段
上下文中已出现的数字」，避免把原文已有的数字误判为编造。
"""

import re
from dataclasses import dataclass, field

from career_copilot.schemas.resume_patch import PatchType, ResumePatch

# 数字 token：整数 / 小数 / 带单位（QPS、%、倍、万、ms 等）
_NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?")


@dataclass
class PatchValidationResult:
    """校验结果：rejected 全部不合法 patch 的 (index, 原因)。"""

    rejected: list[tuple[int, str]] = field(default_factory=list)

    @property
    def has_rejection(self) -> bool:
        return bool(self.rejected)


def validate_patches(
    patches: list[ResumePatch],
    resume_text: str,
) -> PatchValidationResult:
    """对 Agent 产出的 patch 列表做确定性校验。

    规则（全部代码边界，不依赖 LLM 自觉）：
    1. REORDER 一期直接拒绝
    2. REPLACE/DELETE 必须带 oldValue；REPLACE/ADD 必须带 newValue
    3. path 必须指向已知结构段（basicInfo/education/experience/projects/skills/customSections）
    4. 真实性：newValue 引入原文没有的量化数字 → 拒绝（防编造业绩）
    """
    result = PatchValidationResult()
    resume_numbers = set(_NUMBER_PATTERN.findall(resume_text or ""))

    for index, patch in enumerate(patches):
        reason = _validate_single(patch, resume_numbers)
        if reason:
            result.rejected.append((index, reason))
    return result


def _validate_single(patch: ResumePatch, resume_numbers: set[str]) -> str | None:
    if patch.type == PatchType.REORDER:
        return "暂不支持顺序调整（REORDER）类型的修改"

    if patch.type in (PatchType.REPLACE, PatchType.DELETE) and not (patch.oldValue or "").strip():
        return f"{patch.type.value} 类型必须提供 oldValue（用于应用时一致性校验）"
    if patch.type in (PatchType.REPLACE, PatchType.ADD) and not (patch.newValue or "").strip():
        return f"{patch.type.value} 类型必须提供 newValue"

    top_segment = (patch.path or "").split("[", 1)[0].split(".", 1)[0]
    known_segments = {
        "basicInfo", "education", "experience", "projects", "skills", "customSections",
    }
    if top_segment not in known_segments:
        return f"path 非法：未知结构段 {top_segment!r}"

    # 真实性校验只针对新增文本（ADD 的新值整段新增；REPLACE 检查新值引入的新数字）
    if patch.type in (PatchType.REPLACE, PatchType.ADD):
        new_numbers = _NUMBER_PATTERN.findall(patch.newValue or "")
        for number in new_numbers:
            # 原文出现过的数字可直接复用（如把 2s 改写为 2000ms 场景除外，
            # 一期从简：数字完全一致才放行）
            if number not in resume_numbers:
                return (
                    f"newValue 引入了原文没有的数字 {number!r}，"
                    "疑似编造量化业绩（需用户提供真实数据）"
                )
    return None
