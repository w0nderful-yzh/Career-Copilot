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
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.router import ActionRoute
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ActionBlock, NavigationBlock


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

    if action_name == AgentAction.START_INTERVIEW.value:
        # 面试发起：重新进入推荐流程（含调整配置后再推荐）
        return await interview_proposal(state, deps)

    if action_name == AgentAction.CREATE_INTERVIEW.value:
        return await _create_interview_action(state, deps, payload)

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
    创建成功后产出 NavigationBlock 跳转现有面试会话页（过渡方案，
    P4-0 的 InterviewSessionBlock 就绪后原地内嵌替换）。
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
                NavigationBlock(
                    route=ActionRoute.INTERVIEW_SESSION.value,
                    label="进入面试",
                    params={"sessionId": session_id},
                )
            ],
            text=static_text(
                f"面试已创建（{session.get('totalQuestions') or '?'} 题）。"
                "点击「进入面试」开始答题，祝你发挥顺利！"
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


def _dispatch(action_name: str | None, payload: dict[str, Any]) -> StreamPlan:
    """动作注册表：action key → 确定流程。"""
    resume_id = payload.get("resumeId")
    params: dict[str, Any] = {"resumeId": resume_id} if resume_id else {}

    handlers: dict[str, StreamPlan] = {
        # ANALYZE_RESUME / START_INTERVIEW / CREATE_INTERVIEW 已在上方异步处理
        AgentAction.OPTIMIZE_RESUME.value: StreamPlan(
            text=static_text("简历优化功能正在建设中，敬请期待。")
        ),
        AgentAction.JOB_MATCH.value: StreamPlan(
            text=static_text("岗位匹配功能正在建设中，敬请期待。")
        ),
    }
    return handlers.get(
        action_name or "", StreamPlan(text=static_text("暂不支持该操作。"))
    )