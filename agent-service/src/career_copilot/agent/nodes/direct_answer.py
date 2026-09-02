"""direct_answer：GENERAL_CHAT 直接流式回答，无业务数据与结构化块。"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan
from career_copilot.agent.state import CareerAgentState
from career_copilot.tools import format_history


async def direct_answer(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    history = format_history(
        state.get("history") or [],
        state.get("history_summary"),
        snapshot=state.get("user_snapshot"),
    )
    plan = StreamPlan(
        text=deps.answerer.answer_stream(
            state.get("message") or "", history=history or None
        )
    )
    return {"plan": plan}