"""结构化响应构建：把意图执行结果组装成前端可渲染的 CopilotResponse 与流式事件。

MVP 首批 Block：text / action / resume_summary / interview_summary / knowledge_citations。
"""

from typing import Any

from career_copilot.schemas.message import (
    ActionBlock,
    CopilotResponse,
    InterviewSummaryBlock,
    KnowledgeCitationsBlock,
    ResumeSummaryBlock,
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