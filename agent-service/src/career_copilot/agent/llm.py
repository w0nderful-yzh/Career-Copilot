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
- **观测**：每次调用记录 ``promptId@版本``、尝试次数、**实际模型请求数**、耗时、结果状态，可直接回答
  「这次失败在哪一层、调了几次」。

**ARCH-2b 的三条硬约束**：

1. **重试责任只在这一层**。底层 SDK 的重试被显式关掉（``chat.py`` 构造客户端时 ``max_retries=0``），
   否则「逻辑尝试 1 次」背后可能已经打了 3 个 HTTP 请求，排查时看到的等待时间对不上账。
   ``requests`` 字段就是把这件事变成可观测的事实。
2. **一次操作共享一个截止时间**。解析重试不重置完整额度：每次尝试只能用**剩余预算**，
   剩余低于 ``min_attempt_ms`` 时不再发起新请求（避免「最后一次尝试刚好把等待翻倍」）。
3. **长度有上限**。输入超过 ``max_input_chars`` 时按可读标记截断并告警；
   结构化输出的 ``max_tokens`` 封顶。自由文本与流式的长度由各自提示词和上下文上限控制——
   不在这一层截断用户可见的答案。

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

    ``attempts`` 是**逻辑尝试**（含解析失败后的修复重试），``requests`` 是**实际发出的模型请求**。
    SDK 层重试被关掉后两者相等，所以这个字段的价值是把「没有隐藏重试」变成可断言的事实；
    一旦哪天有人把 SDK 重试打开，这里会先露馅。

    ``budget_exhausted`` 区分两种「失败」：预算内把该试的都试完了（False），
    还是剩余预算已不足以再发起一次有意义请求（True）——后者说明「不是模型不合契约，是没时间了」。
    """

    value: T | None
    error: LlmErrorKind | None
    attempts: int
    requests: int
    latency_ms: int
    budget_ms: int
    budget_exhausted: bool = False

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
    """一次调用的观测记录（进程内保留最近若干条，供排查「调了几次、花了多久」）。

    ``budget_ms`` 与 ``latency_ms`` 放在一起才能解释超时：先看是不是把预算用满了，
    再看是不是某一层在重试。
    """

    prompt: str
    status: str
    attempts: int
    requests: int
    latency_ms: int
    budget_ms: int


_MAX_RECORDS = 200
_CALLS: deque[LlmCallRecord] = deque(maxlen=_MAX_RECORDS)

_FENCED_JSON = re.compile(r"```(?:json)?\s*(.*?)```", re.S)
_REPAIR_SUFFIX = (
    "\n\n上一次输出无法解析为要求的 JSON。请只输出一个 JSON 对象，"
    "不要 markdown 代码块、不要解释文字。"
)

# 截断标记的长度预留（实际标记约 25 字，留足余量以免截断后仍超上限）
_OVERFLOW_MARKER_RESERVE = 40


def recent_calls() -> list[LlmCallRecord]:
    """最近的调用记录（新→旧）。"""
    return list(reversed(_CALLS))


def metrics_snapshot() -> dict[str, Any]:
    """按 prompt 汇总的调用统计。

    回答验收要问的三件事：实际调了几次、平均等多久、降级比例多少。
    ``degradedRate`` = 失败调用 / 总调用，是「有没有靠大量降级换取表面低延迟」的判据。
    """
    summary: dict[str, dict[str, Any]] = {}
    for record in _CALLS:
        bucket = summary.setdefault(
            record.prompt,
            {
                "calls": 0,
                "failures": 0,
                "avgLatencyMs": 0,
                "avgRequests": 0.0,
                "degradedRate": 0.0,
                "_totalMs": 0,
                "_totalRequests": 0,
            },
        )
        bucket["calls"] += 1
        bucket["_totalMs"] += record.latency_ms
        bucket["_totalRequests"] += record.requests
        if not record.status.startswith("success"):
            bucket["failures"] += 1
    for bucket in summary.values():
        calls = max(bucket["calls"], 1)
        bucket["avgLatencyMs"] = round(bucket.pop("_totalMs") / calls)
        bucket["avgRequests"] = round(bucket.pop("_totalRequests") / calls, 2)
        bucket["degradedRate"] = round(bucket["failures"] / calls, 3)
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
    """统一执行模型调用：预算、结构化契约、有限修复、长度上限、观测都在这里。

    :param default_timeout: 默认预算（秒）。实时档与后台档由调用方传不同值。
    :param parse_retries: 解析失败后的**额外**尝试次数（只对解析失败生效）。
    :param min_attempt_ms: 剩余预算低于此值就不再发起新尝试——否则最后一次尝试会把
        用户的等待拖到「预算 + 单次调用耗时」，预算就白设了。这个下限不会超过预算的一半，
        否则小预算（例如 1 秒）会被下限直接吃掉、连一次重试都不允许。
    :param max_input_chars: 输入总长上限（字符）。超长时截断最后一条消息并留下可读标记。
    :param max_output_tokens: 结构化输出的 token 上限；自由文本与流式不设，见模块说明。
    """

    def __init__(
        self,
        model: Any,
        *,
        default_timeout: float,
        parse_retries: int = 1,
        min_attempt_ms: int = 1500,
        max_input_chars: int | None = None,
        max_output_tokens: int | None = None,
    ) -> None:
        self._model = model
        self._default_timeout = default_timeout
        self._parse_retries = max(0, parse_retries)
        self._min_attempt_s = max(0.0, min_attempt_ms / 1000)
        self._max_input_chars = max_input_chars
        self._max_output_tokens = max_output_tokens

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
        """纯文本调用（无结构化契约）：单次尝试，不做重试。"""
        started = time.perf_counter()
        budget_ms = self._budget_ms(timeout)
        deadline = time.perf_counter() + budget_ms / 1000
        prepared = self._prepare_input(messages)
        try:
            content = await self._invoke(prepared, self._remaining(deadline))
        except Exception as exc:  # noqa: BLE001 — 统一分类后再上抛为结果
            kind = classify_error(exc)
            self._record(prompt.ref, f"failure:{kind.value}", 1, 1, budget_ms, started)
            logger.warning("LLM 文本调用失败: prompt=%s kind=%s error=%s", prompt.ref, kind, exc)
            return self._failure(
                kind,
                attempts=1,
                requests=1,
                budget_ms=budget_ms,
                started=started,
                # 文本调用不重试：等到超预算就是「预算用尽」
                budget_exhausted=kind is LlmErrorKind.TIMEOUT,
            )
        self._record(prompt.ref, "success", 1, 1, budget_ms, started)
        return LlmResult(value=content, error=None, attempts=1, requests=1,
                         latency_ms=_elapsed(started), budget_ms=budget_ms)

    async def json[U: BaseModel](
        self,
        prompt: Prompt,
        messages: list[BaseMessage],
        schema: type[U],
        *,
        timeout: float | None = None,  # noqa: ASYNC109 — 预算覆盖，不是等待语义
    ) -> LlmResult[U]:
        """结构化调用：抽取 JSON → pydantic 校验；解析失败做有限修复重试。

        重试**共享同一个截止时间**：每次尝试只能用剩余预算，剩余低于 ``min_attempt_ms``
        就不再发起新请求，因此「1 次解析重试」不会把等待翻倍（ARCH-2b）。
        """
        started = time.perf_counter()
        budget_ms = self._budget_ms(timeout)
        deadline = time.perf_counter() + budget_ms / 1000
        prepared = self._prepare_input(messages)
        attempt = 0
        requests = 0
        last_error: Exception | None = None

        while attempt <= self._parse_retries:
            remaining = self._remaining(deadline)
            if attempt > 0 and remaining < self._min_attempt_for(budget_ms):
                # 预算不足以完成一次有意义的调用：不再发起，按「预算用尽」收口
                logger.warning(
                    "剩余预算不足，放弃解析重试: prompt=%s attempts=%d remainingMs=%d",
                    prompt.ref,
                    attempt,
                    max(0, int(remaining * 1000)),
                )
                break

            attempt += 1
            requests += 1
            outgoing = prepared if attempt == 1 else _append_repair_hint(prepared)
            try:
                raw = await self._invoke_json(outgoing, remaining)
            except Exception as exc:  # noqa: BLE001 — 统一分类后上抛为结果
                kind = classify_error(exc)
                self._record(prompt.ref, f"failure:{kind.value}", attempt, requests, budget_ms,
                             started)
                logger.warning(
                    "LLM 结构化调用失败: prompt=%s kind=%s attempts=%d requests=%d error=%s",
                    prompt.ref,
                    kind,
                    attempt,
                    requests,
                    exc,
                )
                return self._failure(kind, attempt, requests, budget_ms, started)

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

            self._record(prompt.ref, "success", attempt, requests, budget_ms, started)
            return LlmResult(value=parsed, error=None, attempts=attempt, requests=requests,
                             latency_ms=_elapsed(started), budget_ms=budget_ms)

        # 走到这里有两种情况：解析始终不合格，或预算不足以再试一次。
        # 后者按 TIMEOUT 归类——调用方要能区分「模型一直不合契约」与「没时间再试」。
        exhausted = requests > 0 and self._remaining(deadline) < self._min_attempt_for(budget_ms)
        kind = LlmErrorKind.TIMEOUT if exhausted else LlmErrorKind.PARSE_FAILED
        self._record(prompt.ref, f"failure:{kind.value}", attempt, requests, budget_ms, started)
        logger.error(
            "结构化调用未能完成: prompt=%s kind=%s attempts=%d requests=%d error=%s",
            prompt.ref,
            kind,
            attempt,
            requests,
            last_error,
        )
        return self._failure(kind, max(attempt, 1), requests, budget_ms, started,
                             budget_exhausted=exhausted)

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
        budget_ms = self._budget_ms(timeout)
        prepared = self._prepare_input(messages)
        try:
            async with asyncio.timeout(budget_ms / 1000):
                async for chunk in self._model.astream(prepared):
                    content = getattr(chunk, "content", None)
                    if isinstance(content, str) and content:
                        yield content
        except Exception as exc:  # noqa: BLE001 — 分类后原样上抛，由调用方收尾
            kind = classify_error(exc)
            self._record(prompt.ref, f"failure:{kind.value}", 1, 1, budget_ms, started)
            logger.warning("LLM 流式调用中断: prompt=%s kind=%s error=%s", prompt.ref, kind, exc)
            raise
        self._record(prompt.ref, "success", 1, 1, budget_ms, started)

    async def _invoke(
        self,
        messages: list[BaseMessage],
        remaining: float,  # noqa: ASYNC109 — 剩余预算透传，不是等待语义
    ) -> str:
        """单次模型调用（用剩余预算封顶）；签名固定，便于测试替身。"""
        async with asyncio.timeout(max(remaining, 0.0)):
            response = await self._model.ainvoke(messages)
        return str(getattr(response, "content", "") or "")

    async def _invoke_json(
        self,
        messages: list[BaseMessage],
        remaining: float,  # noqa: ASYNC109 — 剩余预算透传，不是等待语义
    ) -> str:
        """结构化调用的单次执行。

        优先请求上游的 ``response_format=json_object``（DeepSeek 等 OpenAI 兼容服务支持），
        并在配置了 ``max_output_tokens`` 时给结构化输出封顶——结构化结果本来就短，
        封顶挡的是「模型开始写小作文」这种浪费预算的情况。
        拿不到这些能力的模型退化为「靠提示词约束 + 本层解析」——两条路都在这一层收口。
        """
        runnable = self._bind_extras(self._model, json_mode=True)
        async with asyncio.timeout(max(remaining, 0.0)):
            response = await runnable.ainvoke(messages)
        return str(getattr(response, "content", "") or "")

    def _bind_extras(self, model: Any, *, json_mode: bool) -> Any:
        """按能力给模型挂调用期参数；模型不支持时退回原样（不因参数而失败）。"""
        extras: dict[str, Any] = {}
        if self._max_output_tokens:
            extras["max_tokens"] = self._max_output_tokens
        if json_mode:
            extras["response_format"] = {"type": "json_object"}
        if not extras:
            return model
        try:
            return model.bind(**extras)
        except (AttributeError, TypeError):
            if json_mode:
                logger.debug("模型不支持 response_format=json_object，退回提示词约束")
            return model

    def _budget_ms(self, timeout: float | None) -> int:
        return int(round((timeout or self._default_timeout) * 1000))

    def _min_attempt_for(self, budget_ms: int) -> float:
        """本次预算下的「最小有意义尝试」下限（不超过预算的一半，见构造参数说明）。"""
        return min(self._min_attempt_s, budget_ms / 2000)

    @staticmethod
    def _remaining(deadline: float) -> float:
        """距截止时间还剩多少秒（可能为负：预算已经用尽）。"""
        return deadline - time.perf_counter()

    def _prepare_input(self, messages: list[BaseMessage]) -> list[BaseMessage]:
        """输入长度 backstop。

        业务侧已经按各自上限裁剪过上下文（历史条数、简历/JD 片段、单条消息长度），
        这里兜的是「调用方忘了裁」的情况：超长时截断**最后一条**消息并留下可读标记，
        而不是让一次请求把预算全花在输入上。系统提示与前面的上下文保持完整——
        指令被截断比数据被截断危险得多。
        """
        if not self._max_input_chars or not messages:
            return messages
        total = sum(len(_content_of(message)) for message in messages)
        if total <= self._max_input_chars:
            return messages
        trimmed = list(messages)
        last = trimmed[-1]
        content = _content_of(last)
        other = total - len(content)
        # 预留标记自身的长度，保证「截断 + 标记」之后仍在上限内
        keep = max(0, self._max_input_chars - other - _OVERFLOW_MARKER_RESERVE)
        marker = f"\n…（输入超长，已截断 {len(content) - keep} 字）"
        trimmed[-1] = last.model_copy(update={"content": content[:keep] + marker})
        logger.warning(
            "输入超过上限，已截断最后一条消息: limit=%d total=%d kept=%d",
            self._max_input_chars,
            total,
            keep,
        )
        return trimmed

    def _failure(
        self,
        kind: LlmErrorKind,
        attempts: int,
        requests: int,
        budget_ms: int,
        started: float,
        *,
        budget_exhausted: bool = False,
    ) -> LlmResult[Any]:
        return LlmResult(value=None, error=kind, attempts=max(attempts, 1), requests=requests,
                         latency_ms=_elapsed(started), budget_ms=budget_ms,
                         budget_exhausted=budget_exhausted)

    @staticmethod
    def _record(
        prompt_ref: str,
        status: str,
        attempts: int,
        requests: int,
        budget_ms: int,
        started: float,
    ) -> None:
        latency = _elapsed(started)
        _CALLS.append(
            LlmCallRecord(prompt=prompt_ref, status=status, attempts=attempts, requests=requests,
                          latency_ms=latency, budget_ms=budget_ms)
        )
        logger.info(
            "LLM 调用: prompt=%s status=%s attempts=%d requests=%d latencyMs=%d budgetMs=%d",
            prompt_ref,
            status,
            attempts,
            requests,
            latency,
            budget_ms,
        )


def _content_of(message: BaseMessage) -> str:
    content = getattr(message, "content", "")
    return content if isinstance(content, str) else str(content)


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
