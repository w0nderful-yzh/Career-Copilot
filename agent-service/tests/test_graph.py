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

    async def classify(
        self, message: str, history: str | None = None
    ) -> IntentClassification:
        self.calls += 1
        return self._classification


class FakeAnswerer:
    async def answer_stream(
        self, message: str, context: str | None = None, history: str | None = None
    ):
        for char in "fake answer":
            yield char

    async def summarize_history(self, history_text: str) -> str:
        return "早期对话摘要：用户咨询 Java 后端实习。"


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
                "suggestions": [],
            },
            "get_interview_history": [{"sessionId": "s1", "skillId": "java-backend"}],
            "get_skill_profile": {
                "skills": [
                    {
                        "skill": "MySQL",
                        "score": 83,
                        "evidenceCount": 2,
                        "evidences": [
                            {"sourceType": "INTERVIEW_TURN", "sourceId": "s1:0", "score": 88,
                             "occurredAt": "2026-08-28T12:00:00"},
                            {"sourceType": "INTERVIEW_TURN", "sourceId": "s1:1", "score": 78,
                             "occurredAt": "2026-08-28T12:00:00"},
                        ],
                    },
                    {
                        "skill": "JVM",
                        "score": 55,
                        "evidenceCount": 1,
                        "evidences": [
                            {"sourceType": "INTERVIEW_TURN", "sourceId": "s1:2", "score": 55,
                             "occurredAt": "2026-08-28T12:00:00"},
                        ],
                    },
                ]
            },
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
    """Graph 端到端：action 提交 → execute_action → ANALYZE_RESUME 真实分析。"""
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

    assert router.calls == 0, "action 提交应确定性路由，不经意图分类"
    plan = result["plan"]
    # Copilot 内真实分析：产出 resume_summary 内容卡片而非跳转导航
    assert len(plan.blocks) == 1
    block = plan.blocks[0]
    assert block.type == "resume_summary"
    assert block.resumes[0]["id"] == 9


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


async def test_graph_targeted_resume_includes_content(backend_transport):
    """定向简历查询应把完整简历内容（get_resume）注入回答上下文。"""
    seen: dict[str, str] = {}

    class ContentAnswerer:
        async def answer_stream(self, message, context=None, history=None):
            seen["context"] = context or ""
            for char in "fake answer":
                yield char

        async def summarize_history(self, history_text: str) -> str:
            return ""

    router = FakeIntentRouter(
        IntentClassification(intent=Intent.RESUME_QUERY)
    )
    backend = BackendClient(
        base_url="http://test", transport=httpx.MockTransport(backend_transport)
    )
    deps = GraphDeps(intent_router=router, answerer=ContentAnswerer(), backend=backend)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="分析我的项目经历",
        attachments=[{"kind": "resume", "resume_id": 1, "filename": "resume.pdf"}],
        action=None,
    )
    result = await graph.ainvoke(state)
    async for _ in result["plan"].text:
        pass

    # 上下文应同时包含完整简历内容与分析摘要（内容感知）
    assert "基于 LangGraph 构建 Agent 平台" in seen["context"]
    assert "该简历分析结果" in seen["context"]


# ===== 无附件时的目标简历解析（设计文档 §26）=====


def _capture_deps(backend_transport, intent: Intent):
    """返回带上下文捕获的 deps 与 seen 字典。"""
    seen: dict[str, str] = {}

    class CaptureAnswerer:
        async def answer_stream(self, message, context=None, history=None):
            seen["message"] = message
            seen["context"] = context or ""
            for char in "fake answer":
                yield char

        async def summarize_history(self, history_text: str) -> str:
            return ""

    router = FakeIntentRouter(IntentClassification(intent=intent))
    backend = BackendClient(
        base_url="http://test", transport=httpx.MockTransport(backend_transport)
    )
    return GraphDeps(intent_router=router, answerer=CaptureAnswerer(), backend=backend), seen


async def test_graph_resume_query_single_resume_auto_targets(backend_transport):
    """无附件但库中仅一份简历时应自动锁定并走内容感知路径。"""
    deps, seen = _capture_deps(backend_transport, Intent.RESUME_QUERY)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="你能看到我简历里的内容吗",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)
    async for _ in result["plan"].text:
        pass

    # 唯一简历（fixture 中 id=1）被锁定，上下文含完整简历内容，无"多份说明"
    assert "基于 LangGraph 构建 Agent 平台" in seen["context"]
    assert "最近上传的" not in seen["context"]


async def test_graph_resume_query_multiple_defaults_to_latest():
    """多份简历且未指定时默认分析最近上传的一份，并在上下文中说明便于用户纠正。"""

    def multi_handler(request: httpx.Request) -> httpx.Response:
        tool = request.url.path.rsplit("/", 1)[-1]
        data = {
            "get_resume_list": [
                {"id": 1, "filename": "old.pdf", "uploadedAt": "2026-01-01T00:00:00"},
                {"id": 2, "filename": "new.pdf", "uploadedAt": "2026-08-01T00:00:00"},
            ],
            "get_resume": {
                "id": 2,
                "filename": "new.pdf",
                "resumeText": "新简历内容",
                "analyzeStatus": "COMPLETED",
            },
            "get_resume_analysis": {
                "overallScore": 80,
                "scoreDetail": {},
                "summary": "",
                "strengths": [],
                "suggestions": [],
            },
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    deps, seen = _capture_deps(multi_handler, Intent.RESUME_QUERY)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="帮我看看简历",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)
    async for _ in result["plan"].text:
        pass

    # 默认锁定最近上传的 new.pdf，且上下文有说明
    assert "新简历内容" in seen["context"]
    assert "最近上传的《new.pdf》" in seen["context"]


async def test_graph_resume_query_filename_hint_wins_over_latest():
    """消息中提到文件名时，优先分析该份而非最近上传的一份。"""

    def named_handler(request: httpx.Request) -> httpx.Response:
        tool = request.url.path.rsplit("/", 1)[-1]
        data = {
            "get_resume_list": [
                {"id": 3, "filename": "yang.pdf", "uploadedAt": "2026-01-01T00:00:00"},
                {"id": 2, "filename": "other.pdf", "uploadedAt": "2026-08-01T00:00:00"},
            ],
            "get_resume": {
                "id": 3,
                "filename": "yang.pdf",
                "resumeText": "yang 简历的内容",
                "analyzeStatus": "COMPLETED",
            },
            "get_resume_analysis": {
                "overallScore": 70,
                "scoreDetail": {},
                "summary": "",
                "strengths": [],
                "suggestions": [],
            },
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    deps, seen = _capture_deps(named_handler, Intent.RESUME_QUERY)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="帮我优化 yang.pdf 这份简历",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)
    async for _ in result["plan"].text:
        pass

    assert "yang 简历的内容" in seen["context"]
    assert "最近上传的" not in seen["context"], "文件名命中时无需多份说明"


# ===== P1-3 Conversation 绑定活动资源 =====


def _make_binding_handler(store: dict):
    """带 activeResumeId 的 context 响应 + 绑定写回追踪（store: {'resume_id': x, 'bound': []}）。"""

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if request.method == "PUT" and path.endswith("/active-resume"):
            import json as _json

            body = _json.loads(request.read().decode())
            store["bound"].append(body.get("resumeId"))
            return httpx.Response(200, json={"code": 200, "data": None, "message": "success"})
        if path.endswith("/context"):
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {
                        "messages": [],
                        "summary": None,
                        "totalCount": 0,
                        "activeResumeId": store.get("resume_id"),
                    },
                    "message": "success",
                },
            )
        tool = path.rsplit("/", 1)[-1]
        data = {
            "get_resume_analysis": {
                "overallScore": 74,
                "scoreDetail": {},
                "summary": "",
                "strengths": [],
                "suggestions": [],
            },
            "get_resume": {
                "id": store.get("resume_id"),
                "filename": "bound.pdf",
                "resumeText": "绑定简历的内容",
                "analyzeStatus": "COMPLETED",
            },
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    return handler


async def test_graph_binds_active_resume_after_targeted_query():
    """定向简历分析后应把该简历绑定为会话活动资源。"""
    store: dict = {"bound": []}
    deps, _ = _capture_deps(_make_binding_handler(store), Intent.RESUME_QUERY)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=5,
        message="看看我的简历",
        attachments=[{"kind": "resume", "resume_id": 8, "filename": "a.pdf"}],
        action=None,
    )
    result = await graph.ainvoke(state)
    async for _ in result["plan"].text:
        pass
    assert store["bound"] == [8]


async def test_graph_resolves_bound_resume_without_attachment():
    """无附件轮次应从会话绑定恢复目标简历（内容感知继续生效）。"""
    store: dict = {"bound": [], "resume_id": 7}
    deps, seen = _capture_deps(_make_binding_handler(store), Intent.RESUME_QUERY)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=5,
        message="再详细讲讲项目经历",
        attachments=[],  # 无附件：依赖上一轮绑定
        action=None,
    )
    result = await graph.ainvoke(state)
    async for _ in result["plan"].text:
        pass

    assert result.get("active_resume_id") == 7
    assert "绑定简历的内容" in seen["context"], "无附件也应走内容感知路径"


# ===== 新上传简历的分析就绪窗口：轮询等待 / 失败快速返回 =====


async def test_graph_analysis_pending_waits_until_ready(monkeypatch):
    """分析前两次未就绪、第三次成功时应轮询等待并最终给出真实解读。"""
    from career_copilot.agent.nodes import business_tools as bt

    monkeypatch.setattr(bt.settings, "analysis_wait_attempts", 5)
    monkeypatch.setattr(bt.settings, "analysis_wait_delay_seconds", 0.01)

    store: dict = {"calls": 0}
    progress: list[str] = []

    def flaky_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/context"):
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {"messages": [], "summary": None, "totalCount": 0},
                    "message": "success",
                },
            )
        tool = path.rsplit("/", 1)[-1]
        if tool == "get_resume_analysis":
            store["calls"] += 1
            if store["calls"] < 3:
                return httpx.Response(
                    200,
                    json={"code": 2008, "data": None, "message": "简历分析结果不存在"},
                )
            analysis = {
                "overallScore": 74,
                "scoreDetail": {},
                "summary": "成熟",
                "strengths": [],
                "suggestions": [],
            }
            return httpx.Response(200, json={"code": 200, "data": analysis, "message": "success"})
        data = {
            "get_resume": {
                "id": 1,
                "filename": "a.pdf",
                "resumeText": "正文",
                "analyzeStatus": "PROCESSING",
            }
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    class CapAnswerer:
        async def answer_stream(self, message, context=None, history=None):
            yield context or ""

        async def summarize_history(self, t):
            return ""

    deps = GraphDeps(
        intent_router=FakeIntentRouter(IntentClassification(intent=Intent.RESUME_QUERY)),
        answerer=CapAnswerer(),
        backend=BackendClient(base_url="http://t", transport=httpx.MockTransport(flaky_handler)),
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="分析简历",
        attachments=[{"kind": "resume", "resume_id": 1, "filename": "a.pdf"}],
        action=None,
    )

    plan_holder: list = []
    async for mode, chunk in graph.astream(state, stream_mode=["custom", "values"]):
        if mode == "custom":
            if chunk["type"] == "tool_progress":
                progress.append(chunk["payload"]["label"])
        else:
            plan_holder.append(chunk)

    # 等待进度事件已发出，且最终给出真实解读而非"后台分析中"
    assert any("正在等待简历分析完成" in p for p in progress)
    final_state = plan_holder[-1]
    context_text = None
    async for piece in final_state["plan"].text:
        context_text = (context_text or "") + piece
    assert "成熟" in context_text
    assert store["calls"] == 3


async def test_graph_analysis_failed_fails_fast(monkeypatch):
    """analyzeStatus=FAILED 时应快速诚实报错（不消耗整段等待窗口）。"""
    import time

    from career_copilot.agent.nodes import business_tools as bt

    monkeypatch.setattr(bt.settings, "analysis_wait_attempts", 12)
    monkeypatch.setattr(bt.settings, "analysis_wait_delay_seconds", 4.0)

    def failed_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/context"):
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {"messages": [], "summary": None, "totalCount": 0},
                    "message": "success",
                },
            )
        tool = path.rsplit("/", 1)[-1]
        if tool == "get_resume_analysis":
            return httpx.Response(
                200,
                json={"code": 2008, "data": None, "message": "不存在"},
            )
        data = {
            "get_resume": {
                "id": 1,
                "filename": "a.pdf",
                "resumeText": "正文",
                "analyzeStatus": "FAILED",
            }
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    class SilentAnswerer:
        async def answer_stream(self, message, context=None, history=None):
            yield ""
        async def summarize_history(self, t):
            return ""

    deps = GraphDeps(
        intent_router=FakeIntentRouter(IntentClassification(intent=Intent.RESUME_QUERY)),
        answerer=SilentAnswerer(),
        backend=BackendClient(base_url="http://t", transport=httpx.MockTransport(failed_handler)),
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="分析",
        attachments=[{"kind": "resume", "resume_id": 1, "filename": "a.pdf"}],
        action=None,
    )

    started = time.monotonic()
    result = await graph.ainvoke(state)
    elapsed = time.monotonic() - started

    assert elapsed < 2, f"FAILED 状态应快速返回，实际 {elapsed:.1f}s"
    text = "".join([c async for c in result["plan"].text])
    assert "重新分析" in text


# ===== 短期记忆：load_history / 滚动摘要 / checkpoint =====


async def test_graph_loads_history_and_passes_to_answerer():
    """携带 conversation_id 时应拉取最近消息注入回答，并透传滚动摘要。"""
    seen: dict[str, str] = {}

    class HistoryAnswerer:
        async def answer_stream(self, message, context=None, history=None):
            seen["history"] = history or ""
            for char in "fake answer":
                yield char

        async def summarize_history(self, history_text: str) -> str:
            return "早期对话摘要：用户咨询 Java 后端实习。"

    def history_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/context"):
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {
                        "messages": [
                            {"role": "USER", "content": "我目标 Java 后端"},
                            {"role": "ASSISTANT", "content": "好的，我们开始准备。"},
                        ],
                        "summary": "早期对话摘要：用户咨询 Java 后端实习。",
                        "totalCount": 4,
                    },
                    "message": "success",
                },
            )
        tool = path.rsplit("/", 1)[-1]
        data = {"get_resume_list": []}.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    router = FakeIntentRouter(
        IntentClassification(intent=Intent.GENERAL_CHAT)
    )
    backend = BackendClient(
        base_url="http://test", transport=httpx.MockTransport(history_handler)
    )
    deps = GraphDeps(intent_router=router, answerer=HistoryAnswerer(), backend=backend)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=5, message="继续", attachments=[], action=None
    )
    result = await graph.ainvoke(state)
    # plan.text 是惰性迭代器：消费后才触发回答器（与 API 层 SSE 流式一致）
    async for _ in result["plan"].text:
        pass

    assert "用户: 我目标 Java 后端" in seen["history"]
    assert "助手: 好的，我们开始准备。" in seen["history"]
    assert "早期对话摘要" in seen["history"]


async def test_graph_triggers_rolling_summary_and_writes_back():
    """历史超出窗口且无摘要时，应生成滚动摘要并写回 Java。"""
    calls: list[tuple[str, str]] = []

    def summary_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/context"):
            messages = [
                {"role": "USER", "content": f"第 {i} 轮问题"}
                for i in range(10)
            ]
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {
                        "messages": messages,
                        "summary": None,
                        "totalCount": 10,
                    },
                    "message": "success",
                },
            )
        if path.endswith("/summary"):
            import json

            calls.append((request.method, json.loads(request.read().decode())["summary"]))
            return httpx.Response(
                200, json={"code": 200, "data": None, "message": "success"}
            )
        return httpx.Response(
            200, json={"code": 200, "data": [], "message": "success"}
        )

    router = FakeIntentRouter(
        IntentClassification(intent=Intent.GENERAL_CHAT)
    )
    backend = BackendClient(
        base_url="http://test", transport=httpx.MockTransport(summary_handler)
    )
    deps = GraphDeps(intent_router=router, answerer=FakeAnswerer(), backend=backend)
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=5, message="继续", attachments=[], action=None
    )
    result = await graph.ainvoke(state)

    # 摘要已生成并写回 Java；注入窗口保留最近 8 条
    assert len(calls) == 1
    assert "早期对话摘要" in calls[0][1]
    assert result.get("history_summary") == "早期对话摘要：用户咨询 Java 后端实习。"
    assert len(result.get("history") or []) == 8


async def test_graph_checkpoint_persists_working_state():
    """启用 checkpoint 后：plan 不被持久化，history 跨轮次恢复。"""
    from langgraph.checkpoint.memory import MemorySaver

    from career_copilot.agent.checkpointer import TRANSIENT_KEYS

    # 用内存版验证剥离逻辑（与 PG 版共用剥离思路，避免测试依赖真实 PG）
    class MemoryCopilotSaver(MemorySaver):
        async def aput(self, config, checkpoint, metadata, new_versions):
            copy = checkpoint.copy()
            copy["channel_values"] = {
                k: v
                for k, v in checkpoint["channel_values"].items()
                if k not in TRANSIENT_KEYS
            }
            return await super().aput(config, copy, metadata, new_versions)

        async def aput_writes(self, config, writes, task_id, task_path=""):
            writes = [(c, v) for c, v in writes if c not in TRANSIENT_KEYS]
            return await super().aput_writes(config, writes, task_id, task_path)

    def history_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/context"):
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {
                        "messages": [
                            {"role": "USER", "content": "我目标 Java 后端"},
                        ],
                        "summary": None,
                        "totalCount": 1,
                    },
                    "message": "success",
                },
            )
        return httpx.Response(
            200, json={"code": 200, "data": [], "message": "success"}
        )

    router = FakeIntentRouter(
        IntentClassification(intent=Intent.GENERAL_CHAT)
    )
    backend = BackendClient(
        base_url="http://test", transport=httpx.MockTransport(history_handler)
    )
    deps = GraphDeps(intent_router=router, answerer=FakeAnswerer(), backend=backend)
    checkpointer = MemoryCopilotSaver()
    graph = build_graph(deps, checkpointer=checkpointer)
    config = {"configurable": {"thread_id": "5"}}

    state1 = build_initial_state(
        conversation_id=5, message="第一轮", attachments=[], action=None
    )
    result1 = await graph.ainvoke(state1, config=config)
    assert result1["plan"] is not None, "内存返回值应保留 plan（供 API 流式）"

    # 第二轮同 thread：检查点恢复上一轮 working state
    state2 = build_initial_state(
        conversation_id=5, message="第二轮", attachments=[], action=None
    )
    result2 = await graph.ainvoke(state2, config=config)

    assert len(result2.get("history") or []) >= 1, "history 应跨轮次恢复"
    assert result2.get("history")[0]["content"] == "我目标 Java 后端"


# ===== P1-4 面试发起 Agent 化 =====


class FakeProposalModel:
    """模拟面试推荐模型的结构化 JSON 输出（不进 Graph 的 answerer 文本流）。"""

    def __init__(self, payload: dict) -> None:
        self._payload = payload

    async def ainvoke(self, messages: list) -> "FakeProposalResult":
        import json

        return FakeProposalResult(json.dumps(self._payload))


class FakeProposalResult:
    def __init__(self, content: str) -> None:
        self.content = content


def _proposal_deps(backend_transport, proposal_payload: dict) -> GraphDeps:
    """带提案模型的 deps：answerer._model 返回预设 JSON 推荐配置。"""
    from career_copilot.agent.nodes.interview_proposal import _derive_proposal
    from career_copilot.agent.nodes.interview_proposal import interview_proposal  # noqa: F401

    class ProposalAnswerer:
        def __init__(self) -> None:
            self._model = FakeProposalModel(proposal_payload)

        async def answer_stream(self, message, context=None, history=None):
            for char in "fake answer":
                yield char

        async def summarize_history(self, history_text: str) -> str:
            return ""

    router = FakeIntentRouter(IntentClassification(intent=Intent.INTERVIEW_CREATE))
    backend = BackendClient(
        base_url="http://test", transport=httpx.MockTransport(backend_transport)
    )
    return GraphDeps(
        intent_router=router, answerer=ProposalAnswerer(), backend=backend
    )


def _proposal_transport():
    def handler(request: httpx.Request) -> httpx.Response:
        tool = request.url.path.rsplit("/", 1)[-1]
        data = {
            "list_skills": [
                {
                    "id": "java-backend",
                    "name": "Java 后端",
                    "categories": [
                        {"key": "JVM", "label": "JVM", "priority": "CORE"},
                        {"key": "REDIS", "label": "Redis", "priority": "CORE"},
                        {"key": "PROJECT", "label": "项目经历", "priority": "ALWAYS_ONE"},
                    ],
                },
                {"id": "frontend", "name": "前端", "categories": []},
            ],
            "get_resume": {
                "id": 1,
                "filename": "resume.pdf",
                "resumeText": "技能：Java、Redis、Spring",
                "analyzeStatus": "COMPLETED",
            },
            "create_interview": {
                "sessionId": "abc123",
                "totalQuestions": 8,
                "status": "CREATED",
            },
        }.get(tool, [])
        return httpx.Response(
            200, json={"code": 200, "data": data, "message": "success"}
        )

    return handler


async def test_graph_interview_create_produces_proposal():
    """INTERVIEW_CREATE 意图 → 面试提案块（含推荐配置与确认动作）。"""
    transport = _proposal_transport()
    deps = _proposal_deps(
        transport,
        {
            "direction": "java-backend",
            "difficulty": "mid",
            "focus": ["JVM", "PROJECT"],
            "summary": "简历项目经历突出，适合深挖 JVM",
        },
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="来一场模拟面试",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)

    plan = result["plan"]
    types = [block.type for block in plan.blocks]
    assert "interview_proposal" in types

    proposal = next(b for b in plan.blocks if b.type == "interview_proposal")
    assert proposal.direction == "java-backend"
    assert proposal.direction_name == "Java 后端"
    assert proposal.difficulty == "mid"
    assert proposal.difficulty_name == "中级"
    assert set(proposal.focus) == {"JVM", "PROJECT"}

    # 提案块应带可确认的创建动作（由前端原样回传）
    choice = next(b for b in plan.blocks if b.type == "choice")
    assert any(opt.action == "START_INTERVIEW" for opt in choice.options)


async def test_graph_interview_create_proposal_fallback_without_resume():
    """无简历时推荐流程不阻断：回落默认配置并产出提案。"""

    def no_resume_handler(request: httpx.Request) -> httpx.Response:
        tool = request.url.path.rsplit("/", 1)[-1]
        if tool == "get_resume":
            return httpx.Response(
                200, json={"code": 1006, "data": None, "message": "简历不存在"}
            )
        data = {
            "list_skills": [
                {
                    "id": "java-backend",
                    "name": "Java 后端",
                    "categories": [{"key": "JVM", "priority": "CORE"}],
                },
            ],
        }.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    deps = _proposal_deps(
        no_resume_handler,
        {
            "direction": "java-backend",
            "difficulty": "mid",
            "focus": [],
            "summary": "按 Java 后端推荐",
        },
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="我要准备 Java 后端面试",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)

    proposal = next(b for b in result["plan"].blocks if b.type == "interview_proposal")
    assert proposal.direction == "java-backend"
    assert proposal.resume_id is None


async def test_graph_create_interview_action_navigates_to_session():
    """CREATE_INTERVIEW action（用户确认后）→ 创建成功 → NavigationBlock 跳转面试页。"""
    transport = _proposal_transport()
    deps = _proposal_deps(transport, {})
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="",
        attachments=[],
        action={
            "type": "ACTION_SELECTED",
            "action": "CREATE_INTERVIEW",
            "payload": {
                "direction": "java-backend",
                "difficulty": "mid",
                "focus": ["JVM"],
                "questionCount": 8,
                "resumeId": 1,
            },
        },
    )
    result = await graph.ainvoke(state)

    plan = result["plan"]
    nav = next((b for b in plan.blocks if b.type == "navigation"), None)
    assert nav is not None, "创建成功应产出 NavigationBlock"
    assert nav.route == "INTERVIEW_SESSION"
    assert nav.params["sessionId"] == "abc123"


async def test_graph_create_interview_action_requires_direction():
    """CREATE_INTERVIEW 缺 direction 时应拒绝创建并提示（不调后端写接口）。"""
    transport = _proposal_transport()
    deps = _proposal_deps(transport, {})
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="",
        attachments=[],
        action={
            "type": "ACTION_SELECTED",
            "action": "CREATE_INTERVIEW",
            "payload": {"difficulty": "mid"},
        },
    )
    result = await graph.ainvoke(state)

    text = "".join([c async for c in result["plan"].text])
    assert "缺少面试方向配置" in text
    assert result["plan"].blocks == []

# ===== profile_query（P3-2 能力画像） =====


async def test_graph_profile_query_produces_skill_profile_block(backend_transport):
    """PROFILE_QUERY → get_skill_profile → SkillProfileBlock + 证据驱动的回答。"""
    deps, router = make_deps(
        IntentClassification(intent=Intent.PROFILE_QUERY), backend_transport
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="我的 JVM 水平怎么样？",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)

    plan = result["plan"]
    block = next((b for b in plan.blocks if b.type == "skill_profile"), None)
    assert block is not None, "画像查询应产出 SkillProfileBlock"
    skills = {s["skill"]: s for s in block.skills}
    assert set(skills) == {"MySQL", "JVM"}
    # 数值来自 Java 聚合器，块内原样透传（LLM 不改写）
    assert skills["JVM"]["score"] == 55
    assert skills["JVM"]["evidenceCount"] == 1
    assert skills["JVM"]["evidences"][0]["sourceId"] == "s1:2"

    text = "".join([c async for c in plan.text])
    assert text, "画像查询应有流式回答"


async def test_graph_profile_query_empty_guides_to_interview(backend_transport):
    """无画像数据时如实说明并引导面试，不产出空画像块。"""
    def empty_profile_transport(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.startswith("/api/agent/conversations"):
            return httpx.Response(200, json={"code": 200, "data": None, "message": "success"})
        tool = path.rsplit("/", 1)[-1]
        data = {"get_skill_profile": {"skills": []}}.get(tool, [])
        return httpx.Response(200, json={"code": 200, "data": data, "message": "success"})

    deps, _ = make_deps(
        IntentClassification(intent=Intent.PROFILE_QUERY), empty_profile_transport
    )
    graph = build_graph(deps)
    state = build_initial_state(
        conversation_id=None,
        message="我水平怎么样",
        attachments=[],
        action=None,
    )
    result = await graph.ainvoke(state)

    plan = result["plan"]
    assert not [b for b in plan.blocks if b.type == "skill_profile"]
    actions = [b for b in plan.blocks if b.type == "action"]
    assert actions and actions[0].route == "INTERVIEW_CREATE"
    text = "".join([c async for c in plan.text])
    assert "画像" in text and "面试" in text
