"""面试进展对 Agent 可见（P4-10）。

两个验收点：
1. Agent 能解释**当前**话题与已发生内容（不必等面试结束）——靠新增的
   `get_interview_progress` 读路径 + 摘要函数；
2. 读取是**按需**的：只有前端带了进行中的会话 ID 才走这条路，
   会话没带时仍走原来的「历史 + 报告」路径（面试逐轮循环不接回主 Graph）。
"""

from typing import Any

import httpx
import pytest

from career_copilot.agent.graph import build_initial_state
from career_copilot.agent.nodes.business_tools import business_tools
from career_copilot.agent.router import Intent
from career_copilot.clients.backend import BackendClient
from career_copilot.schemas.message import ChatRequest
from career_copilot.tools import summarize_interview_progress


def _progress_payload() -> dict[str, Any]:
    return {
        "sessionId": "s1",
        "status": "IN_PROGRESS",
        "askedTurnCount": 2,
        "satisfiedRequiredTopicCount": 1,
        "requiredTopics": ["JVM", "数据库"],
        "currentQuestion": {
            "questionId": "q3",
            "question": "数据库 的主问题",
            "topic": "数据库",
            "isFollowUp": False,
        },
        "coverageSummary": "- 必要覆盖：JVM=已覆盖；数据库=本轮正在考察",
        "budgetSummary": "- 时间预算：剩余约 15 分钟",
        "legalCandidates": ["[q4] 主问题｜算法｜中级：算法 的主问题"],
        "turns": [
            {
                "ordinal": 1,
                "questionId": "q1",
                "question": "JVM 的主问题",
                "topic": "JVM",
                "category": "JVM",
                "answerState": "ANSWERED",
                "userAnswer": "堆分新生代与老年代",
            },
            {
                "ordinal": 2,
                "questionId": "q2",
                "question": "JVM 的追问",
                "topic": "JVM",
                "answerState": "SKIPPED",
                "userAnswer": None,
            },
        ],
    }


def test_summarize_renders_current_topic_and_history():
    """摘要要答得上「现在在考什么、已经发生过什么、还剩什么」。"""
    text = summarize_interview_progress(_progress_payload())

    assert "当前话题: 数据库" in text
    assert "必要覆盖达标 1/2" in text
    assert "JVM=已覆盖" in text
    assert "剩余约 15 分钟" in text
    assert "下一步可能问" in text and "算法" in text
    assert "第1轮" in text and "堆分新生代与老年代" in text
    # 跳过的那一轮也要在：没有作答内容 ≠ 没发生过
    assert "第2轮" in text and "状态=SKIPPED" in text and "（无作答内容）" in text


def test_summarize_marks_missing_data_instead_of_faking_it():
    """没有计划/候选时如实标注，不编造预算或覆盖。"""
    text = summarize_interview_progress({"sessionId": "s2", "status": "IN_PROGRESS"})

    assert "当前题: 无（本场已收束）" in text
    assert "覆盖" not in text and "预算" not in text
    assert summarize_interview_progress({}) == "（面试进展不可用）"


@pytest.mark.asyncio
async def test_live_session_reads_progress_instead_of_report(mock_backend_transport):
    """带上进行中的会话：走实时进展，不再去翻历史列表。"""
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(request.url.path.rsplit("/", 1)[-1])
        return mock_backend_transport(request)

    backend = BackendClient("http://java", 5.0, transport=httpx.MockTransport(handler))
    deps = _deps(backend)
    state = build_initial_state(
        conversation_id=1,
        message="现在考到哪了？还剩什么？",
        attachments=[],
        action=None,
        active_interview_session_id="s1",
    )
    state["intent"] = Intent.INTERVIEW_REVIEW.value

    result = await business_tools(state, deps)

    assert requested == ["get_interview_progress"]
    text = result["plan"].text
    rendered = "".join([chunk async for chunk in text])
    # 进展摘要进入 Prompt（fake 回答器把它原样吐出，便于断言「喂进去了什么」）
    assert "面试 s1 进行中" in rendered
    assert "当前话题: 数据库" in rendered
    await backend.aclose()


@pytest.mark.asyncio
async def test_progress_failure_is_explained_not_silently_downgraded():
    """读不到进展时说清原因，不静默退化成「你还没有面试记录」。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"code": 3001, "message": "会话不存在", "data": None}
        )

    backend = BackendClient("http://java", 5.0, transport=httpx.MockTransport(handler))
    deps = _deps(backend)
    state = build_initial_state(
        conversation_id=1,
        message="现在考到哪了？",
        attachments=[],
        action=None,
        active_interview_session_id="s1",
    )
    state["intent"] = Intent.INTERVIEW_REVIEW.value

    result = await business_tools(state, deps)

    rendered = "".join([chunk async for chunk in result["plan"].text])
    assert "暂时读不到" in rendered
    await backend.aclose()


@pytest.mark.asyncio
async def test_without_live_session_still_uses_history_path(mock_backend_transport):
    """没带进行中的会话：行为不变（历史 + 摘要），不凭空去读进展。"""
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(request.url.path.rsplit("/", 1)[-1])
        return mock_backend_transport(request)

    backend = BackendClient("http://java", 5.0, transport=httpx.MockTransport(handler))
    deps = _deps(backend)
    state = build_initial_state(
        conversation_id=1, message="我最近面试怎么样？", attachments=[], action=None
    )
    state["intent"] = Intent.INTERVIEW_REVIEW.value

    await business_tools(state, deps)

    assert requested == ["get_interview_history"]
    await backend.aclose()


def test_chat_request_carries_active_interview_session():
    """请求契约：前端能带进行中的会话 ID，并进入 Graph 初始状态。"""
    payload = ChatRequest(message="现在考到哪了？", active_interview_session_id="s1")
    assert payload.active_interview_session_id == "s1"
    assert (
        build_initial_state(
            conversation_id=None,
            message="x",
            attachments=[],
            action=None,
            active_interview_session_id="s1",
        )["active_interview_session_id"]
        == "s1"
    )


class _EchoModel:
    """把收到的提示原样吐出的模型：便于断言「到底喂进去了什么」。"""

    def bind(self, **kwargs):  # noqa: ANN003, ANN201 - 与真实模型接口对齐
        return self

    async def ainvoke(self, messages: list):
        from tests.conftest import FakeChatResult

        return FakeChatResult("".join(str(getattr(m, "content", "")) for m in messages))

    async def astream(self, messages: list):
        from tests.conftest import FakeChatResult

        for message in messages:
            yield FakeChatResult(str(getattr(message, "content", "")))


def _deps(backend: BackendClient):
    """只用到 backend 与 answerer（回声模型把上下文原样吐出）。"""
    from career_copilot.agent.answerer import Answerer
    from career_copilot.agent.deps import GraphDeps
    from career_copilot.agent.router import IntentRouter
    from tests.conftest import executor_for

    executor = executor_for(_EchoModel())
    return GraphDeps(
        intent_router=IntentRouter(executor),
        answerer=Answerer(executor),
        backend=backend,
        llm=executor,
    )
