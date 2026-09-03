"""结构化响应构建：把意图执行结果组装成前端可渲染的 CopilotResponse 与流式事件。

MVP 首批 Block：text / action / resume_summary / interview_summary / knowledge_citations。
"""

from typing import Any

from career_copilot.schemas.message import (
    ActionBlock,
    CopilotResponse,
    InterviewProposalBlock,
    InterviewSessionBlock,
    InterviewSummaryBlock,
    KnowledgeCitationsBlock,
    ResumeOptimizationBlock,
    ResumeOptimizationPatch,
    ResumeSummaryBlock,
    SkillProfileBlock,
    TextBlock,
)


def text_response(content: str) -> CopilotResponse:
    """纯文本回复。"""
    return CopilotResponse(content=content, blocks=[TextBlock(content=content)])


def action_response(
    content: str,
    route: str,
    label: str,
    params: dict[str, Any] | None = None,
) -> CopilotResponse:
    """文本 + 动作建议块：前端渲染按钮，仅由用户点击后跳转。"""
    return CopilotResponse(
        content=content,
        blocks=[
            TextBlock(content=content),
            ActionBlock(route=route, label=label, params=params or {}),
        ],
    )


def resume_summary_block(resumes: list[dict[str, Any]]) -> ResumeSummaryBlock:
    """简历摘要块：只保留展示所需字段，遵守 Token 纪律。"""
    items = [
        {
            "id": r.get("id"),
            "filename": r.get("filename"),
            "latestScore": r.get("latestScore"),
            "lastAnalyzedAt": r.get("lastAnalyzedAt"),
            "interviewCount": r.get("interviewCount"),
        }
        for r in resumes[:5]
    ]
    return ResumeSummaryBlock(resumes=items)


def interview_summary_block(interviews: list[dict[str, Any]]) -> InterviewSummaryBlock:
    """面试摘要块：只保留最近若干场的展示字段。"""
    items = [
        {
            "sessionId": s.get("sessionId"),
            "skillId": s.get("skillId"),
            "difficulty": s.get("difficulty"),
            "status": s.get("status"),
            "evaluateStatus": s.get("evaluateStatus"),
            "totalQuestions": s.get("totalQuestions"),
            "resumeId": s.get("resumeId"),
        }
        for s in interviews[:5]
    ]
    return InterviewSummaryBlock(interviews=items)


def citations_block(citations: list[dict[str, Any]]) -> KnowledgeCitationsBlock:
    """知识引用块：RAG 回答的来源说明。"""
    return KnowledgeCitationsBlock(citations=citations)


def skill_profile_block(profile: dict[str, Any], skill_limit: int = 6) -> SkillProfileBlock:
    """技能画像块：聚合分 + 每技能证据明细（裁剪到最近 3 条，Token 纪律）。"""
    skills = []
    for skill in (profile.get("skills") or [])[:skill_limit]:
        evidences = [
            {
                "sourceType": e.get("sourceType"),
                "sourceId": e.get("sourceId"),
                "score": e.get("score"),
                "occurredAt": e.get("occurredAt"),
            }
            for e in (skill.get("evidences") or [])[:3]
        ]
        skills.append(
            {
                "skill": skill.get("skill"),
                "score": skill.get("score"),
                "evidenceCount": skill.get("evidenceCount"),
                "evidences": evidences,
            }
        )
    return SkillProfileBlock(skills=skills)


def resume_optimization_block(
    *,
    proposal_id: int,
    resume_id: int,
    version_id: int,
    summary: str,
    patches: list[Any],
    rejected_note: str | None = None,
) -> ResumeOptimizationBlock:
    """简历优化提案块：Patch Diff 卡片（P2-1 HITL 确认入口）。"""
    items = [
        ResumeOptimizationPatch(
            id=patch.id,
            type=patch.type.value,
            path=patch.path,
            oldValue=patch.oldValue,
            newValue=patch.newValue,
            reason=patch.reason,
        )
        for patch in patches
    ]
    return ResumeOptimizationBlock(
        proposalId=proposal_id,
        resumeId=resume_id,
        versionId=version_id,
        summary=summary,
        patches=items,
        rejectedNote=rejected_note,
    )


def interview_proposal_block(
    *,
    direction: str,
    direction_name: str,
    difficulty: str,
    difficulty_name: str,
    focus: list[str],
    question_count: int = 8,
    resume_id: int | None = None,
    summary: str = "",
) -> InterviewProposalBlock:
    """面试提案确认块：Agent 推荐的面试配置 + [按推荐开始] / [调整配置]。

    direction/difficulty 使用 Java 侧枚举（skillId / junior·mid·senior），
    前端据此把「按推荐开始」回传为 CREATE_INTERVIEW action。
    """
    return InterviewProposalBlock(
        direction=direction,
        direction_name=direction_name,
        difficulty=difficulty,
        difficulty_name=difficulty_name,
        mode="TEXT",
        focus=focus,
        question_count=question_count,
        resume_id=resume_id,
        summary=summary,
    )


def interview_session_block(
    *,
    session_id: str,
    skill_id: str | None = None,
    difficulty: str | None = None,
    focus: list[str] | None = None,
    question_count: int | None = None,
    direction_name: str | None = None,
) -> InterviewSessionBlock:
    """内嵌面试会话块（P4-0）：面试创建成功后原地内嵌。

    只带展示字段；前端据此 sessionId 直连 Java API 拉取会话与答题。
    """
    return InterviewSessionBlock(
        session_id=session_id,
        skill_id=skill_id,
        difficulty=difficulty,
        mode="TEXT",
        focus=focus or [],
        question_count=question_count,
        direction_name=direction_name,
    )