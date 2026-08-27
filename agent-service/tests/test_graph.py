"""Copilot Turn Graph 与节点单元测试。

通过 fake 意图路由 / fake 回答器 / Mock 后端注入，不调用真实 LLM 与 Java 服务。
覆盖：normalize_input、route_intent 确定性分支、attachment_flow、execute_action、
以及 Graph 端到端路由。
"""

import httpx
import pytest

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.graph import build_graph, build_initial_state
from career_copilot.agent.nodes.normalize_input import normalize_input
from career_copilot.agent.nodes.route_intent import route_intent
from career_copilot.agent.router import Intent, IntentClassification
from career_copilot.clients.backend import BackendClient


class FakeIntentRouter:
    """返回预设分类结果，不触发真实 LLM。"""

    def __init__(self, classification: IntentClassification) -> None:
        self._classification = classification
        self.calls = 0

    async def classify(self, message: str) -> IntentClassification:
        self.calls += 1
        return self._classification


class FakeAnswerer:
    async def answer_stream(self, message: str, context: str | None = None):
        for char in "fake answer":
            yield char


def make_deps(
    classification: IntentClassification,
    backend_transport,
) -> tuple[GraphDeps, FakeIntentRouter]:
    """组装 fake 依赖：返回 (deps, 意图路由引用)，便于断言分类调用次数。"""
    router = FakeIntentRouter(classification)
    backend = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(backend_transport),
    )
    deps = GraphDeps(
        intent_router=router,
        answerer=FakeAnswerer(),
        backend=backend,
    )
    return deps, router


@pytest.fixture
def backend_transport():
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
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
                "suggestions": [],
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


# ===== normalize_input =====


def test_normalize_input_derives_input_types():
    """normalize_input 应确定性推导 input_type，不调用 LLM。"""
    assert normalize_input({"message": "你好"})["input_type"] == "TEXT"
    assert normalize_input({"message": "", "attachments": []})["input_type"] == "TEXT"

    state = {"message": "这是我的简历", "attachments": [{"kind": "resume"}]}
    assert normalize_input(state)["input_type"] == "TEXT_WITH_ATTACHMENT"

    state = {"message": "", "attachments": [{"kind": "resume"}]}
    assert normalize_input(state)["input_type"] == "ATTACHMENT"

    state = {"message": "", "action": {"type": "ACTION_SELECTED", "action": "X"}}
    assert normalize_input(state)["input_type"] == "ACTION"


# ===== route_intent =====


async def test_route_intent_skips_classifier_for_action(backend_transport):
    """action 输入应确定性路由，不调用 LLM 分类。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    state = {
        "input_type": "ACTION",
        "message": "",
        "action": {"type": "ACTION_SELECTED", "action": "OPTIMIZE_RESUME"},
    }
    result = await route_intent(state, deps)
    assert result["intent"] == "ACTION"
    assert router.calls == 0


async def test_route_intent_skips_classifier_for_attachment(backend_transport):
    """仅附件输入应确定性路由到 ATTACHMENT_RECEIVED，不调用 LLM。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    state = {"input_type": "ATTACHMENT", "message": ""}
    result = await route_intent(state, deps)
    assert result["intent"] == "ATTACHMENT_RECEIVED"
    assert router.calls == 0


async def test_route_intent_uses_classifier_for_text(backend_transport):
    """开放文本输入才调用 LLM 意图分类。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.KNOWLEDGE_QA), backend_transport
    )
    state = {"input_type": "TEXT", "message": "JVM 是什么"}
    result = await route_intent(state, deps)
    assert result["intent"] == "KNOWLEDGE_QA"
    assert router.calls == 1


# ===== attachment_flow / execute_action 端到端 =====


async def test_graph_attachment_only_produces_choice(backend_transport):
    """Graph 端到端：仅附件 → attachment_flow → ChoiceBlock（含 4 个选项）。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="",
        attachments=[{"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}],
        action=None,
    )
    result = await graph.ainvoke(state)

    assert router.calls == 0, "附件路径不应调用意图分类"
    plan = result["plan"]
    assert plan is not None
    assert len(plan.blocks) == 1
    block = plan.blocks[0]
    assert block.type == "choice"
    assert {opt.action for opt in block.options} == {
        "ANALYZE_RESUME",
        "OPTIMIZE_RESUME",
        "START_INTERVIEW",
        "JOB_MATCH",
    }


async def test_graph_action_routes_to_execute_action(backend_transport):
    """Graph 端到端：action 提交 → execute_action → 确定性分发。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="",
        attachments=[],
        action={
            "type": "ACTION_SELECTED",
            "action": "ANALYZE_RESUME",
            "payload": {"resumeId": 9},
        },
    )
    result = await graph.ainvoke(state)

    assert router.calls == 0
    plan = result["plan"]
    assert len(plan.blocks) == 1
    block = plan.blocks[0]
    assert block.type == "action"
    assert block.route == "RESUME_DETAIL"
    assert block.params["resumeId"] == 9


async def test_graph_text_routes_to_direct_answer(backend_transport):
    """Graph 端到端：纯文本 → LLM 意图 → direct_answer 流式回答。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.GENERAL_CHAT), backend_transport
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None, message="你好", attachments=[], action=None
    )
    result = await graph.ainvoke(state)

    assert router.calls == 1
    plan = result["plan"]
    assert plan is not None
    assert plan.blocks == []
    chunks = [chunk async for chunk in plan.text]
    assert "".join(chunks) == "fake answer"


async def test_graph_resume_query_targets_uploaded_resume(backend_transport):
    """Graph 端到端：上传简历 + 询问 → RESUME_QUERY 应基于该份简历分析回答。

    通过断言不调用 get_resume_list（整库）验证不再反问是哪一份。
    """
    called: list[str] = []

    def tracking_handler(request: httpx.Request) -> httpx.Response:
        called.append(request.url.path)
        return backend_transport(request)

    deps, router = make_deps(
        IntentClassification(intent=Intent.RESUME_QUERY), tracking_handler
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="我的这份简历怎么样",
        attachments=[{"kind": "resume", "resume_id": 9, "filename": "resume.pdf"}],
        action=None,
    )
    result = await graph.ainvoke(state)

    plan = result["plan"]
    assert len(plan.blocks) == 1
    block = plan.blocks[0]
    assert block.type == "resume_summary"
    assert block.resumes[0]["id"] == 9
    # 应只调用目标简历分析，不查整库
    assert any("get_resume_analysis" in path for path in called)
    assert not any("get_resume_list" in path for path in called)