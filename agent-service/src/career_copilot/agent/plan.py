"""执行计划：分支节点产出的结构化块 + 可流式文本源。

Plan 式执行模型：Graph 节点运行到产出计划即返回，不消费 LLM 文本流，
SSE 流式由 API 层在 Graph 结束后消费 plan.text（保持低延迟与可测试性）。
"""

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any


@dataclass
class StreamPlan:
    """意图分支的执行计划：先产出的结构化块 + 可选的流式文本。

    blocks 在文本流之前一次性产出，text 为 None 表示无文本。
    text 是惰性迭代器，Graph 内不消费，由 API 层转发为 message_delta 事件。
    """

    blocks: list[Any] = field(default_factory=list)
    text: AsyncIterator[str] | None = None


async def static_text(content: str) -> AsyncIterator[str]:
    """静态文本一次性产出（确定性分支无需 LLM 时使用）。"""
    yield content