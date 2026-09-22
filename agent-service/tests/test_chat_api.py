"""Chat API 端到端测试：验证各意图短路的响应结构。

通过依赖覆盖注入 fake 意图路由 / fake 回答器 / Mock 后端，不调用真实 LLM 与 Java 服务。
"""

import asyncio
import json
import time
from collections.abc import Callable

import httpx
import pytest
from fastapi.testclient import TestClient

from career_copilot.agent.router import (
    ActionRoute,
    Intent,
    IntentClassification,
)
from career_copilot.api import chat as chat_module
from career_copilot.api.chat import (
    chat_stream,
    get_answerer,
    get_backend_client,
    get_intent_router,
    get_llm_executor,
)
from career_copilot.clients.backend import BackendClient, BusinessToolError
from career_copilot.main import app
from career_copilot.schemas.message import ActionBlock, ChatRequest
from tests.conftest import make_fake_executor


class FakeIntentRouter:
    """返回预设分类结果的意图路由。"""

    def __init__(self, classification: IntentClassification) -> None:
        self._classification = classification

    async def classify(
        self, message: str, history: str | None = None
    ) -> IntentClassification:
        return self._classification


class FakeAnswerer:
    """返回固定文本的回答器，支持同步与流式两种调用。"""

    async def answer(
        self, message: str, context: str | None = None, history: str | None = None
    ) -> str:
        return "fake answer"

    async def answer_stream(
        self, message: str, context: str | None = None, history: str | None = None
    ):
        for char in "fake answer":
            yield char

    async def summarize_history(self, history_text: str) -> str:
        return "早期对话摘要：用户咨询 Java 后端实习。"


def setup_overrides(
    classification: IntentClassification,
    backend_transport,
) -> TestClient:
    """组装依赖覆盖并返回 TestClient。"""
    app.dependency_overrides[get_intent_router] = lambda: FakeIntentRouter(
        classification
    )
    app.dependency_overrides[get_answerer] = lambda: FakeAnswerer()
    app.dependency_overrides[get_llm_executor] = lambda: make_fake_executor()

    def fake_client() -> BackendClient:
        return BackendClient(
            base_url="http://test",
            transport=httpx.MockTransport(backend_transport),
        )

    app.dependency_overrides[get_backend_client] = fake_client
    return TestClient(app)


@pytest.fixture
def backend_transport():
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        # 会话持久化路径：返回成功，避免流式测试触发保存时抛错
        if path.startswith("/api/agent/conversations"):
            return httpx.Response(200, json={"code": 200, "data": None, "message": "success"})
        tool = path.rsplit("/", 1)[-1]
        data = {
            "get_resume_list": [{"id": 1, "filename": "resume.pdf", "latestScore": 82}],
            "get_resume": {
                "id": 1,
                "filename": "resume.pdf",
                "resumeText": (
                    "姓名：张三\n"
                    "项目经历：基于 LangGraph 构建 Agent 平台\n"
                    "技能：Java、Redis"
                ),
                "analyzeStatus": "COMPLETED",
            },
            "get_resume_analysis": {
                "overallScore": 82,
                "scoreDetail": {
                    "contentScore": 20,
                    "structureScore": 16,
                    "skillMatchScore": 21,
                    "expressionScore": 12,
                    "projectScore": 13,
                },
                "summary": "整体较好，项目经验突出。",
                "strengths": ["项目描述清晰"],
                "suggestions": [
                    {
                        "category": "内容",
                        "priority": "高",
                        "issue": "缺乏量化",
                        "recommendation": "补充量化数据",
                    }
                ],
            },
            "get_interview_history": [{"sessionId": "s1", "skillId": "java-backend"}],
            "list_knowledge_bases": [{"id": 1, "name": "Java 知识库"}],
            "search_knowledge": {
                "answer": "JVM 是 Java 虚拟机。",
                "knowledgeBaseId": 1,
                "knowledgeBaseName": "Java 知识库",
            },
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    return handler


def test_general_chat_returns_answer(backend_transport):
    """GENERAL_CHAT 直接返回回答器文本。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    response = client.post("/api/chat", json={"message": "你好"})
    assert response.status_code == 200
    body = response.json()
    assert body["content"] == "fake answer"
    # GENERAL_CHAT 无结构化块，纯文本
    assert body["blocks"] == []


def test_resume_query_with_resumes(backend_transport):
    """RESUME_QUERY 有简历时返回带上下文的回答。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), backend_transport
    )
    response = client.post("/api/chat", json={"message": "我的简历怎么样"})
    assert response.status_code == 200
    assert response.json()["content"] == "fake answer"


def test_resume_query_empty_returns_navigation(backend_transport):
    """RESUME_QUERY 无简历时应返回上传简历导航。"""

    def empty_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": [],
                "message": "success",
            },
        )

    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), empty_handler
    )
    response = client.post("/api/chat", json={"message": "我的简历呢"})
    body = response.json()
    blocks = body["blocks"]
    assert any(
        isinstance(block, ActionBlock) or block.get("type") == "action"
        for block in blocks
    )


def test_interview_review_empty_returns_navigation(backend_transport):
    """INTERVIEW_REVIEW 无记录时应返回开始面试导航。"""

    def empty_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": [],
                "message": "success",
            },
        )

    client = setup_overrides(
        IntentClassification(intent=Intent.INTERVIEW_REVIEW), empty_handler
    )
    response = client.post("/api/chat", json={"message": "我面试得怎么样"})
    body = response.json()
    assert any(
        block.get("type") == "action"
        and block.get("route") == "INTERVIEW_CREATE"
        for block in body["blocks"]
    )


def test_knowledge_qa_returns_rag_answer(backend_transport):
    """KNOWLEDGE_QA 应返回 RAG 检索答案。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.KNOWLEDGE_QA), backend_transport
    )
    response = client.post("/api/chat", json={"message": "JVM GC 是什么"})
    assert response.status_code == 200
    assert response.json()["content"] == "JVM 是 Java 虚拟机。"


def test_navigation_returns_action_block(backend_transport):
    """NAVIGATION 应返回白名单路由的动作块。"""
    client = setup_overrides(
        IntentClassification(
            intent=Intent.NAVIGATION,
            action_route=ActionRoute.INTERVIEW_CREATE,
        ),
        backend_transport,
    )
    response = client.post("/api/chat", json={"message": "给我来场模拟面试"})
    body = response.json()
    assert body["blocks"][0]["type"] == "action"
    assert body["blocks"][0]["route"] == "INTERVIEW_CREATE"
    assert body["blocks"][0]["label"] == "开始模拟面试"

# ===== Agent LLM 配置同步 =====


@pytest.mark.asyncio
async def test_sync_agent_llm_config_success(monkeypatch):
    """同步成功时应缓存 Java 下发的 Agent Provider 配置。"""
    expected = {
        "providerId": "dashscope",
        "baseUrl": "https://api.example.com/v1",
        "model": "qwen3.5-flash",
        "apiKey": "secret",
    }

    class FakeClient:
        """替换 BackendClient：直接返回预设配置，不发起真实请求。"""

        def __init__(self, base_url, timeout) -> None:
            pass

        async def get_agent_llm_config(self) -> dict:
            return expected

        async def aclose(self) -> None:
            pass

    monkeypatch.setattr(chat_module, "BackendClient", FakeClient)
    await chat_module.sync_agent_llm_config()
    assert chat_module._agent_llm_config == expected
    # 清理全局缓存，避免污染其他测试
    chat_module._agent_llm_config = None


@pytest.mark.asyncio
async def test_sync_agent_llm_config_failure_falls_back(monkeypatch):
    """同步失败时应保持 None（回落 .env 配置），且不抛出异常。"""

    class FailingClient:
        def __init__(self, base_url, timeout) -> None:
            pass

        async def get_agent_llm_config(self) -> dict:
            raise BusinessToolError(500, "后端服务不可达", retryable=True)

        async def aclose(self) -> None:
            pass

    monkeypatch.setattr(chat_module, "BackendClient", FailingClient)
    await chat_module.sync_agent_llm_config()
    assert chat_module._agent_llm_config is None


@pytest.mark.asyncio
async def test_ensure_llm_config_synced_retries_lazily(monkeypatch):
    """启动同步失败后，首个请求应惰性重试同步（解决 Agent 先于 Java 启动）。"""
    chat_module._agent_llm_config = None
    calls = {"count": 0}

    class LazyClient:
        def __init__(self, base_url, timeout) -> None:
            pass

        async def get_agent_llm_config(self) -> dict:
            calls["count"] += 1
            return {
                "providerId": "deepseek",
                "baseUrl": "https://api.deepseek.com",
                "model": "deepseek-v4-flash",
                "apiKey": "secret",
            }

        async def aclose(self) -> None:
            pass

    monkeypatch.setattr(chat_module, "BackendClient", LazyClient)
    # 首次请求触发重试并同步成功
    await chat_module._ensure_llm_config_synced()
    assert chat_module._agent_llm_config is not None
    assert calls["count"] == 1
    # 已同步后不再重试
    await chat_module._ensure_llm_config_synced()
    assert calls["count"] == 1
    # 清理全局缓存，避免污染其他测试
    chat_module._agent_llm_config = None


# ===== SSE 流式端点 =====


def _parse_sse(stream_text: str) -> list[dict]:
    """解析 SSE data 行，返回事件列表。"""
    events = []
    for line in stream_text.splitlines():
        if line.startswith("data: "):
            import json

            events.append(json.loads(line[len("data: "):]))
    return events


def test_chat_stream_general_chat(backend_transport):
    """GENERAL_CHAT 流式：message_delta 逐字 + done 结尾。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    with client.stream("POST", "/api/chat/stream", json={"message": "你好"}) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        events = _parse_sse("".join(response.iter_text()))

    deltas = [e["payload"]["content"] for e in events if e["type"] == "message_delta"]
    assert "".join(deltas) == "fake answer"
    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)


def test_chat_stream_emits_tool_and_run_status_events(backend_transport):
    """P1-2：RESUME_QUERY 应产出 run_status 与 tool_started/completed 事件序列。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={"message": "我的简历怎么样", "conversation_id": 7},
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    types = [e["type"] for e in events]
    # run_status：RUNNING 开场、COMPLETED 收尾（done 之前）
    assert types[0] == "run_status"
    assert events[0]["payload"]["status"] == "RUNNING"
    assert types[-2:] == ["run_status", "done"]
    assert events[-2]["payload"]["status"] == "COMPLETED"

    # 定向简历查询（无附件但库中唯一 → 自动锁定）应埋点 resume_insight
    started = [e for e in events if e["type"] == "tool_started"]
    completed = [e for e in events if e["type"] == "tool_completed"]
    tools_started = [e["payload"]["tool"] for e in started]
    assert "resume_insight" in tools_started
    # load_history 在携带 conversation_id 时也应出现
    assert "load_history" in tools_started
    # started 与 completed 成对出现且顺序正确
    assert [e["payload"]["tool"] for e in completed] == tools_started
    # label 面向用户中文
    insight = next(e for e in started if e["payload"]["tool"] == "resume_insight")
    assert insight["payload"]["label"]

    # 内容产出回归：block + 文本增量仍在
    assert any(e["type"] == "block" for e in events)
    deltas = "".join(
        e["payload"]["content"] for e in events if e["type"] == "message_delta"
    )
    assert deltas == "fake answer"


def test_chat_stream_attachment_emits_waiting_user(backend_transport):
    """仅附件产生 ChoiceBlock 时应广播 WAITING_USER 状态。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "",
            "attachments": [
                {"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    statuses = [
        e["payload"]["status"] for e in events if e["type"] == "run_status"
    ]
    assert statuses[0] == "RUNNING"
    assert "WAITING_USER" in statuses


def test_chat_stream_resume_query_emits_block(backend_transport):
    """RESUME_QUERY 流式：先 block（resume_summary）后文本增量。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), backend_transport
    )
    with client.stream(
        "POST", "/api/chat/stream", json={"message": "我的简历怎么样"}
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    block_events = [e for e in events if e["type"] == "block"]
    assert block_events, "应产出 block 事件"
    assert block_events[0]["payload"]["type"] == "resume_summary"
    assert block_events[0]["payload"]["resumes"][0]["id"] == 1
    deltas = [e["payload"]["content"] for e in events if e["type"] == "message_delta"]
    assert "".join(deltas) == "fake answer"


def test_chat_stream_knowledge_qa_emits_citations(backend_transport):
    """KNOWLEDGE_QA 流式：产出 knowledge_citations 引用块。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.KNOWLEDGE_QA), backend_transport
    )
    with client.stream(
        "POST", "/api/chat/stream", json={"message": "JVM GC 是什么"}
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    citations = [
        e for e in events
        if e["type"] == "block" and e["payload"]["type"] == "knowledge_citations"
    ]
    assert citations
    assert citations[0]["payload"]["citations"][0]["knowledgeBaseId"] == 1
    deltas = [e["payload"]["content"] for e in events if e["type"] == "message_delta"]
    assert "".join(deltas) == "JVM 是 Java 虚拟机。"


def _tracking_handler(saved: list[dict]) -> Callable[[httpx.Request], httpx.Response]:
    """构造记录 /messages 落库请求的 Mock 后端处理器。"""

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.startswith("/api/agent/conversations") and path.endswith("/messages"):
            saved.append(json.loads(request.read().decode()))
            return httpx.Response(200, json={"code": 200, "data": None, "message": "success"})
        if path.endswith("/context"):
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {"messages": [], "summary": None, "totalCount": 0},
                    "message": "success",
                },
            )
        if path.startswith("/api/agent/conversations"):
            return httpx.Response(200, json={"code": 200, "data": None, "message": "success"})
        tool = path.rsplit("/", 1)[-1]
        return httpx.Response(
            200,
            json={"code": 200, "data": {"tool": tool, "data": []}, "message": "success"},
        )

    return handler


def _patch_persist_client(monkeypatch: pytest.MonkeyPatch, handler) -> None:
    """把「脱手落库」自建的客户端替换为带 MockTransport 的实例。

    落库刻意不复用请求作用域的 client（后者在请求结束时被 aclose），
    因此它不会走 dependency_overrides，必须在此单独注入，否则会打真实后端。
    """
    monkeypatch.setattr(
        chat_module,
        "_new_persist_client",
        lambda: BackendClient(base_url="http://test", transport=httpx.MockTransport(handler)),
    )


def _wait_until(predicate: Callable[[], bool], timeout: float = 3.0) -> bool:
    """轮询等待条件成立。

    落库已与响应生命周期解耦，响应结束时落库任务可能仍在进行，故不能立即断言。
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.02)
    return predicate()


def test_chat_stream_persists_turn_with_conversation_id(monkeypatch):
    """携带 conversation_id 时，流式结束后应保存本轮消息到 Java（终态 COMPLETED）。"""
    saved: list[dict] = []
    handler = _tracking_handler(saved)
    _patch_persist_client(monkeypatch, handler)

    client = setup_overrides(IntentClassification(intent=Intent.GENERAL_CHAT), handler)
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={"message": "你好", "conversation_id": "5"},
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert _wait_until(lambda: len(saved) == 1), "应保存一次消息"
    payload = saved[0]["messages"]
    assert payload[0]["role"] == "USER"
    assert payload[0]["content"] == "你好"
    assert payload[1]["role"] == "ASSISTANT"
    assert payload[1]["content"] == "fake answer"
    assert payload[1]["status"] == "COMPLETED"


async def test_chat_stream_aborted_turn_persists_as_stopped(monkeypatch):
    """用户点「停止生成」（客户端提前断开）时，本轮仍应落库并标记 STOPPED。

    回归点（P1 待收口）：此前落库写在 SSE 生成器的 finally 中且伴随 yield，
    客户端断开会让 finally 抛 RuntimeError: async generator ignored GeneratorExit，
    落库被整体跳过 —— 刷新后用户的问题和已生成的回答一起消失。

    这里直接驱动 StreamingResponse.body_iterator 并 aclose()：
    TestClient 的 response.close() 并不会真的中断服务端生成器（服务端会跑完），
    只有 aclose() 才会在挂起点抛出 GeneratorExit，是唯一能复现该缺陷的路径。
    """
    saved: list[dict] = []
    handler = _tracking_handler(saved)
    _patch_persist_client(monkeypatch, handler)

    backend = BackendClient(base_url="http://test", transport=httpx.MockTransport(handler))
    response = await chat_stream(
        ChatRequest(message="你好", conversation_id="7"),
        None,  # http_request：无 checkpointer 挂载需求，传 None 即可
        FakeIntentRouter(IntentClassification(intent=Intent.GENERAL_CHAT)),
        FakeAnswerer(),
        backend,
        make_fake_executor(),
    )

    agen = response.body_iterator
    await agen.__anext__()  # 取到首帧（run_status RUNNING）后中断，模拟用户点「停止」
    await agen.aclose()  # 触发 GeneratorExit
    await chat_module.flush_pending_persists()

    assert saved, "中断轮也必须落库，否则刷新后整轮消失"
    messages = saved[0]["messages"]
    assert messages[0]["role"] == "USER"
    assert messages[0]["content"] == "你好"
    # 中断轮尚未产出内容，但必须留一条 STOPPED 助手消息作为「已停止」的痕迹
    assert messages[1]["role"] == "ASSISTANT"
    assert messages[1]["status"] == "STOPPED"


def test_persist_turn_marks_failed_status(monkeypatch):
    """生成失败轮：助手消息落 FAILED 且保留已产出的部分内容。"""
    saved: list[dict] = []
    handler = _tracking_handler(saved)
    _patch_persist_client(monkeypatch, handler)

    # 直接驱动落库函数覆盖 FAILED 分支（Graph 抛错到 SSE error 事件由上面的用例覆盖）
    asyncio.run(
        chat_module._persist_conversation_turn(  # noqa: SLF001
            BackendClient(base_url="http://test", transport=httpx.MockTransport(handler)),
            88,
            "帮我复盘这次面试",
            "先看整体",
            [],
            "FAILED",
        )
    )

    assert len(saved) == 1
    messages = saved[0]["messages"]
    assert messages[0]["status"] is None
    assert messages[1]["status"] == "FAILED"
    assert messages[1]["content"] == "先看整体"


def test_persist_turn_stopped_with_empty_content_keeps_marker(monkeypatch):
    """停止且尚未产出内容时，仍要落一条空内容的 STOPPED 助手消息留痕。"""
    saved: list[dict] = []
    handler = _tracking_handler(saved)
    _patch_persist_client(monkeypatch, handler)

    asyncio.run(
        chat_module._persist_conversation_turn(  # noqa: SLF001
            BackendClient(base_url="http://test", transport=httpx.MockTransport(handler)),
            89,
            "帮我复盘这次面试",
            "",
            [],
            "STOPPED",
        )
    )

    assert len(saved) == 1
    messages = saved[0]["messages"]
    assert messages[1]["role"] == "ASSISTANT"
    assert messages[1]["status"] == "STOPPED"
    assert messages[1]["content"] == ""


def test_persist_turn_completed_with_empty_content_skips_assistant(monkeypatch):
    """已完成但无内容（无回复可展示）时不应写空助手消息。"""
    saved: list[dict] = []
    handler = _tracking_handler(saved)
    _patch_persist_client(monkeypatch, handler)

    asyncio.run(
        chat_module._persist_conversation_turn(  # noqa: SLF001
            BackendClient(base_url="http://test", transport=httpx.MockTransport(handler)),
            90,
            "你好",
            "",
            [],
            "COMPLETED",
        )
    )

    assert len(saved) == 1
    assert len(saved[0]["messages"]) == 1


def test_chat_stream_skips_persist_without_conversation_id(backend_transport):
    """无 conversation_id 时不应触发保存。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    with client.stream(
        "POST", "/api/chat/stream", json={"message": "你好"}
    ) as response:
        events = _parse_sse("".join(response.iter_text()))
    assert events[-1]["type"] == "done"


def test_chat_stream_with_resume_attachment(backend_transport):
    """仅附件（无文本）时应确定性识别简历并返回 ChoiceBlock（不依赖意图分类）。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "",
            "attachments": [
                {"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    deltas = "".join(e["payload"]["content"] for e in events if e["type"] == "message_delta")
    assert "加入简历库" in deltas
    assert "resume.pdf" in deltas
    blocks = [e["payload"] for e in events if e["type"] == "block"]
    assert any(b["type"] == "choice" for b in blocks)
    choice = next(b for b in blocks if b["type"] == "choice")
    assert {opt["action"] for opt in choice["options"]} == {
        "ANALYZE_RESUME",
        "OPTIMIZE_RESUME",
        "START_INTERVIEW",
        "JOB_MATCH",
    }


def test_chat_stream_with_duplicate_resume_attachment(backend_transport):
    """重复简历附件应如实告知已复用历史记录，而不是声称新加入简历库。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "",
            "attachments": [
                {
                    "kind": "resume",
                    "resume_id": 9,
                    "filename": "resume.pdf",
                    "duplicate": True,
                }
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    deltas = "".join(e["payload"]["content"] for e in events if e["type"] == "message_delta")
    assert "已有简历相同" in deltas
    assert "复用历史记录" in deltas
    assert "加入简历库" not in deltas
    blocks = [e["payload"] for e in events if e["type"] == "block"]
    assert any(b["type"] == "choice" for b in blocks)


def test_chat_stream_resume_attachment_skips_classifier(backend_transport):
    """仅附件路径应完全跳过意图分类：分类器不可用时仍返回确定性 ChoiceBlock。"""

    class BrokenIntentRouter:
        async def classify(self, message: str, history: str | None = None) -> IntentClassification:
            raise RuntimeError("intent classifier unavailable")

    app.dependency_overrides[get_intent_router] = lambda: BrokenIntentRouter()
    app.dependency_overrides[get_answerer] = lambda: FakeAnswerer()
    app.dependency_overrides[get_llm_executor] = lambda: make_fake_executor()

    def fake_client() -> BackendClient:
        return BackendClient(
            base_url="http://test",
            transport=httpx.MockTransport(backend_transport),
        )

    app.dependency_overrides[get_backend_client] = fake_client
    client = TestClient(app)
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "",
            "attachments": [
                {"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    deltas = "".join(e["payload"]["content"] for e in events if e["type"] == "message_delta")
    assert "加入简历库" in deltas
    assert "resume.pdf" in deltas
    assert any(
        e["type"] == "block" and e["payload"]["type"] == "choice"
        for e in events
    )


def test_chat_stream_with_action_routes_execute_action(backend_transport):
    """action 提交应走确定性 execute_action 分支（不依赖意图分类）。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "",
            "action": {
                "type": "ACTION_SELECTED",
                "action": "ANALYZE_RESUME",
                "payload": {"resumeId": 9},
            },
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    blocks = [e["payload"] for e in events if e["type"] == "block"]
    # Copilot 内真实分析：产出 resume_summary 内容卡片而非跳转导航
    summary_blocks = [b for b in blocks if b["type"] == "resume_summary"]
    assert summary_blocks, "应产出 resume_summary 分析卡片"
    assert summary_blocks[0]["resumes"][0]["id"] == 9
    deltas = "".join(
        e["payload"]["content"] for e in events if e["type"] == "message_delta"
    )
    assert deltas == "fake answer"


def test_chat_stream_text_with_attachment_uses_llm_intent(backend_transport):
    """带文本的附件应进入 LLM 意图分类（符合确定性优先原则：仅无文本附件才短路）。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "帮我分析这份简历",
            "attachments": [
                {"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    block_events = [e for e in events if e["type"] == "block"]
    assert block_events, "应产出 block 事件"
    assert block_events[0]["payload"]["type"] == "resume_summary"


def test_chat_stream_resume_query_uses_uploaded_resume(backend_transport):
    """上传简历并询问时，应基于该份简历的分析回答，而非整库反问。"""
    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), backend_transport
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "我的这份简历怎么样",
            "attachments": [
                {"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    blocks = [e["payload"] for e in events if e["type"] == "block"]
    assert blocks, "应产出 resume_summary 块"
    assert blocks[0]["type"] == "resume_summary"
    assert blocks[0]["resumes"][0]["id"] == 9
    deltas = "".join(e["payload"]["content"] for e in events if e["type"] == "message_delta")
    assert "".join(deltas) == "fake answer"


def test_chat_stream_resume_query_analysis_pending(monkeypatch):
    """目标简历仍在后台分析（未就绪）时应给出「稍后获取」ChoiceBlock 回退。

    不反问是哪份简历，也不请求内干等：等待窗口内未就绪即返回可重试选择。
    """
    from career_copilot.agent.nodes import business_tools as bt

    # 缩短等待窗口，避免测试长时间 sleep（行为本身不变）
    monkeypatch.setattr(bt.settings, "analysis_wait_attempts", 2)
    monkeypatch.setattr(bt.settings, "analysis_wait_delay_seconds", 0.01)

    def pending_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/get_resume_analysis"):
            return httpx.Response(
                200,
                json={"code": 5001, "message": "简历分析结果不存在", "data": None},
            )
        tool = path.rsplit("/", 1)[-1]
        data = {"get_resume_list": []}.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    client = setup_overrides(
        IntentClassification(intent=Intent.RESUME_QUERY), pending_handler
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={
            "message": "我的这份简历怎么样",
            "attachments": [
                {"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}
            ],
        },
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "error" for e in events)
    # 等待超时回退：产出「稍后获取分析结果」重试 ChoiceBlock + WAITING_USER
    blocks = [e["payload"] for e in events if e["type"] == "block"]
    assert len(blocks) == 1
    assert blocks[0]["type"] == "choice"
    option = blocks[0]["options"][0]
    assert option["action"] == "ANALYZE_RESUME"
    assert option["label"] == "稍后获取分析结果"
    assert option["payload"] == {"resumeId": 9}
    statuses = [e["payload"]["status"] for e in events if e["type"] == "run_status"]
    assert "WAITING_USER" in statuses
    deltas = "".join(e["payload"]["content"] for e in events if e["type"] == "message_delta")
    assert "后台" in deltas and "稍后" in deltas
