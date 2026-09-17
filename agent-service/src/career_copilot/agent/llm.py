"""LLM 执行器（ARCH-2）：Python 侧模型调用的唯一入口。

此前 6 处调用各写各的——两种取模型姿势（``deps.answerer._model`` 私有属性 / ``self._model``）、
裸 ``json.loads``、宽泛 ``except Exception`` 静默回落。于是验收要答的三问答不上来：
**失败发生在哪一层、实际调了几次模型、花了多久、怎么降级的**。

本模块把这些收成一处：

- **拿模型**：只有这里拿（不再到处摸私有属性）；
- **预算**：实时（面试逐轮这类要人等）与后台（报告、优化提案）分别传超时；
- **结构化输出**：JSON 抽取 + pydantic 校验 + **有限修复重试**（默认 1 次，且只针对解析失败，
  网络/超时类错误重试只会把用户的等待翻倍）；
- **错误分类**：TIMEOUT / RATE_LIMITED / PARSE_FAILED / UPSTREAM——调用方按类别决定降级方式，
  而不是一律吞掉；
- **观测**：每次调用记录 ``promptId@版本``、尝试次数、耗时、结果状态，可直接回答
  「这次失败在哪一层、调了几次」。

**不做的事**：不重试流式调用（已吐出的增量无法回滚）；不在业务层再叠一层重试
（重试责任只在这一层，避免形成不受控的重试链）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from collections import deque
from collections.abc import AsyncIterator
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from langchain_core.messages import BaseMessage
from pydantic import BaseModel, ValidationError

from career_copilot.prompts import Prompt

logger = logging.getLogger(__name__)

__all__ = [
    "LlmErrorKind",
    "LlmExecutor",
    "LlmResult",
    "metrics_snapshot",
    "recent_calls",
]



class LlmErrorKind(StrEnum):
    """调用失败的类型：调用方据此决定降级方式（而不是一律当作「没有结果」）。"""

    TIMEOUT = "TIMEOUT"  # 超出本次预算
    RATE_LIMITED = "RATE_LIMITED"  # 上游限流
    PARSE_FAILED = "PARSE_FAILED"  # 拿到了响应但不符合结构化契约
    UPSTREAM = "UPSTREAM"  # 其余上游错误


@dataclass(frozen=True)
class LlmResult[T]:
    """一次调用的结果：要么有值，要么有明确的失败原因。

    刻意不用 ``T | None``：``None`` 分不清「模型说没有建议」和「调用失败」，
    而这正是 ARCH-2 要求区分的一件事。
    """

    value: T | None
    error: LlmErrorKind | None
    attempts: int
    latency_ms: int

    @property
    def ok(self) -> bool:
        return self.error is None

    def unwrap(self) -> T:
        """取结果；失败时抛 ``LlmFailure``（调用方若只想拿值，用 ``if result.ok`` 分支）。"""
        if self.value is None or self.error is not None:
            raise LlmFailure(self.error or LlmErrorKind.UPSTREAM, self.attempts)
        return self.value


class LlmFailure(RuntimeError):
    """``LlmResult.unwrap()`` 在失败时抛出。"""

    def __init__(self, kind: LlmErrorKind, attempts: int) -> None:
        super().__init__(f"LLM 调用失败: {kind.value}（尝试 {attempts} 次）")
        self.kind = kind
        self.attempts = attempts


@dataclass(frozen=True)
class LlmCallRecord:
    """一次调用的观测记录（进程内保留最近若干条，供排查「调了几次、花了多久」）。"""

    prompt: str
    status: str
    attempts: int
    latency_ms: int


_MAX_RECORDS = 200
_CALLS: deque[LlmCallRecord] = deque(maxlen=_MAX_RECORDS)

_FENCED_JSON = re.compile(r"```(?:json)?\s*(.*?)```", re.S)
_REPAIR_SUFFIX = (
    "\n\n上一次输出无法解析为要求的 JSON。请只输出一个 JSON 对象，"
    "不要 markdown 代码块、不要解释文字。"
)


def recent_calls() -> list[LlmCallRecord]:
    """最近的调用记录（新→旧）。"""
    return list(reversed(_CALLS))


def metrics_snapshot() -> dict[str, Any]:
    """按 prompt 汇总的调用统计：次数、失败数、平均耗时——回答「实际调了几次、花了多久」。"""
    summary: dict[str, dict[str, Any]] = {}
    for record in _CALLS:
        bucket = summary.setdefault(
            record.prompt, {"calls": 0, "failures": 0, "avgLatencyMs": 0, "_totalMs": 0}
        )
        bucket["calls"] += 1
        bucket["_totalMs"] += record.latency_ms
        if not record.status.startswith("success"):
            bucket["failures"] += 1
    for bucket in summary.values():
        bucket["avgLatencyMs"] = round(bucket.pop("_totalMs") / max(bucket["calls"], 1))
    return summary


def classify_error(exc: BaseException) -> LlmErrorKind:
    """错误分类：把「超时 / 限流 / 其他上游错误」分开，别都叫「模型不可用」。"""
    if isinstance(exc, TimeoutError):
        return LlmErrorKind.TIMEOUT
    text = f"{type(exc).__name__}: {exc}".lower()
    if "timeout" in text or "timed out" in text:
        return LlmErrorKind.TIMEOUT
    if "rate limit" in text or "429" in text or "too many requests" in text:
        return LlmErrorKind.RATE_LIMITED
    return LlmErrorKind.UPSTREAM


def extract_json_object(raw: str) -> str:
    """从模型输出里取出 JSON 本体：容忍 ```json 围栏与前后废话。

    DeepSeek 等 OpenAI 兼容服务在 json_mode 下偶尔仍会包一层代码块。
    """
    fenced = _FENCED_JSON.search(raw)
    candidate = fenced.group(1) if fenced else raw
    start, end = candidate.find("{"), candidate.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("输出里没有可解析的 JSON 对象")
    return candidate[start : end + 1]


class LlmExecutor:
    """统一执行模型调用：预算、结构化契约、有限修复、观测都在这里。"""

    def __init__(self, model: Any, *, default_timeout: float, parse_retries: int = 1) -> None:
        self._model = model
        self._default_timeout = default_timeout
        self._parse_retries = max(0, parse_retries)

    @property
    def model(self) -> Any:
        """底层模型：**仅供测试替身断言**，业务代码不要用它绕过治理。"""
        return self._model

    async def text(
        self,
        prompt: Prompt,
        messages: list[BaseMessage],
        *,
        timeout: float | None = None,  # noqa: ASYNC109 — 预算覆盖，不是等待语义
    ) -> LlmResult[str]:
        """纯文本调用（无结构化契约）。"""
        started = time.perf_counter()
        try:
            content = await self._invoke(messages, timeout)
        except Exception as exc:  # noqa: BLE001 — 统一分类后再上抛为结果
            kind = classify_error(exc)
            self._record(prompt.ref, f"failure:{kind.value}", 1, started)
            logger.warning("LLM 文本调用失败: prompt=%s kind=%s error=%s", prompt.ref, kind, exc)
            return LlmResult(value=None, error=kind, attempts=1, latency_ms=_elapsed(started))
        self._record(prompt.ref, "success", 1, started)
        return LlmResult(value=content, error=None, attempts=1, latency_ms=_elapsed(started))

    async def json[U: BaseModel](
        self,
        prompt: Prompt,
        messages: list[BaseMessage],
        schema: type[U],
        *,
        timeout: float | None = None,  # noqa: ASYNC109 — 预算覆盖，不是等待语义
    ) -> LlmResult[U]:
        """结构化调用：抽取 JSON → pydantic 校验；解析失败做有限修复重试。"""
        started = time.perf_counter()
        attempt = 0
        last_error: Exception | None = None

        while attempt <= self._parse_retries:
            attempt += 1
            outgoing = messages if attempt == 1 else _append_repair_hint(messages)
            try:
                raw = await self._invoke_json(outgoing, timeout)
            except Exception as exc:  # noqa: BLE001 — 统一分类后上抛为结果
                kind = classify_error(exc)
                self._record(prompt.ref, f"failure:{kind.value}", attempt, started)
                logger.warning(
                    "LLM 结构化调用失败: prompt=%s kind=%s attempts=%d error=%s",
                    prompt.ref,
                    kind,
                    attempt,
                    exc,
                )
                return LlmResult(value=None, error=kind, attempts=attempt,
                                 latency_ms=_elapsed(started))

            try:
                parsed = _parse_and_validate(raw, schema)
            except (ValueError, ValidationError) as exc:
                last_error = exc
                logger.warning(
                    "结构化输出不符合契约，准备%s: prompt=%s attempt=%d/%d error=%s",
                    "重试" if attempt <= self._parse_retries else "放弃",
                    prompt.ref,
                    attempt,
                    self._parse_retries + 1,
                    exc,
                )
                continue

            self._record(prompt.ref, "success", attempt, started)
            return LlmResult(value=parsed, error=None, attempts=attempt,
                             latency_ms=_elapsed(started))

        self._record(prompt.ref, f"failure:{LlmErrorKind.PARSE_FAILED.value}", attempt, started)
        logger.error(
            "结构化输出始终不符合契约: prompt=%s attempts=%d error=%s",
            prompt.ref,
            attempt,
            last_error,
        )
        return LlmResult(value=None, error=LlmErrorKind.PARSE_FAILED, attempts=attempt,
                         latency_ms=_elapsed(started))

    async def stream(
        self,
        prompt: Prompt,
        messages: list[BaseMessage],
        *,
        timeout: float | None = None,  # noqa: ASYNC109 — 预算覆盖，不是等待语义
    ) -> AsyncIterator[str]:
        """流式调用。

        **不做重试**：已经吐给用户的增量无法回滚，重试会让同一段话出现两次。
        失败时按分类抛出，由调用方决定如何收尾。
        """
        started = time.perf_counter()
        try:
            async with asyncio.timeout(timeout or self._default_timeout):
                async for chunk in self._model.astream(messages):
                    content = getattr(chunk, "content", None)
                    if isinstance(content, str) and content:
                        yield content
        except Exception as exc:  # noqa: BLE001 — 分类后原样上抛，由调用方收尾
            kind = classify_error(exc)
            self._record(prompt.ref, f"failure:{kind.value}", 1, started)
            logger.warning("LLM 流式调用中断: prompt=%s kind=%s error=%s", prompt.ref, kind, exc)
            raise
        self._record(prompt.ref, "success", 1, started)

    async def _invoke(
        self,
        messages: list[BaseMessage],
        timeout: float | None,  # noqa: ASYNC109 — 预算透传，不是等待语义
    ) -> str:
        """单次模型调用（含预算）；签名固定，便于测试替身。"""
        async with asyncio.timeout(timeout or self._default_timeout):
            response = await self._model.ainvoke(messages)
        return str(getattr(response, "content", "") or "")

    async def _invoke_json(
        self,
        messages: list[BaseMessage],
        timeout: float | None,  # noqa: ASYNC109 — 预算透传，不是等待语义
    ) -> str:
        """结构化调用的单次执行。

        优先请求上游的 ``response_format=json_object``（DeepSeek 等 OpenAI 兼容服务支持），
        拿不到这个能力的模型退化为「靠提示词约束 + 本层解析」——两条路都在这一层收口，
        业务节点不必各自操心「用不用 json_mode」。
        """
        runnable = self._model
        try:
            runnable = self._model.bind(response_format={"type": "json_object"})
        except (AttributeError, TypeError):
            logger.debug("模型不支持 response_format=json_object，退回提示词约束")
        async with asyncio.timeout(timeout or self._default_timeout):
            response = await runnable.ainvoke(messages)
        return str(getattr(response, "content", "") or "")

    @staticmethod
    def _record(prompt_ref: str, status: str, attempts: int, started: float) -> None:
        latency = _elapsed(started)
        _CALLS.append(
            LlmCallRecord(prompt=prompt_ref, status=status, attempts=attempts,
                          latency_ms=latency)
        )
        logger.info(
            "LLM 调用: prompt=%s status=%s attempts=%d latencyMs=%d",
            prompt_ref,
            status,
            attempts,
            latency,
        )


def _elapsed(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _append_repair_hint(messages: list[BaseMessage]) -> list[BaseMessage]:
    """修复型重试：在最后一条用户消息上追加「只输出 JSON」的定向提示。"""
    if not messages:
        return messages
    repaired = list(messages)
    last = repaired[-1]
    content = last.content if isinstance(last.content, str) else str(last.content)
    repaired[-1] = last.model_copy(update={"content": content + _REPAIR_SUFFIX})
    return repaired


def _parse_and_validate[U: BaseModel](raw: str, schema: type[U]) -> U:
    """抽取 + 校验；模型若能直接产出对象（如 langchain 的结构化输出）也接受。"""
    if isinstance(raw, schema):  # pragma: no cover — 正常路径下 raw 是 str
        return raw
    payload = json.loads(extract_json_object(raw))
    if not isinstance(payload, dict):
        raise ValueError("结构化输出不是 JSON 对象")
    return schema.model_validate(payload)
