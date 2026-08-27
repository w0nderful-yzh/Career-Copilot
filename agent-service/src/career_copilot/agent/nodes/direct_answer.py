"""direct_answer：GENERAL_CHAT 直接流式回答，无业务数据与结构化块。"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan
from career_copilot.agent.state import CareerAgentState


async def direct_answer(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    plan = StreamPlan(text=deps.answerer.answer_stream(state.get("message") or ""))
    return {"plan": plan}