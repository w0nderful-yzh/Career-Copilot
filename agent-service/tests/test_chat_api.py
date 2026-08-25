"""Chat API 端到端测试：验证各意图短路的响应结构。

通过依赖覆盖注入 fake 意图路由 / fake 回答器 / Mock 后端，不调用真实 LLM 与 Java 服务。
"""

import httpx
import pytest
from fastapi.testclient import TestClient

from career_copilot.agent.router import (
    Intent,
    IntentClassification,
    NavigationRoute,
)
from career_copilot.api import chat as chat_module
from career_copilot.api.chat import (
    get_answerer,
    get_backend_client,
    get_intent_router,
)
from career_copilot.clients.backend import BackendClient, BusinessToolError
from career_copilot.main import app
from career_copilot.schemas.message import NavigationBlock


class FakeIntentRouter:
    """返回预设分类结果的意图路由。"""

    def __init__(self, classification: IntentClassification) -> None:
        self._classification = classification

    async def classify(self, message: str) -> IntentClassification:
        return self._classification


class FakeAnswerer:
    """返回固定文本的回答器。"""

    async def answer(self, message: str, context: str | None = None) -> str:
        return "fake answer"


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
        tool = request.url.path.rsplit("/", 1)[-1]
        data = {
            "get_resume_list": [{"id": 1, "filename": "resume.pdf", "latestScore": 82}],
            "get_interview_history": [{"sessionId": "s1", "skillId": "java-backend"}],
            "list_knowledge_bases": [{"id": 1, "name": "Java 知识库"}],
            "search_knowledge": {"answer": "JVM 是 Java 虚拟机。"},
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
    assert body["blocks"][0]["type"] == "text"


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
        isinstance(block, NavigationBlock) or block.get("type") == "navigation"
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
        block.get("type") == "navigation"
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


def test_navigation_returns_navigation_block(backend_transport):
    """NAVIGATION 应返回对应路由的导航块。"""
    client = setup_overrides(
        IntentClassification(
            intent=Intent.NAVIGATION,
            navigation_route=NavigationRoute.INTERVIEW_CREATE,
        ),
        backend_transport,
    )
    response = client.post("/api/chat", json={"message": "给我来场模拟面试"})
    body = response.json()
    assert body["blocks"][1]["type"] == "navigation"
    assert body["blocks"][1]["route"] == "INTERVIEW_CREATE"
    assert body["blocks"][1]["label"] == "开始模拟面试"

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
