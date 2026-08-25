"""基于上下文生成用户可读的自然语言回复。"""

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

    async def answer(self, message: str, context: str | None = None) -> str:
        messages: list[BaseMessage] = [SystemMessage(content=ANSWER_SYSTEM_PROMPT)]
        if context:
            messages.append(HumanMessage(content=f"参考信息：\n{context}"))
        messages.append(HumanMessage(content=message))
        response = await self._model.ainvoke(messages)
        return str(response.content)