"""测试公共 fixture：fake 模型与 Mock 后端。

普通测试一律不调用真实 LLM / 真实 Java 后端（见 agent-service 规则 #50）。
"""

from collections.abc import Callable

import httpx
import pytest

from career_copilot.agent.router import Intent, IntentClassification
from career_copilot.clients.backend import BackendClient


class FakeStructuredModel:
    """模拟 with_structured_output() 之后的 Runnable：直接返回预设分类结果。"""

    def __init__(self, result: IntentClassification) -> None:
        self._result = result

    async def ainvoke(self, messages: list) -> IntentClassification:
        return self._result


class FakeChatModel:
    """模拟普通 ChatModel：返回预设文本，不触发任何网络调用。

    同时支持 ainvoke（同步回答）与 astream（流式回答，按字产出 chunk），
    使 Answerer 的同步/流式路径都能被测试覆盖。
    """

    def __init__(self, text: str = "fake answer") -> None:
        self._text = text

    def with_structured_output(self, schema, **kwargs) -> FakeStructuredModel:
        return FakeStructuredModel(self._classification)

    async def ainvoke(self, messages: list):
        return FakeChatResult(self._text)

    async def astream(self, messages: list):
        for char in self._text:
            yield FakeChatResult(char)


class FakeChatResult:
    def __init__(self, content: str) -> None:
        self.content = content


def make_fake_model(classification: IntentClassification, text: str = "fake answer"):
    """构造带预设意图分类与回答文本的 fake 模型。"""

    class FakeModel(FakeChatModel):
        def __init__(self) -> None:
            super().__init__(text)
            self._classification = classification

    return FakeModel()


@pytest.fixture
def fake_classification() -> IntentClassification:
    return IntentClassification(intent=Intent.GENERAL_CHAT)


@pytest.fixture
def mock_backend_transport() -> Callable[[httpx.Request], httpx.Response]:
    """Mock Java /api/agent/tools 统一入口：按 Tool 名返回 ToolResponse 信封。"""

    def handler(request: httpx.Request) -> httpx.Response:
        tool = request.url.path.rsplit("/", 1)[-1]
        data = {
            "get_resume_list": [
                {"id": 1, "filename": "resume.pdf", "latestScore": 82},
            ],
            "get_interview_history": [
                {
                    "sessionId": "s1",
                    "skillId": "java-backend",
                    "status": "COMPLETED",
                    "evaluateStatus": "COMPLETED",
                },
            ],
            "list_knowledge_bases": [{"id": 1, "name": "Java 知识库"}],
            "search_knowledge": {
                "answer": "JVM 是 Java 虚拟机。",
                "knowledgeBaseId": 1,
                "knowledgeBaseName": "Java 知识库",
            },
        }.get(tool, [])
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {"tool": tool, "data": data},
                "message": "success",
            },
        )

    return handler


@pytest.fixture
def backend_client(mock_backend_transport) -> BackendClient:
    """带 MockTransport 的 BackendClient，测试无需真实后端。"""
    return BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(mock_backend_transport),
    )