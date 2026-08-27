"""normalize_input：统一前端输入格式，确定性推导 input_type（不调用 LLM）。"""

from typing import Any

from career_copilot.agent.state import CareerAgentState, InputType


def normalize_input(state: CareerAgentState) -> dict[str, Any]:
    """推导 input_type 并规范化 message。

    规则：
    - action 存在          → ACTION
    - 文本 + 附件          → TEXT_WITH_ATTACHMENT
    - 仅附件（无文本）     → ATTACHMENT
    - 其余                 → TEXT
    """
    message = (state.get("message") or "").strip()
    attachments = state.get("attachments") or []
    action = state.get("action")

    if action:
        input_type = InputType.ACTION
    elif message and attachments:
        input_type = InputType.TEXT_WITH_ATTACHMENT
    elif attachments:
        input_type = InputType.ATTACHMENT
    else:
        input_type = InputType.TEXT

    return {"input_type": input_type.value, "message": message}