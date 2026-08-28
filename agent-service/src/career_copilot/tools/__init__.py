"""Agent Tool 注册表与薄封装。

Tool 只负责：参数整理、调用 BackendClient、结果裁剪（Token 纪律）。
业务逻辑一律在 Java 侧，Python 不做业务复制。
"""

from dataclasses import dataclass, field
from typing import Any

from career_copilot.clients.backend import BackendClient


@dataclass(frozen=True)
class ToolSpec:
    """Tool 元信息，供后续 Agent 决策与 Discovery 使用。"""

    name: str
    description: str
    parameters: dict[str, str] = field(default_factory=dict)


TOOLS: list[ToolSpec] = [
    ToolSpec(
        name="get_resume_list",
        description="获取简历列表（含最新分析分数）",
        parameters={"resume_id": "int (可选)"},
    ),
    ToolSpec(
        name="get_interview_history",
        description="获取模拟面试历史列表",
        parameters={"resume_id": "int (可选)"},
    ),
    ToolSpec(
        name="get_skill_profile",
        description="获取用户技能画像（聚合分 + 可追溯证据）",
        parameters={},
    ),
    ToolSpec(
        name="search_knowledge",
        description="基于 RAG 知识库回答问题",
        parameters={"question": "str", "knowledge_base_ids": "list[int] (可选)"},
    ),
    ToolSpec(
        name="list_skills",
        description="获取可用的模拟面试技能方向列表（含分类与优先级）",
        parameters={},
    ),
    ToolSpec(
        name="create_interview",
        description="创建模拟面试会话（需用户确认后执行）",
        parameters={
            "skillId": "str",
            "difficulty": "str",
            "questionCount": "int (可选)",
            "resumeId": "int (可选)",
        },
    ),
]


async def summarize_resumes(resumes: list[dict[str, Any]], limit: int = 5) -> str:
    """把简历列表裁剪为适合放入 Prompt 的摘要（只保留决策所需字段）。"""
    if not resumes:
        return "（用户还没有上传简历）"
    rows = [
        f"- id={r.get('id')} {r.get('filename')} 最新评分={r.get('latestScore')}"
        for r in resumes[:limit]
    ]
    return "用户简历：\n" + "\n".join(rows)


async def summarize_resume_analysis(analysis: dict[str, Any]) -> str:
    """把单份简历分析结果裁剪为 Prompt 摘要（排除 originalText，遵守 Token 纪律）。"""
    if not analysis:
        return "（暂无简历分析结果）"
    score = analysis.get("scoreDetail") or {}
    lines = [
        f"- 总分: {analysis.get('overallScore')}/100",
        f"- 内容{score.get('contentScore')} 结构{score.get('structureScore')} "
        f"技能匹配{score.get('skillMatchScore')} 表达{score.get('expressionScore')} "
        f"项目{score.get('projectScore')}",
    ]
    if analysis.get("summary"):
        lines.append(f"- 摘要: {analysis['summary']}")
    for strength in analysis.get("strengths") or []:
        lines.append(f"- 优点: {strength}")
    for suggestion in (analysis.get("suggestions") or [])[:5]:
        lines.append(
            f"- 建议({suggestion.get('priority', '')}): {suggestion.get('recommendation')}"
        )
    return "该简历分析结果：\n" + "\n".join(lines)


def format_resume_content(resume: dict[str, Any]) -> str:
    """把 get_resume 返回的完整简历文本组装为 Prompt 片段（内容级分析/优化用）。"""
    filename = resume.get("filename") or f"简历 #{resume.get('id')}"
    text = resume.get("resumeText") or ""
    return f"[简历内容：{filename}]\n{text}"


async def summarize_interviews(history: list[dict[str, Any]], limit: int = 5) -> str:
    """把面试历史裁剪为摘要，避免完整列表塞入上下文。"""
    if not history:
        return "（用户还没有参加过模拟面试）"
    rows = [
        f"- session={s.get('sessionId')} skill={s.get('skillId')} "
        f"状态={s.get('status')} 评估={s.get('evaluateStatus')}"
        for s in history[:limit]
    ]
    return "最近模拟面试：\n" + "\n".join(rows)


def summarize_skills(skills: list[dict[str, Any]], limit: int = 8) -> str:
    """把技能方向列表裁剪为适合放入 Prompt 的摘要（id + 展示名 + 分类）。"""
    if not skills:
        return "（没有可用的面试方向）"
    rows = []
    for skill in skills[:limit]:
        name = skill.get("name") or skill.get("id")
        categories = [
            f"{cat.get('key')}({cat.get('priority')})"
            for cat in (skill.get("categories") or [])
            if cat.get("key")
        ][:6]
        rows.append(
            f"- {skill.get('id')} {name}"
            + (f" 分类: {', '.join(categories)}" if categories else "")
        )
    return "可选面试方向：\n" + "\n".join(rows)


def summarize_skill_profile(profile: dict[str, Any], limit: int = 8) -> str:
    """把技能画像裁剪为 Prompt 摘要：聚合分 + 证据来源（支撑可追溯解读）。

    证据只保留最近 3 条（sessionId:题号 + 分数），避免完整明细塞入上下文。
    """
    skills = profile.get("skills") or []
    if not skills:
        return "（暂无技能画像数据）"
    lines = ["用户技能画像（分数=面试证据均值，可追溯）："]
    for skill in skills[:limit]:
        evidences = [
            f"{e.get('sourceId')}={e.get('score')}分"
            for e in (skill.get("evidences") or [])[:3]
        ]
        evidence_txt = f"（证据: {', '.join(evidences)}）" if evidences else ""
        lines.append(
            f"- {skill.get('skill')}: {skill.get('score')}分 "
            f"[{skill.get('evidenceCount')} 条证据]{evidence_txt}"
        )
    return "\n".join(lines)


def summarize_resume_for_interview(resume: dict[str, Any], max_chars: int = 1200) -> str:
    """把 get_resume 返回的完整简历文本裁剪为面试推荐所需摘要（Token 纪律）。"""
    filename = resume.get("filename") or f"简历 #{resume.get('id')}"
    text = (resume.get("resumeText") or "").strip()
    if len(text) > max_chars:
        text = text[:max_chars] + "\n…（已截断）"
    return f"[简历：{filename}]\n{text}" if text else f"[简历：{filename}]（无解析文本）"


async def resolve_knowledge_base_ids(client: BackendClient) -> list[int]:
    """解析默认检索的知识库：未显式指定时使用全部已存在知识库。"""
    knowledge_bases = await client.list_knowledge_bases()
    ids: list[int] = []
    for kb in knowledge_bases:
        kb_id = kb.get("id")
        if kb_id is not None:
            ids.append(int(kb_id))
    return ids


def format_history(
    history: list[dict[str, str]],
    summary: str | None = None,
    snapshot: str | None = None,
) -> str:
    """把会话历史裁剪为适合放入 Prompt 的文本（快照 + 摘要 + 轮次，正序）。

    snapshot 为新会话首轮注入的用户背景快照（P3-4），置于最前作为背景感知。
    """
    if not history and not summary and not snapshot:
        return ""
    lines: list[str] = []
    if snapshot:
        lines.append(snapshot)
    if summary:
        lines.append(f"[早期对话摘要] {summary}")
    for item in history:
        role = "用户" if item.get("role") == "USER" else "助手"
        content = item.get("content") or ""
        lines.append(f"{role}: {content}")
    return "\n".join(lines)