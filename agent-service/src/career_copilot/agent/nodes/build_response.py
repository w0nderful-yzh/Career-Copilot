"""build_response：Graph 状态收尾。

Plan 式执行模型下，文本流式由 API 层在 Graph 结束后消费 plan.text，
本节点只负责快照 blocks 与标记运行状态，不消费文本迭代器（避免破坏流式）。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.state import CareerAgentState, RunStatus


async def build_response(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    plan = state.get("plan")
    blocks = [block.model_dump() for block in plan.blocks] if plan else []
    return {
        "response": {"content": "", "blocks": blocks},
        "status": RunStatus.COMPLETED.value,
    }