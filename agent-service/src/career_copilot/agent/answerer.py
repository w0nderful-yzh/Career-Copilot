"""基于上下文生成用户可读的回复。

模型调用统一走 :class:`LlmExecutor`（预算、错误分类、观测都在那一层），
提示词集中在 ``career_copilot/prompts/`` 并带版本。
"""

from collections.abc import AsyncIterator

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

from career_copilot.agent.llm import LlmExecutor
from career_copilot.prompts import load


class Answerer:
    """回答生成器：GENERAL_CHAT 与带业务上下文的意图共用。"""

    def __init__(self, llm: LlmExecutor) -> None:
        self._llm = llm

    def _build_messages(
        self, message: str, context: str | None = None, history: str | None = None
    ) -> list[BaseMessage]:
        messages: list[BaseMessage] = [SystemMessage(content=load("answer").text)]
        if history:
            messages.append(HumanMessage(content=f"对话历史：\n{history}"))
        if context:
            messages.append(HumanMessage(content=f"参考信息：\n{context}"))
        messages.append(HumanMessage(content=message))
        return messages

    async def answer(
        self, message: str, context: str | None = None, history: str | None = None
    ) -> str:
        """一次性回答；调用失败按错误分类抛出（由上层决定如何呈现，不静默返回空串）。"""
        result = await self._llm.text(
            load("answer"), self._build_messages(message, context, history)
        )
        return result.unwrap()

    async def answer_stream(
        self,
        message: str,
        context: str | None = None,
        history: str | None = None,
    ) -> AsyncIterator[str]:
        """流式回答：逐 chunk 产出文本增量，供 SSE message_delta 事件转发。"""
        async for chunk in self._llm.stream(
            load("answer"), self._build_messages(message, context, history)
        ):
            yield chunk

    async def summarize_history(self, history_text: str) -> str:
        """对超出窗口的早期对话做滚动摘要（保持简短，供短期记忆写回）。"""
        if not history_text.strip():
            return ""
        prompt = load("history_summary")
        result = await self._llm.text(
            prompt,
            [SystemMessage(content=prompt.text), HumanMessage(content=history_text)],
        )
        return result.unwrap().strip()
