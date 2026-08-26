"""基于上下文生成用户可读的自然语言回复。"""

from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

ANSWER_SYSTEM_PROMPT = """你是 Career Copilot，一名面向求职者的智能职业助手。
根据用户消息和给定的参考信息（如有），用简洁、专业的中文回答。
不要编造参考信息中不存在的事实。如果参考信息不足以回答，如实说明并给出下一步建议。"""


class Answerer:
    """回答生成器：GENERAL_CHAT 与带业务上下文的意图共用。

    模型通过构造器注入，测试时可替换为 fake 模型。
    """

    def __init__(self, model: Any) -> None:
        self._model = model

    def _build_messages(self, message: str, context: str | None = None) -> list[BaseMessage]:
        messages: list[BaseMessage] = [SystemMessage(content=ANSWER_SYSTEM_PROMPT)]
        if context:
            messages.append(HumanMessage(content=f"参考信息：\n{context}"))
        messages.append(HumanMessage(content=message))
        return messages

    async def answer(self, message: str, context: str | None = None) -> str:
        response = await self._model.ainvoke(self._build_messages(message, context))
        return str(response.content)

    async def answer_stream(self, message: str, context: str | None = None) -> AsyncIterator[str]:
        """流式回答：逐 chunk 产出文本增量，供 SSE message_delta 事件转发。"""
        async for chunk in self._model.astream(self._build_messages(message, context)):
            content = getattr(chunk, "content", None)
            if isinstance(content, str) and content:
                yield content