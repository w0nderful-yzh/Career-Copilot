"""business_tools：业务数据查询意图，按固定 Intent → Tool 映射执行读操作。

第一版不做无限 Tool Loop：每个意图最多调用固定 Tool 集合，产出摘要块 + 回答。
PROFILE_QUERY / PREPARATION_QUERY 的 Tool（Java 侧）尚未开通，返回友好占位。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import interview_summary_block, resume_summary_block
from career_copilot.agent.router import ActionRoute, Intent
from career_copilot.agent.state import CareerAgentState
from career_copilot.clients.backend import BusinessToolError
from career_copilot.schemas.message import ActionBlock
from career_copilot.tools import (
    format_history,
    summarize_interviews,
    summarize_resume_analysis,
    summarize_resumes,
)


async def business_tools(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    intent = state.get("intent")
    backend = deps.backend

    if intent == Intent.RESUME_QUERY.value:
        return await _plan_resume_query(state, backend, deps)
    if intent == Intent.INTERVIEW_REVIEW.value:
        return await _plan_interview_review(state, backend, deps)

    # PROFILE_QUERY / PREPARATION_QUERY：工具未开通，友好占位（避免空回复）
    return {
        "plan": StreamPlan(
            text=static_text(
                "能力画像与学习计划功能正在建设中，暂时无法查看。"
                "你可以先查看简历或模拟面试记录。"
            )
        )
    }


async def _plan_resume_query(
    state: CareerAgentState, backend: Any, deps: GraphDeps
) -> dict[str, Any]:
    """简历查询。

    附件/会话指定了目标简历（active_resume_id）时，直接读取该简历的分析结果，
    而不是把整库简历塞给 LLM 让它反问你"是哪一份"；否则回退简历列表。
    """
    message = state.get("message") or ""
    history = format_history(
        state.get("history") or [], state.get("history_summary")
    )
    if state.get("active_resume_id") is not None:
        return await _plan_targeted_resume(state, backend, deps)

    resumes = await backend.list_resumes()
    if not resumes:
        return {
            "plan": StreamPlan(
                blocks=[
                    ActionBlock(
                        route=ActionRoute.RESUME_UPLOAD.value, label="上传简历"
                    )
                ],
                text=static_text("你还没有上传简历，上传后我可以帮你分析简历与岗位的匹配情况。"),
            )
        }
    context = await summarize_resumes(resumes)
    return {
        "plan": StreamPlan(
            blocks=[resume_summary_block(resumes)],
            text=deps.answerer.answer_stream(message, context, history or None),
        )
    }


async def _plan_targeted_resume(
    state: CareerAgentState, backend: Any, deps: GraphDeps
) -> dict[str, Any]:
    """目标简历分析：基于 active_resume_id 读取单份简历分析并解读。

    分析由 Java 异步生成，未完成时（RESUME_ANALYSIS_NOT_FOUND）如实告知，
    避免把"正在分析"误判为"没有简历"。
    """
    message = state.get("message") or ""
    history = format_history(
        state.get("history") or [], state.get("history_summary")
    )
    resume_id = state["active_resume_id"]
    try:
        analysis = await backend.get_resume_analysis(resume_id)
    except BusinessToolError:
        return {
            "plan": StreamPlan(
                text=static_text(
                    "这份简历还在后台分析中，分析完成后我会帮你解读。"
                    "你可以先来一场模拟面试，或稍后再问我。"
                )
            )
        }

    filename = _attachment_filename(state) or f"简历 #{resume_id}"
    context = await summarize_resume_analysis(analysis)
    return {
        "plan": StreamPlan(
            blocks=[
                resume_summary_block(
                    [
                        {
                            "id": resume_id,
                            "filename": filename,
                            "latestScore": analysis.get("overallScore"),
                        }
                    ]
                )
            ],
            text=deps.answerer.answer_stream(message, context, history or None),
        )
    }


def _attachment_filename(state: CareerAgentState) -> str | None:
    """从附件中取目标简历的原始文件名（用于展示，无则返回 None）。"""
    for attachment in state.get("attachments") or []:
        if attachment.get("kind") == "resume":
            return attachment.get("filename")
    return None


async def _plan_interview_review(
    state: CareerAgentState, backend: Any, deps: GraphDeps
) -> dict[str, Any]:
    """面试回顾：先产出 interview_summary 块，再基于摘要流式回答。"""
    message = state.get("message") or ""
    history = format_history(
        state.get("history") or [], state.get("history_summary")
    )
    history_txt = history or None
    backend_history = await backend.get_interview_history()
    if not backend_history:
        return {
            "plan": StreamPlan(
                blocks=[
                    ActionBlock(
                        route=ActionRoute.INTERVIEW_CREATE.value, label="开始模拟面试"
                    )
                ],
                text=static_text("你还没有模拟面试记录，可以先来一场模拟面试练练手。"),
            )
        }
    context = await summarize_interviews(backend_history)
    return {
        "plan": StreamPlan(
            blocks=[interview_summary_block(backend_history)],
            text=deps.answerer.answer_stream(message, context, history_txt),
        )
    }