"""LLM 执行器（ARCH-2）：预算、结构化契约、有限修复、错误分类与观测。

这里守的是验收要答的三问：**失败发生在哪一层、实际调了几次模型、花了多久、怎么降级**。
"""

import asyncio
import json
from typing import Any

import pytest
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel

from career_copilot.agent.llm import (
    LlmErrorKind,
    LlmExecutor,
    LlmFailure,
    metrics_snapshot,
    recent_calls,
)
from career_copilot.prompts import load
from tests.conftest import FakeChatResult


class _ScriptedModel:
    """按脚本依次返回内容的模型；脚本项可以是异常（模拟上游失败）。"""

    def __init__(self, *responses: Any) -> None:
        self._responses = list(responses)
        self.calls = 0

    def _next(self) -> str:
        self.calls += 1
        item = self._responses[min(self.calls - 1, len(self._responses) - 1)]
        if isinstance(item, Exception):
            raise item
        return str(item)

    async def ainvoke(self, messages: list) -> FakeChatResult:
        return FakeChatResult(self._next())

    async def astream(self, messages: list):
        yield FakeChatResult(self._next())


class _SlowModel:
    """每次调用都慢于预算：用于验证超时被识别为 TIMEOUT 而不是「模型不可用」。"""

    async def ainvoke(self, messages: list) -> FakeChatResult:
        await asyncio.sleep(0.2)
        return FakeChatResult("late")


class _SlowScriptedModel(_ScriptedModel):
    """先慢后快：第一次调用吃掉大部分预算，用来验证解析重试共享同一个截止时间。"""

    def __init__(self, delay: float, *responses: Any) -> None:
        super().__init__(*responses)
        self._delay = delay

    async def ainvoke(self, messages: list) -> FakeChatResult:
        await asyncio.sleep(self._delay)
        return FakeChatResult(self._next())


class _RecordingModel(_ScriptedModel):
    """记录收到的消息与调用期参数（bind），用于验证输入截断与输出封顶。"""

    def __init__(self, *responses: Any) -> None:
        super().__init__(*responses)
        self.messages: list = []
        self.binds: list[dict[str, Any]] = []

    def bind(self, **kwargs: Any) -> "_RecordingModel":
        self.binds.append(kwargs)
        return self

    async def ainvoke(self, messages: list) -> FakeChatResult:
        self.messages = messages
        return await super().ainvoke(messages)


class _Draft(BaseModel):
    direction: str
    focus: list[str] = []


def _executor(
    model: Any,
    *,
    timeout: float = 1.0,
    retries: int = 1,
    min_attempt_ms: int = 1500,
    max_input_chars: int | None = None,
    max_output_tokens: int | None = None,
) -> LlmExecutor:
    return LlmExecutor(
        model,
        default_timeout=timeout,
        parse_retries=retries,
        min_attempt_ms=min_attempt_ms,
        max_input_chars=max_input_chars,
        max_output_tokens=max_output_tokens,
    )


async def test_text_success_is_recorded_with_prompt_version():
    """成功调用记录 prompt=id@版本、尝试次数与耗时。"""
    model = _ScriptedModel("回答")
    before = len(recent_calls())

    result = await _executor(model).text(load("answer"), [])

    assert result.ok and result.value == "回答"
    assert result.attempts == 1
    records = recent_calls()
    assert len(records) == before + 1
    assert records[0].prompt.startswith("answer@v")
    assert records[0].status == "success"
    assert metrics_snapshot()["answer@v1"]["failures"] == 0


async def test_timeout_is_classified_as_timeout():
    """超预算 → TIMEOUT，而不是笼统的「模型不可用」。"""
    result = await _executor(_SlowModel(), timeout=0.01).text(load("answer"), [])

    assert not result.ok
    assert result.error is LlmErrorKind.TIMEOUT


async def test_rate_limit_is_classified_separately():
    """限流与其它上游错误分开：降级策略不同（限流可稍后重试，参数错重试无意义）。"""
    model = _ScriptedModel(RuntimeError("429 Too Many Requests"))

    result = await _executor(model).text(load("answer"), [])

    assert result.error is LlmErrorKind.RATE_LIMITED


async def test_upstream_error_is_classified():
    result = await _executor(_ScriptedModel(RuntimeError("connection reset"))).text(
        load("answer"), []
    )

    assert result.error is LlmErrorKind.UPSTREAM


async def test_json_accepts_fenced_output():
    """模型偶尔仍包一层 ```json 围栏：执行器负责剥掉，业务节点不必各自处理。"""
    payload = json.dumps({"direction": "java-backend", "focus": ["JAVA"]})
    model = _ScriptedModel(f"```json\n{payload}\n```")

    result = await _executor(model).json(load("interview_proposal"), [], _Draft)

    assert result.ok
    assert result.value is not None and result.value.direction == "java-backend"
    assert result.attempts == 1


async def test_json_repairs_once_then_succeeds():
    """首次输出不合契约 → 有限修复重试一次即成功，且尝试次数被记录。"""
    good = json.dumps({"direction": "java-backend"})
    model = _ScriptedModel("这不是 JSON", good)

    result = await _executor(model, retries=1).json(load("interview_proposal"), [], _Draft)

    assert result.ok
    assert result.attempts == 2
    assert model.calls == 2


async def test_json_gives_up_after_parse_retries():
    """始终不合契约 → PARSE_FAILED（不是「模型说没有建议」）。"""
    model = _ScriptedModel("一直不是 JSON")

    result = await _executor(model, retries=1).json(load("interview_proposal"), [], _Draft)

    assert not result.ok
    assert result.error is LlmErrorKind.PARSE_FAILED
    assert result.attempts == 2
    assert metrics_snapshot()["interview_proposal@v1"]["failures"] >= 1


async def test_no_retry_on_upstream_failure():
    """上游错误不重试：重试超时只会把用户的等待翻倍。"""
    model = _ScriptedModel(RuntimeError("boom"))

    result = await _executor(model, retries=1).json(load("interview_proposal"), [], _Draft)

    assert result.error is LlmErrorKind.UPSTREAM
    assert model.calls == 1


async def test_failure_is_distinguishable_from_empty_value():
    """「调用失败」与「模型真的没给内容」必须能分开——靠 ok/error 判断，不靠 None 猜。"""
    failed = await _executor(_ScriptedModel(RuntimeError("boom"))).text(load("answer"), [])
    empty = await _executor(_ScriptedModel("")).text(load("answer"), [])

    assert failed.error is not None and failed.value is None
    assert empty.ok and empty.value == ""


async def test_unwrap_raises_typed_failure():
    result = await _executor(_ScriptedModel(RuntimeError("boom"))).text(load("answer"), [])

    with pytest.raises(LlmFailure) as exc_info:
        result.unwrap()
    assert exc_info.value.kind is LlmErrorKind.UPSTREAM


async def test_stream_does_not_retry():
    """流式不做重试：已吐出的增量无法回滚，重试会让同一段话出现两次。"""
    model = _ScriptedModel("流式")
    collected = [chunk async for chunk in _executor(model).stream(load("answer"), [])]

    assert "".join(collected) == "流式"
    assert model.calls == 1


# ===== ARCH-2b：预算共享、重试责任、长度上限 =====


async def test_parse_retry_shares_one_deadline():
    """第一次尝试吃掉大部分预算后不再发起第二次：总等待不因重试成倍增长。

    这是 ARCH-2b 的核心验收：重试共享一整次操作的截止时间，而不是每次重置完整额度。
    """
    model = _SlowScriptedModel(0.6, "不是 JSON", '{"direction": "java"}')

    result = await _executor(model, timeout=1.0, retries=1).json(
        load("interview_proposal"), [], _Draft
    )

    assert model.calls == 1
    assert result.requests == 1
    assert result.error is LlmErrorKind.TIMEOUT
    assert result.budget_exhausted, "失败原因要说清是「预算用尽」而不是「模型一直不合契约」"
    assert result.latency_ms <= result.budget_ms


async def test_parse_retry_still_happens_when_budget_allows():
    """预算充足时解析重试照常发生，且仍然总时长受预算约束。"""
    model = _SlowScriptedModel(0.05, "不是 JSON", '{"direction": "java-backend"}')

    result = await _executor(model, timeout=2.0, retries=1).json(
        load("interview_proposal"), [], _Draft
    )

    assert result.ok and result.attempts == 2
    assert model.calls == 2
    assert result.requests == result.attempts, "没有隐藏的 SDK 重试：请求数应等于逻辑尝试数"


async def test_sdk_hidden_retry_is_disabled_in_production_model():
    """重试责任只有一层：生产模型客户端的 SDK 重试必须为 0。

    这条断言防的是「把重试打开后排查现场对不上账」——逻辑 1 次尝试背后打了 3 个请求。
    """
    import inspect

    from career_copilot.api import chat

    source = inspect.getsource(chat._openai_model)
    assert "max_retries=0" in source
    assert "timeout=settings.llm_timeout_seconds" in source


async def test_input_overflow_is_truncated_with_readable_marker():
    """输入超长时截断最后一条消息并留标记；系统提示与前面的上下文保持完整。"""
    model = _RecordingModel("ok")
    long_text = "甲" * 200

    await _executor(model, max_input_chars=80).text(
        load("answer"),
        [
            SystemMessage(content="系统指令"),
            HumanMessage(content=long_text),
        ],
    )

    assert model.messages[0].content == "系统指令"
    assert len(model.messages[-1].content) <= 80
    assert "已截断" in model.messages[-1].content


async def test_structured_output_is_token_capped_but_text_is_not():
    """结构化输出封顶（挡「写小作文」），自由文本不截断用户可见的答案。"""
    structured = _RecordingModel('{"direction": "java"}')
    await _executor(structured, max_output_tokens=512).json(
        load("interview_proposal"), [], _Draft
    )
    assert structured.binds == [{"max_tokens": 512, "response_format": {"type": "json_object"}}]

    plain = _RecordingModel("一段很长的回答")
    await _executor(plain, max_output_tokens=512).text(load("answer"), [])
    assert plain.binds == []
