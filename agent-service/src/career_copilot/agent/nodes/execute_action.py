"""execute_action：ACTION 输入 → 确定性动作分发。

动作来自 ChoiceBlock / 前端白名单按钮，按 AgentAction key 分发到确定流程。
ANALYZE_RESUME 走真实内容感知分析（与定向简历查询同一路径）；
START_INTERVIEW 走面试提案推荐；CREATE_INTERVIEW 按用户确认后的配置创建面试；
需要子图的动作返回占位引导，读/导航类动作直接返回。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_run_status, emit_tool_completed, emit_tool_started
from career_copilot.agent.nodes.business_tools import _plan_targeted_resume
from career_copilot.agent.nodes.interview_proposal import interview_proposal
from career_copilot.agent.nodes.resume_optimization import resume_optimization
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import interview_session_block
from career_copilot.agent.router import ActionRoute
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ActionBlock, ChoiceBlock, ChoiceOption, NavigationBlock
from career_copilot.tools import format_history, summarize_interview_detail


async def execute_action(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    action = state.get("action") or {}
    action_name = action.get("action")
    payload = action.get("payload") or {}

    if action.get("type") != "ACTION_SELECTED":
        # 未知动作类型：回退普通对话，避免静默失败
        return {
            "plan": StreamPlan(
                text=deps.answerer.answer_stream(state.get("message") or "嗯？")
            )
        }

    if action_name == AgentAction.ANALYZE_RESUME.value:
        return await _analyze_resume_action(state, deps, payload)

    if action_name == AgentAction.OPTIMIZE_RESUME.value:
        # 简历优化：走优化子图（生成 Patch 提案，待用户确认后应用）。
        # payload.resumeId / payload.jobId（ChoiceBlock 回传）优先，回退会话活动资源。
        optimize_state: CareerAgentState = {**state}
        payload_resume_id = _as_int(payload.get("resumeId"))
        if payload_resume_id is not None:
            optimize_state["active_resume_id"] = payload_resume_id
        payload_job_id = _as_int(payload.get("jobId"))
        if payload_job_id is not None:
            optimize_state["active_job_id"] = payload_job_id
        return await resume_optimization(optimize_state, deps)

    if action_name == AgentAction.START_INTERVIEW.value:
        # 面试发起：重新进入推荐流程（含调整配置后再推荐）
        return await interview_proposal(state, deps)

    if action_name == AgentAction.CREATE_INTERVIEW.value:
        return await _create_interview_action(state, deps, payload)

    if action_name == AgentAction.APPLY_RESUME_PATCHES.value:
        return await _apply_resume_patches_action(state, deps, payload)

    if action_name == AgentAction.REVIEW_INTERVIEW.value:
        return await _review_interview_action(state, deps, payload)

    return {"plan": _dispatch(action_name, payload)}


async def _analyze_resume_action(
    state: CareerAgentState,
    deps: GraphDeps,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Copilot 内真实简历分析：与定向简历查询共用内容感知路径。

    resumeId 缺失时回退会话绑定 / 附件推导的活动简历。
    """
    resume_id = payload.get("resumeId") or state.get("active_resume_id")
    if resume_id is None:
        return {
            "plan": StreamPlan(
                text=static_text(
                    "请先告诉我要分析的简历，或把简历文件发给我。"
                )
            )
        }

    targeted_state: CareerAgentState = {
        **state,
        "active_resume_id": int(resume_id),
    }
    display_filename = payload.get("filename")
    return await _plan_targeted_resume(
        targeted_state,
        deps.backend,
        deps,
        display_filename=display_filename if isinstance(display_filename, str) else None,
        context_note=None,
    )


async def _create_interview_action(
    state: CareerAgentState,
    deps: GraphDeps,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """按用户确认后的配置创建面试（CONFIRM_WRITE）。

    payload 由前端从 InterviewProposalBlock 原样回传（direction/difficulty/focus/
    questionCount/resumeId），先校验必填，再调用 Java create_interview Tool。
    创建成功后产出 InterviewSessionBlock 原地内嵌（P4-0：不再跳转面试页，
    答题在块内直连 Java Interview API）。
    """
    direction = payload.get("direction")
    difficulty = payload.get("difficulty") or "mid"
    if not isinstance(direction, str) or not direction:
        return {
            "plan": StreamPlan(
                text=static_text("缺少面试方向配置，请重新选择面试方向。")
            )
        }

    emit_tool_started("create_interview")
    try:
        session = await deps.backend.create_interview(
            skill_id=direction,
            difficulty=difficulty,
            question_count=_as_int(payload.get("questionCount")),
            resume_id=_as_int(payload.get("resumeId")) or state.get("active_resume_id"),
            resume_text=None,
            force_create=True,
        )
    except BusinessToolError as exc:
        emit_tool_completed("create_interview")
        return {
            "plan": StreamPlan(
                text=static_text(
                    f"创建面试失败：{exc.message}。请稍后重试，或重新选择配置。"
                )
            )
        }
    emit_tool_completed("create_interview")

    session_id = session.get("sessionId")
    if not isinstance(session_id, str) or not session_id:
        return {
            "plan": StreamPlan(
                text=static_text("面试创建成功，但未能获取会话信息，请前往面试记录查看。")
            )
        }

    emit_run_status(RunStatus.COMPLETED.value)
    return {
        "plan": StreamPlan(
            blocks=[
                interview_session_block(
                    session_id=session_id,
                    skill_id=direction if isinstance(direction, str) else None,
                    difficulty=difficulty if isinstance(difficulty, str) else None,
                    focus=[f for f in (payload.get("focus") or []) if isinstance(f, str)],
                    question_count=_as_int(payload.get("questionCount")),
                    direction_name=None,
                )
            ],
            text=static_text(
                f"面试已创建（{session.get('totalQuestions') or '?'} 题）。"
                "面试已在你面前展开，直接在卡片内回答即可。"
            ),
        )
    }


def _as_int(value: Any) -> int | None:
    """payload 里的数值字段兼容 int / float / 数字字符串。"""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str) and value.strip().isdigit():
        return int(value)
    return None


async def _apply_resume_patches_action(
    state: CareerAgentState,
    deps: GraphDeps,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """应用用户勾选的优化建议（CONFIRM_WRITE）。

    payload 由前端从 ResumeOptimizationBlock 回传（proposalId + patchIds），
    Java 侧逐条 JSON path 应用并生成新版本（原版本不动）；
    成功后 NavigationBlock 跳简历详情页（版本列表可见）。
    """
    proposal_id = _as_int(payload.get("proposalId"))
    if proposal_id is None:
        return {
            "plan": StreamPlan(
                text=static_text("缺少提案信息，无法应用修改。请回到优化建议卡片重新操作。")
            )
        }

    patch_ids = payload.get("patchIds")
    patch_id_list = (
        [str(p) for p in patch_ids if isinstance(p, str)]
        if isinstance(patch_ids, list)
        else None
    )

    emit_tool_started("apply_patches")
    try:
        result = await deps.backend.apply_resume_patches(proposal_id, patch_id_list)
    except BusinessToolError as exc:
        emit_tool_completed("apply_patches")
        # PATCH_CONFLICT（内容漂移）与其他失败都如实告知
        return {
            "plan": StreamPlan(
                text=static_text(
                    f"应用修改失败：{exc.message}。"
                    "如果简历内容已变化，请让我重新生成优化建议。"
                )
            )
        }
    emit_tool_completed("apply_patches")

    version = result.get("version")
    emit_run_status(RunStatus.COMPLETED.value)
    resume_id = result.get("resumeId")
    nav_params = (
        {"resumeId": resume_id}
        if isinstance(resume_id, int)
        else {}
    )
    return {
        "plan": StreamPlan(
            blocks=[
                NavigationBlock(
                    route=ActionRoute.RESUME_DETAIL.value,
                    label="查看简历版本",
                    params=nav_params,
                )
            ],
            text=static_text(
                f"已应用你勾选的修改，生成了简历新版本（V{version}）。"
                "原版本保持不变，你可以随时对比或回退。"
                "需要的话我可以基于新版本再做一轮优化，或者来一场模拟面试检验效果。"
            ),
        )
    }


async def _review_interview_action(
    state: CareerAgentState,
    deps: GraphDeps,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """复盘刚结束的面试（P4-6a 面试结果回流 Copilot）。

    payload 由结果卡携带 sessionId。读取 Java 面试详情（强项/弱项/逐题得分），
    让 LLM 基于客观数据给出复盘（answerer 系统 prompt 禁止编造参考信息）；
    附 [再来一场] / [查看面试记录] 下一步动作。
    """
    session_id = payload.get("sessionId")
    if not isinstance(session_id, str) or not session_id:
        return {
            "plan": StreamPlan(
                text=static_text("缺少面试信息，请回到面试结果卡片重新操作。")
            )
        }

    emit_tool_started("interview_review")
    try:
        detail = await deps.backend.get_interview_detail(session_id)
    except BusinessToolError as exc:
        emit_tool_completed("interview_review")
        return {
            "plan": StreamPlan(
                text=static_text(
                    f"读取面试详情失败：{exc.message}。你可以在「面试记录」页查看完整报告。"
                )
            )
        }
    emit_tool_completed("interview_review")

    context = summarize_interview_detail(detail)
    history = format_history(
        state.get("history") or [],
        state.get("history_summary"),
        snapshot=state.get("user_snapshot"),
    )
    message = (state.get("message") or "").strip() or "请帮我复盘这次面试"

    return {
        "plan": StreamPlan(
            blocks=[
                ActionBlock(
                    route=ActionRoute.INTERVIEW_HISTORY.value,
                    label="查看面试记录",
                    params={},
                ),
                ChoiceBlock(
                    title="下一步",
                    options=[
                        ChoiceOption(
                            action=AgentAction.START_INTERVIEW.value,
                            label="再来一场模拟面试",
                            payload={},
                        ),
                    ],
                ),
            ],
            text=deps.answerer.answer_stream(message, context, history or None),
        )
    }


def _dispatch(action_name: str | None, payload: dict[str, Any]) -> StreamPlan:
    """动作注册表：action key → 确定流程。"""
    resume_id = payload.get("resumeId")
    params: dict[str, Any] = {"resumeId": resume_id} if resume_id else {}

    handlers: dict[str, StreamPlan] = {
        # ANALYZE_RESUME / OPTIMIZE_RESUME / START_INTERVIEW / CREATE_INTERVIEW 已在上方异步处理
        AgentAction.JOB_MATCH.value: StreamPlan(
            text=static_text("岗位匹配功能正在建设中，敬请期待。")
        ),
    }
    return handlers.get(
        action_name or "", StreamPlan(text=static_text("暂不支持该操作。"))
    )