"""Chat API 端到端测试：验证各意图短路的响应结构。

通过依赖覆盖注入 fake 意图路由 / fake 回答器 / Mock 后端，不调用真实 LLM 与 Java 服务。
"""

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
    get_answerer,
    get_backend_client,
    get_intent_router,
)
from career_copilot.clients.backend import BackendClient, BusinessToolError
from career_copilot.main import app
from career_copilot.schemas.message import ActionBlock


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


def test_chat_stream_persists_turn_with_conversation_id():
    """携带 conversation_id 时，流式结束后应保存本轮消息到 Java。"""
    saved: list[dict] = []

    def tracking_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.startswith("/api/agent/conversations") and path.endswith("/messages"):
            import json as _json

            saved.append(_json.loads(request.read().decode()))
            return httpx.Response(
                200, json={"code": 200, "data": None, "message": "success"}
            )
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
            return httpx.Response(
                200, json={"code": 200, "data": None, "message": "success"}
            )
        tool = path.rsplit("/", 1)[-1]
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {"tool": tool, "data": []},
                "message": "success",
            },
        )

    client = setup_overrides(
        IntentClassification(intent=Intent.GENERAL_CHAT), tracking_handler
    )
    with client.stream(
        "POST",
        "/api/chat/stream",
        json={"message": "你好", "conversation_id": "5"},
    ) as response:
        events = _parse_sse("".join(response.iter_text()))

    assert events[-1]["type"] == "done"
    assert len(saved) == 1, "应保存一次消息"
    payload = saved[0]["messages"]
    assert payload[0]["role"] == "USER"
    assert payload[0]["content"] == "你好"
    assert payload[1]["role"] == "ASSISTANT"
    assert payload[1]["content"] == "fake answer"


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
    action_blocks = [b for b in blocks if b["type"] == "action"]
    assert action_blocks, "应产出 action 块"
    assert action_blocks[0]["route"] == "RESUME_DETAIL"
    assert action_blocks[0]["params"]["resumeId"] == 9


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


def test_chat_stream_resume_query_analysis_pending():
    """目标简历仍在后台分析时应如实告知，而不是反问是哪份。"""

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
    deltas = "".join(e["payload"]["content"] for e in events if e["type"] == "message_delta")
    assert "后台分析" in deltas
    assert not any(e["type"] == "block" for e in events)
