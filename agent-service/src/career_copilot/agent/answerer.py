"""基于上下文生成用户可读的自然语言回复。"""

from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

ANSWER_SYSTEM_PROMPT = """你是 Career Copilot，一名面向求职者的智能职业助手。
根据用户消息和给定的参考信息（如有），用简洁、专业的中文回答。
不要编造参考信息中不存在的事实。如果参考信息不足以回答，如实说明并给出下一步建议。"""

HISTORY_SUMMARY_SYSTEM_PROMPT = """你是 Career Copilot 的对话记忆整理器。
把用户与助手的早期对话压缩为一段简洁的中文摘要（150 字以内），保留：
用户的目标/身份信息、已讨论的关键主题、未完成的承诺或待办、已上传的资源（如简历）。
如果输入已包含"早期对话摘要"，把它与新内容合并成一份更新的摘要。
不要编造对话中不存在的信息。"""


class Answerer:
    """回答生成器：GENERAL_CHAT 与带业务上下文的意图共用。

    模型通过构造器注入，测试时可替换为 fake 模型。
    """

    def __init__(self, model: Any) -> None:
        self._model = model

    def _build_messages(
        self, message: str, context: str | None = None, history: str | None = None
    ) -> list[BaseMessage]:
        messages: list[BaseMessage] = [SystemMessage(content=ANSWER_SYSTEM_PROMPT)]
        if history:
            messages.append(HumanMessage(content=f"对话历史：\n{history}"))
        if context:
            messages.append(HumanMessage(content=f"参考信息：\n{context}"))
        messages.append(HumanMessage(content=message))
        return messages

    async def answer(
        self, message: str, context: str | None = None, history: str | None = None
    ) -> str:
        response = await self._model.ainvoke(self._build_messages(message, context, history))
        return str(response.content)

    async def answer_stream(
        self,
        message: str,
        context: str | None = None,
        history: str | None = None,
    ) -> AsyncIterator[str]:
        """流式回答：逐 chunk 产出文本增量，供 SSE message_delta 事件转发。

        history 为会话短期记忆（最近轮次 + 滚动摘要），context 为业务证据。
        """
        async for chunk in self._model.astream(self._build_messages(message, context, history)):
            content = getattr(chunk, "content", None)
            if isinstance(content, str) and content:
                yield content

    async def summarize_history(self, history_text: str) -> str:
        """对超出窗口的早期对话做滚动摘要（保持简短，供短期记忆写回）。"""
        if not history_text.strip():
            return ""
        response = await self._model.ainvoke(
            [
                SystemMessage(content=HISTORY_SUMMARY_SYSTEM_PROMPT),
                HumanMessage(content=history_text),
            ]
        )
        return str(response.content).strip()