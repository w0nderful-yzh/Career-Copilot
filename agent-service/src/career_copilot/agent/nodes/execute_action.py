"""execute_action：ACTION 输入 → 确定性动作分发。

动作来自 ChoiceBlock / 前端白名单按钮，按 AgentAction key 分发到确定流程。
ANALYZE_RESUME 走真实内容感知分析（与定向简历查询同一路径）；
需要子图的动作返回占位引导，读/导航类动作直接返回。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.nodes.business_tools import _plan_targeted_resume
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.router import ActionRoute
from career_copilot.agent.state import CareerAgentState
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ActionBlock


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


def _dispatch(action_name: str | None, payload: dict[str, Any]) -> StreamPlan:
    """动作注册表：action key → 确定流程。"""
    resume_id = payload.get("resumeId")
    params: dict[str, Any] = {"resumeId": resume_id} if resume_id else {}

    handlers: dict[str, StreamPlan] = {
        # ANALYZE_RESUME 已在上方异步处理（真实内容感知分析）
        AgentAction.OPTIMIZE_RESUME.value: StreamPlan(
            text=static_text("简历优化功能正在建设中，敬请期待。")
        ),
        AgentAction.START_INTERVIEW.value: StreamPlan(
            blocks=[
                ActionBlock(
                    route=ActionRoute.INTERVIEW_CREATE.value,
                    label="开始模拟面试",
                    params=params,
                )
            ],
            text=static_text("好的，准备开始一场模拟面试。"),
        ),
        AgentAction.JOB_MATCH.value: StreamPlan(
            text=static_text("岗位匹配功能正在建设中，敬请期待。")
        ),
    }
    return handlers.get(
        action_name or "", StreamPlan(text=static_text("暂不支持该操作。"))
    )