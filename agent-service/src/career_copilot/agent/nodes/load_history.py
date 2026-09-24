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
    # 会话绑定的活动简历/活动 JD（Conversation Memory，无附件轮次恢复目标用）
    bound_resume_id = context.get("activeResumeId")
    bound_job_id = context.get("activeJobId")

    # 注入窗口内的最近消息（单条截断，Token 纪律）
    max_chars = settings.history_max_message_chars
    recent = messages[-settings.history_max_messages :]
    if state.get("regenerate"):
        recent = _drop_regenerated_user_turn(recent, state.get("message") or "")
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

    return {
        "history": history,
        "history_summary": summary,
        "bound_resume_id": int(bound_resume_id) if bound_resume_id is not None else None,
        "bound_job_id": int(bound_job_id) if bound_job_id is not None else None,
    }


def _drop_regenerated_user_turn(
    messages: list[dict[str, Any]], message: str
) -> list[dict[str, Any]]:
    """重新生成轮：去掉历史末尾这次要重发的用户消息，避免同一句话注入两遍。

    前端「重新生成」会先删掉旧的助手回复，Java 历史因此以这次要重发的用户消息结尾；
    不清掉它，模型会在同一轮里既从历史、又从当前输入看到两遍相同的问题。

    只在「末尾确实是同内容的 USER 消息」时才删一条：不满足就原样返回——
    宁可让模型看到一次重复，也不能误删真实历史。
    """
    if not messages or not message.strip():
        return messages
    last = messages[-1]
    if (last.get("role") or "").upper() != "USER":
        return messages
    if (last.get("content") or "").strip() != message.strip():
        return messages
    return messages[:-1]