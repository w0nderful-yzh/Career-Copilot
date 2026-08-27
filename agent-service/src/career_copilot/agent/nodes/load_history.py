"""load_history：会话短期记忆加载与滚动摘要。

短期记忆权威来源是 Java（System of Record）：每轮拉取最近 N 条消息注入上下文；
当会话历史超出注入窗口且尚无摘要时，对早期轮次做 LLM 滚动摘要并写回 Java
（checkpoint / 服务重启后仍可恢复）。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_tool_completed, emit_tool_started
from career_copilot.agent.state import CareerAgentState
from career_copilot.config import settings
from career_copilot.tools import format_history


async def load_history(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    conversation_id = state.get("conversation_id")
    if conversation_id is None:
        return {"history": [], "history_summary": None}
    try:
        conversation_id_int = int(conversation_id)
    except (TypeError, ValueError):
        return {"history": [], "history_summary": None}

    try:
        emit_tool_started("load_history")
        context = await deps.backend.get_conversation_context(
            conversation_id_int, limit=settings.summary_trigger_messages
        )
        emit_tool_completed("load_history")
    except Exception:
        # 历史拉取失败不阻断对话：回退空历史（Java 不可达时 Graph 仍可用）
        return {"history": [], "history_summary": None}

    messages = context.get("messages") or []
    summary = context.get("summary")
    total_count = int(context.get("totalCount") or 0)

    # 注入窗口内的最近消息（单条截断，Token 纪律）
    max_chars = settings.history_max_message_chars
    recent = messages[-settings.history_max_messages :]
    history = [
        {"role": item.get("role"), "content": (item.get("content") or "")[:max_chars]}
        for item in recent
    ]

    # 历史超出窗口且尚无摘要：对早期轮次做滚动摘要并写回 Java
    if total_count > settings.history_max_messages and not summary:
        early = messages[: total_count - settings.history_max_messages]
        try:
            summary = await deps.answerer.summarize_history(
                format_history(early, summary)
            )
            if summary:
                await deps.backend.update_conversation_summary(
                    conversation_id_int, summary
                )
        except Exception:
            # 摘要失败不阻断对话：本轮仅注入最近消息
            summary = None

    return {"history": history, "history_summary": summary}