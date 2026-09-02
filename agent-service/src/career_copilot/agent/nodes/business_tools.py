"""business_tools：业务数据查询意图，按固定 Intent → Tool 映射执行读操作。

第一版不做无限 Tool Loop：每个意图最多调用固定 Tool 集合，产出摘要块 + 回答。
PREPARATION_QUERY 的 Tool（Java 侧）尚未开通，返回友好占位。
"""

import asyncio
from typing import Any, cast

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import (
    emit_run_status,
    emit_tool_completed,
    emit_tool_progress,
    emit_tool_started,
)
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import interview_summary_block, resume_summary_block
from career_copilot.agent.router import ActionRoute, Intent
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.config import settings
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ActionBlock, ChoiceBlock, ChoiceOption
from career_copilot.tools import (
    format_history,
    format_resume_content,
    summarize_interviews,
    summarize_resume_analysis,
)


async def business_tools(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    intent = state.get("intent")
    backend = deps.backend

    if intent == Intent.RESUME_QUERY.value:
        return await _plan_resume_query(state, backend, deps)
    if intent == Intent.INTERVIEW_REVIEW.value:
        return await _plan_interview_review(state, backend, deps)

    # PREPARATION_QUERY：Preparation Tool 未开通，用首轮注入的用户快照
    # （top 技能 + 最近面试）做背景感知回答；无快照时如实占位
    snapshot = state.get("user_snapshot")
    if snapshot:
        message = state.get("message") or ""
        history = format_history(state.get("history") or [], state.get("history_summary"))
        return {
            "plan": StreamPlan(
                text=deps.answerer.answer_stream(message, snapshot, history or None)
            )
        }
    return {
        "plan": StreamPlan(
            text=static_text(
                "学习计划功能正在建设中，暂时无法查看。"
                "你可以先查看简历或模拟面试记录。"
            )
        )
    }


async def _plan_resume_query(
    state: CareerAgentState, backend: Any, deps: GraphDeps
) -> dict[str, Any]:
    """简历查询（含目标简历解析，对齐设计文档 §26 优先级）。

    目标解析优先级：
    1. 附件 / Action 指定的 active_resume_id
    2. 消息中提到的简历文件名
    3. 库中唯一一份简历 → 自动锁定
    4. 多份且无法判断 → 默认最近上传的一份并在回答中说明

    命中目标后走内容感知路径（注入完整简历文本）；完全无法收敛时回退整库概览。
    """
    message = state.get("message") or ""
    if state.get("active_resume_id") is not None:
        return await _plan_targeted_resume(state, backend, deps)

    emit_tool_started("resume_query")
    resumes = await backend.list_resumes()
    emit_tool_completed("resume_query")
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

    matched = _match_resume_by_filename(message, resumes)
    target = matched
    auto_selected = len(resumes) > 1 and matched is None
    if target is None:
        if len(resumes) == 1:
            # 唯一简历自动锁定（对齐设计文档 §26）
            target = resumes[0]
            auto_selected = False
        else:
            # 多份且未指明：默认最近上传的一份，并在回答中说明便于用户纠正
            target = _latest_resume(resumes)

    targeted_state = cast(
        CareerAgentState, {**state, "active_resume_id": int(target["id"])}
    )
    return await _plan_targeted_resume(
        targeted_state,
        backend,
        deps,
        display_filename=target.get("filename"),
        context_note=(
            f"（用户有多份简历且本次未指定：你正在分析最近上传的《{target.get('filename')}》，"
            "如需分析其他简历请让用户指出文件名。）"
            if auto_selected
            else None
        ),
    )


def _match_resume_by_filename(
    message: str, resumes: list[dict[str, Any]]
) -> dict[str, Any] | None:
    """从消息中匹配用户提到的简历文件名（忽略扩展名与大小写）。"""
    lowered = message.lower()
    for resume in resumes:
        filename = (resume.get("filename") or "").lower()
        stem = filename.rsplit(".", 1)[0]
        if filename and (filename in lowered or (stem and stem in lowered)):
            return resume
    return None


def _latest_resume(resumes: list[dict[str, Any]]) -> dict[str, Any]:
    """取最近上传的一份（uploadedAt 缺失时退化为最大 id）。"""
    return max(
        resumes,
        key=lambda r: (str(r.get("uploadedAt") or ""), r.get("id") or 0),
    )


async def _plan_targeted_resume(
    state: CareerAgentState,
    backend: Any,
    deps: GraphDeps,
    display_filename: str | None = None,
    context_note: str | None = None,
) -> dict[str, Any]:
    """目标简历分析：基于 active_resume_id 读取单份简历分析并解读。

    分析由 Java 异步生成，未完成时（RESUME_ANALYSIS_NOT_FOUND）如实告知，
    避免把"正在分析"误判为"没有简历"。
    内容感知：额外读取完整简历文本（按 resume_context_max_chars 截断）注入上下文，
    让 Agent 能引用项目/技能等具体内容（也为简历优化子图铺好取数路径）。

    display_filename / context_note 供无附件场景的目标解析使用
    （唯一简历自动锁定或多份时默认最近一份，需要向用户说明所选目标）。
    """
    message = state.get("message") or ""
    history = format_history(
        state.get("history") or [],
        state.get("history_summary"),
        snapshot=state.get("user_snapshot"),
    )
    # 上游 _plan_resume_query 保证 active_resume_id 非空才进入本路径
    raw_resume_id = state["active_resume_id"]
    assert raw_resume_id is not None
    resume_id = int(raw_resume_id)

    # 新上传的简历分析异步完成（真实简历常需十余秒），在就绪窗口内轮询等待，
    # 让用户本轮直接拿到分析结果；等待期通过 tool_progress 实时反馈进度。
    analysis: dict[str, Any] | None = None
    attempts = settings.analysis_wait_attempts
    delay = settings.analysis_wait_delay_seconds
    emit_tool_started("resume_insight")
    for attempt in range(attempts):
        try:
            analysis = await backend.get_resume_analysis(resume_id)
            break
        except BusinessToolError:
            # 分析尚未就绪。先探测状态：FAILED 直接诚实报错，避免白等整个窗口
            status = await _resume_analyze_status(backend, resume_id)
            if status == "FAILED":
                break
            if attempt >= attempts - 1:
                break
            emit_tool_progress(
                "resume_insight",
                f"正在等待简历分析完成…（{attempt + 1}/{attempts - 1}）",
            )
            await asyncio.sleep(delay)
    emit_tool_completed("resume_insight")

    if analysis is None:
        status = await _resume_analyze_status(backend, resume_id)
        if status == "FAILED":
            hint = (
                "这份简历的上次自动分析失败了。你可以在简历库中对该简历点击"
                "「重新分析」，完成后再来问我，我会帮你解读。"
            )
            return {"plan": StreamPlan(text=static_text(hint))}

        # 有界等待后仍未就绪：如实说明 + 提供确定性重试入口（不请求内干等）
        emit_run_status(RunStatus.WAITING_USER.value)
        return {
            "plan": StreamPlan(
                blocks=[
                    ChoiceBlock(
                        title="简历仍在分析中",
                        options=[
                            ChoiceOption(
                                action=AgentAction.ANALYZE_RESUME.value,
                                label="稍后获取分析结果",
                                payload={"resumeId": resume_id},
                            ),
                        ],
                    ),
                ],
                text=static_text(
                    "这份简历的分析还在后台进行中（通常需要十几秒，本次等待 "
                    f"{attempts} 次仍未完成）。你可以点击「稍后获取分析结果」"
                    "稍后重试，或先进行其他操作。"
                ),
            )
        }

    context = await summarize_resume_analysis(analysis)
    # 内容感知：读取完整简历文本（失败不阻断，回落纯摘要）
    try:
        resume = await backend.get_resume(
            resume_id, max_chars=settings.resume_context_max_chars
        )
        context = f"{format_resume_content(resume)}\n\n{context}"
    except BusinessToolError:
        pass

    if context_note:
        context = f"{context}\n{context_note}"

    filename = (
        display_filename
        or _attachment_filename(state)
        or f"简历 #{resume_id}"
    )

    # Conversation Memory：定向分析后绑定活动简历，
    # 下一轮无附件提问也能锁定同一目标（失败仅告警不阻断）
    conversation_id = state.get("conversation_id")
    if conversation_id is not None:
        try:
            await backend.bind_active_resume(int(conversation_id), resume_id)
        except BusinessToolError:
            pass

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


async def _resume_analyze_status(backend: Any, resume_id: int) -> str:
    """探测简历分析状态（PENDING / PROCESSING / COMPLETED / FAILED，未知返回空串）。

    通过 get_resume 的元信息（maxChars 很小）获取，避免拉全量文本。
    """
    try:
        meta = await backend.get_resume(resume_id, max_chars=1)
        return (meta.get("analyzeStatus") or "").upper()
    except BusinessToolError:
        # 简历读取不到（如已被删除）：返回空串，由上层按未就绪兜底
        return ""


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
        state.get("history") or [],
        state.get("history_summary"),
        snapshot=state.get("user_snapshot"),
    )
    history_txt = history or None
    emit_tool_started("interview_review")
    backend_history = await backend.get_interview_history()
    emit_tool_completed("interview_review")
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