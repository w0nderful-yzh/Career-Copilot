"""Agent Tool 结果的摘要与裁剪（Token 纪律）。

业务逻辑一律在 Java 侧，Python 不做业务复制；本模块只负责把 Tool 返回的结构化结果
裁成适合放进 Prompt 的文本。

Tool 的**参数契约**不在本模块：它由 Java 的类型化请求模型导出到
`career_copilot/contracts/agent-tools.json`，由 `career_copilot.contracts` 在调用前校验。
（此前这里有一份手写的 ToolSpec 列表，无任何消费者却已经开始与 Java 侧漂移，已删除。）
"""

from typing import Any

from career_copilot.clients.backend import BackendClient


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


def summarize_interview_detail(detail: dict[str, Any], max_answers: int = 8) -> str:
    """把单场面试详情（Java /details）裁剪为复盘上下文：强项/弱项 + 逐题评分。

    只摘客观字段（overallScore/strengths/improvements + 每题 score/feedback），
    不复述题目全文，避免长文塞入 Prompt。
    """
    if not detail:
        return "（面试详情不可用）"
    lines = [f"面试 {detail.get('sessionId')} 复盘数据："]
    score = detail.get("overallScore")
    if score is not None:
        lines.append(f"- 综合得分: {score}")
    strengths = detail.get("strengths") or []
    if strengths:
        lines.append(f"- 强项: {'；'.join(str(s) for s in strengths[:4])}")
    improvements = detail.get("improvements") or []
    if improvements:
        lines.append(f"- 待提升: {'；'.join(str(s) for s in improvements[:4])}")
    overall = detail.get("overallFeedback")
    if overall:
        lines.append(f"- 总评: {str(overall)[:200]}")
    answers = detail.get("answers") or []
    if answers:
        lines.append("逐题表现：")
        for a in answers[:max_answers]:
            feedback = str(a.get("feedback") or "").strip()
            lines.append(
                f"  - [{a.get('category')}] 得分{a.get('score')}"
                + (f" 反馈: {feedback[:80]}" if feedback else "")
            )
    return "\n".join(lines)


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
    """把技能画像裁剪为 Prompt 摘要：聚合分 + 证据来源/时间（支撑可追溯解读）。

    证据只保留最近 3 条（场次:题号 + 分数 + 时间），避免完整明细塞入上下文。

    简历声明（declaredSkills）单独成行：它们没有分数，但代表「简历列过、还没考过」，
    是下一场面试最该重点考察的部分，不能被当成空数据处理掉。
    """
    skills = profile.get("skills") or []
    declared = profile.get("declaredSkills") or []
    if not skills and not declared:
        return "（暂无技能画像数据）"

    lines: list[str] = []
    if skills:
        lines.append("用户技能画像（分数=面试证据均值，可追溯）：")
        for skill in skills[:limit]:
            evidences = [
                f"{_source_label(e)}={e.get('score')}分"
                + (f" @{str(e.get('occurredAt'))[:10]}" if e.get("occurredAt") else "")
                for e in (skill.get("evidences") or [])[:3]
            ]
            evidence_txt = f"（证据: {', '.join(evidences)}）" if evidences else ""
            lines.append(
                f"- {skill.get('skill')}: {skill.get('score')}分 "
                f"[{skill.get('evidenceCount')} 条证据]{evidence_txt}"
            )
    if declared:
        names = [str(item.get("skill")) for item in declared[:limit] if item.get("skill")]
        if names:
            lines.append(
                "简历已列、尚无评分证据（从未考过，建议优先考察）：" + "、".join(names)
            )
    return "\n".join(lines)


def _source_label(evidence: dict[str, Any]) -> str:
    """证据来源的可读标签：面试轮次 "sessionId:题号" → "面试 sessionId 第N题"。

    用 sourceType 而不是猜 sourceId 格式——RESUME 的 sourceId 就是 resumeId，
    与裸 sessionId 同为数字/字符串时无法区分。
    """
    source_type = str(evidence.get("sourceType") or "")
    source_id = str(evidence.get("sourceId") or "")
    if source_type == "INTERVIEW_TURN":
        prefix, separator, index = source_id.rpartition(":")
        if separator and prefix and index.isdigit():
            return f"面试 {prefix} 第{int(index) + 1}题"
        return f"面试 {source_id}"
    if source_type == "INTERVIEW_SESSION":
        return f"面试 {source_id}"
    if source_type == "RESUME":
        return f"简历 {source_id}"
    return source_id or "未知来源"


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