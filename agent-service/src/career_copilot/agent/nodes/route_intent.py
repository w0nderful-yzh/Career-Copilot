"""route_intent：意图路由。确定性优先，只有开放文本输入才调用 LLM。

原则：LLM 负责语义判断，代码负责确定性边界（action / 仅附件不走 LLM）。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.router import Intent
from career_copilot.agent.state import CareerAgentState, InputType
from career_copilot.tools import format_history

# 确定性路由：action / 仅附件不经过 LLM
ACTION_INTENT = "ACTION"
ATTACHMENT_INTENT = Intent.ATTACHMENT_RECEIVED.value


async def route_intent(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    input_type = state.get("input_type")

    # 1. 按钮点击回传的确定性动作：不进行 LLM 分类
    if input_type == InputType.ACTION.value:
        return {"intent": ACTION_INTENT}

    # 2. 仅附件无文本：确定性识别附件流程
    if input_type == InputType.ATTACHMENT.value:
        return {"intent": ATTACHMENT_INTENT}

    # 3. 开放文本（含带文本的附件）：交给意图路由器（附会话历史辅助指代解析）
    history = format_history(
        state.get("history") or [], state.get("history_summary")
    )
    classification = await deps.intent_router.classify(
        state.get("message") or "", history=history or None
    )
    return {
        "intent": classification.intent.value,
        "action_route": getattr(classification, "action_route", None),
    }