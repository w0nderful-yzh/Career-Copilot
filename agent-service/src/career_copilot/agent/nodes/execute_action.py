"""execute_action：ACTION 输入 → 确定性动作分发。

动作来自 ChoiceBlock / 前端白名单按钮，按 AgentAction key 分发到确定流程。
V1：需要 Java 写能力或子图的动作返回占位引导，读/导航类动作直接返回。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
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

    return {"plan": _dispatch(action_name, payload)}


def _dispatch(action_name: str | None, payload: dict[str, Any]) -> StreamPlan:
    """动作注册表：action key → 确定流程。"""
    resume_id = payload.get("resumeId")
    params: dict[str, Any] = {"resumeId": resume_id} if resume_id else {}

    handlers: dict[str, StreamPlan] = {
        AgentAction.ANALYZE_RESUME.value: StreamPlan(
            blocks=[
                ActionBlock(
                    route=ActionRoute.RESUME_DETAIL.value,
                    label="查看简历分析",
                    params=params,
                )
            ],
            text=static_text("好的，我来帮你分析这份简历。"),
        ),
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