"""Copilot 闭环：一句自然语言串起画像读取 → 提案 → 创建 → 面试 → 指定场次复盘（P5-3）。

与其它测试的分工：这里不重复验证单个节点，而是验证**串起来之后仍然成立**——
上一轮的建议必须能变成下一轮可执行的入口，创建出来的会话 id 必须能带着去复盘，
中途某一环失败不能把整条链路说成成功。
"""

from typing import Any

import httpx

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.nodes.execute_action import execute_action
from career_copilot.agent.nodes.interview_proposal import interview_proposal
from career_copilot.agent.nodes.profile_query import profile_query
from career_copilot.clients.backend import BackendClient
from tests.conftest import executor_for

SKILLS = [
    {
        "id": "java-backend",
        "name": "Java 后端",
        "categories": [
            {"key": "JAVA", "label": "Java"},
            {"key": "REDIS", "label": "Redis"},
        ],
    }
]

# 画像：Java 低分（58），Redis 只有简历声明
PROFILE: dict[str, Any] = {
    "skills": [
        {
            "skill": "Java",
            "score": 58,
            "evidenceCount": 2,
            "updatedAt": "2026-09-15T10:00:00",
            "evidences": [
                {
                    "sourceType": "INTERVIEW_TURN",
                    "sourceId": "sess-a:q1",
                    "score": 58,
                    "occurredAt": "2026-09-15T10:00:00",
                }
            ],
        }
    ],
    "declaredSkills": [{"skill": "Redis", "resumeId": "3", "occurredAt": "2026-09-01T08:00:00"}],
}

IMPACT: dict[str, Any] = {
    "sessionId": "sess-a",
    "skills": [{"skill": "Java", "beforeScore": 55, "afterScore": 58, "delta": 3}],
}


class _EchoModel:
    """把收到的提示原样吐出：断言「喂进去了什么」。"""

    def bind(self, **kwargs: Any) -> "_EchoModel":  # noqa: ANN401
        return self

    async def ainvoke(self, messages: list) -> Any:  # noqa: ANN401
        from tests.conftest import FakeChatResult

        return FakeChatResult("".join(str(getattr(m, "content", "")) for m in messages))

    async def astream(self, messages: list) -> Any:  # noqa: ANN401
        from tests.conftest import FakeChatResult

        for message in messages:
            yield FakeChatResult(str(getattr(message, "content", "")))


def _transport() -> httpx.MockTransport:
    """一个会话里需要的所有 Java 调用：画像 / 差分 / 技能方向 / 提案 / 创建 / 复盘。"""

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("get_skill_profile"):
            body: Any = {"code": 200, "data": {"tool": path, "data": PROFILE}, "message": "ok"}
        elif path.endswith("list_skills"):
            body = {"code": 200, "data": {"tool": path, "data": SKILLS}, "message": "ok"}
        elif path.endswith("/profile-impact"):
            body = {"code": 200, "data": IMPACT, "message": "ok"}
        elif path.endswith("/details"):
            body = {
                "code": 200,
                "data": {
                    "sessionId": "sess-new",
                    "overallScore": 72,
                    "strengths": ["基础扎实"],
                    "improvements": ["补充细节"],
                    "answers": [{"category": "Java", "score": 72, "feedback": "不错"}],
                },
                "message": "ok",
            }
        elif path.endswith("create_interview"):
            session = {"sessionId": "sess-new", "totalQuestions": 6, "skillId": "java-backend"}
            body = {"code": 200, "data": {"tool": path, "data": session}, "message": "ok"}
        else:
            raise AssertionError(f"未预期的调用: {path}")
        return httpx.Response(200, json=body)

    return httpx.MockTransport(handler)


def _echo_deps(transport: httpx.MockTransport) -> GraphDeps:
    """回答用回声模型：渲染出的文本就是「喂进模型的上下文」。"""
    llm = executor_for(_EchoModel())
    return GraphDeps(
        intent_router=None,
        answerer=Answerer(llm),
        backend=BackendClient(base_url="http://test", transport=transport),
        llm=llm,
    )


def _draft_deps(transport: httpx.MockTransport, draft: dict[str, Any]) -> GraphDeps:
    """结构化提案用给定草稿：走 executor 的 JSON 解析路径（与真实调用同一条）。"""
    from tests.conftest import make_fake_executor

    llm = make_fake_executor(draft)
    return GraphDeps(
        intent_router=None,
        answerer=Answerer(llm),
        backend=BackendClient(base_url="http://test", transport=transport),
        llm=llm,
    )


async def _collect(plan: Any) -> str:  # noqa: ANN401
    return "".join([chunk async for chunk in plan.text])


async def test_profile_read_ends_in_executable_next_step():
    """第一轮：读画像 → 回答里给出可执行方向，且区分已验证与仅声明。"""
    deps = _echo_deps(_transport())
    try:
        result = await profile_query({"message": "我最近复习得怎么样？"}, deps)
        rendered = await _collect(result["plan"])
    finally:
        await deps.backend.aclose()

    assert "Java: 58 分" in rendered
    assert "仅简历声明、未验证的技能" in rendered
    assert "可优先补强的方向" in rendered and "Java（58 分，待提升）" in rendered


async def test_next_turn_turns_advice_into_proposal():
    """第二轮：用户接着用自然语言点名补强 → 提案把该技能放进重点与必要覆盖。

    模型这次**没有**给 focus：兜底必须由画像候选决定，否则「建议补强 Java」到了下一轮就丢了。
    """
    deps = _draft_deps(
        _transport(),
        {"direction": "java-backend", "difficulty": "mid", "focus": [], "summary": "推荐"},
    )
    try:
        result = await interview_proposal(
            {"message": "那就针对 Java 来一场", "action": None}, deps
        )
    finally:
        await deps.backend.aclose()

    block = result["plan"].blocks[0]
    # 画像候选的顺序是「简历已列未考」优先，低分技能紧随其后——两者都要进重点
    assert "JAVA" in block.focus and "REDIS" in block.focus
    assert "JAVA" in block.required_topics
    # 依据解释**首要重点**（这里是没有证据的 Redis）：从「建议补强」到「为什么是它」不断链
    assert any("Redis" in reason for reason in block.reasons)
    assert not any("分" in reason for reason in block.reasons), "未验证技能不得出现分数表述"


async def test_created_session_id_can_be_carried_to_review():
    """第三轮：用创建得到的 sessionId 复盘——闭环的最后一环要落在具体场次上。"""
    deps = _echo_deps(_transport())
    try:
        result = await execute_action(
            {
                "action": {
                    "type": "ACTION_SELECTED",
                    "action": "CREATE_INTERVIEW",
                    "payload": {"direction": "java-backend", "difficulty": "mid"},
                }
            },
            deps,
        )
        session_id = None
        for block in result["plan"].blocks or []:
            if getattr(block, "type", None) == "interview_session":
                session_id = block.session_id
        assert session_id == "sess-new", "创建后要内嵌会话块，复盘才有 id 可带"

        review = await execute_action(
            {
                "action": {
                    "type": "ACTION_SELECTED",
                    "action": "REVIEW_INTERVIEW",
                    "payload": {"sessionId": session_id},
                }
            },
            deps,
        )
        rendered = await _collect(review["plan"])
    finally:
        await deps.backend.aclose()

    assert "72" in rendered and "基础扎实" in rendered


async def test_review_without_session_id_guides_instead_of_guessing():
    """闭环缺参数时不猜：明确要求指定场次，而不是复盘一场随机的面试。"""
    deps = _echo_deps(_transport())
    try:
        result = await execute_action(
            {"action": {"type": "ACTION_SELECTED", "action": "REVIEW_INTERVIEW", "payload": {}}},
            deps,
        )
        rendered = await _collect(result["plan"])
    finally:
        await deps.backend.aclose()

    assert "缺少面试信息" in rendered
