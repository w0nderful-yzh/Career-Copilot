"""画像驱动建议（P4-6b / P6-3）。

两个验收点：
1. 画像低分项能**一键发起定向提案**，且提案里带**推荐依据**（来自画像的真实事实，
   不是模型措辞）；用户指定的重点技能真的进了「重点 + 必要覆盖」。
2. Copilot 建议消费**画像差分与 Evidence**，并区分「已验证」与「仅声明未验证」——
   没有证据时不宣称水平或提升。
"""

from typing import Any

import httpx

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.nodes.interview_proposal import interview_proposal
from career_copilot.agent.nodes.profile_query import profile_query
from career_copilot.clients.backend import BackendClient
from career_copilot.tools import (
    latest_interview_session_id,
    profile_reasons_for,
    summarize_profile_advice,
)
from tests.conftest import make_fake_executor

CATEGORIES = [
    {"key": "JAVA", "label": "Java"},
    {"key": "MYSQL", "label": "MySQL"},
    {"key": "REDIS", "label": "Redis"},
]

SKILLS = [{"id": "java-backend", "name": "Java 后端", "categories": CATEGORIES}]

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
                    "score": 60,
                    "occurredAt": "2026-09-15T10:00:00",
                },
                {
                    "sourceType": "INTERVIEW_TURN",
                    "sourceId": "sess-b:q3",
                    "score": 56,
                    "occurredAt": "2026-09-10T09:00:00",
                },
            ],
        },
        {"skill": "MySQL", "score": 78, "evidenceCount": 1, "evidences": []},
    ],
    "declaredSkills": [{"skill": "Redis", "resumeId": "3", "occurredAt": "2026-09-01T08:00:00"}],
}


def test_profile_reasons_state_only_facts():
    """依据只说画像里存在的事实：分数、证据条数、最近考察时间。"""
    reasons = profile_reasons_for(PROFILE, "Java")

    assert any("58 分" in reason and "2 条面试证据" in reason for reason in reasons)
    assert any("2026-09-15" in reason for reason in reasons)


def test_profile_reasons_mark_declared_only_skill():
    """只有简历声明的技能：明确说「还没有任何面试证据」，不暗示水平。"""
    reasons = profile_reasons_for(PROFILE, "Redis")

    assert reasons[0] == "Redis 只出现在简历里，还没有任何面试证据"
    assert all("分" not in reason for reason in reasons), "未验证的技能不得出现任何分数表述"


def test_profile_reasons_never_invent_without_data():
    """画像里没有这个技能、也没有声明：只说明是用户指定，不编「你比较弱」。"""
    reasons = profile_reasons_for(PROFILE, "Kafka")

    assert reasons[0] == "按你的要求优先考察 Kafka"
    assert all("弱" not in reason and "分" not in reason for reason in reasons)
    assert profile_reasons_for({"skills": [], "declaredSkills": []}, None) == []


def test_latest_interview_session_id_picks_newest_evidence():
    assert latest_interview_session_id(PROFILE) == "sess-a"
    assert latest_interview_session_id({"skills": []}) is None


def test_advice_separates_verified_from_declared():
    """已验证 vs 仅声明必须分开说；没有基线时明确不谈涨幅。"""
    impact = {
        "skills": [
            {"skill": "Java", "beforeScore": 55, "afterScore": 58, "delta": 3},
            {"skill": "Kafka", "beforeScore": None, "afterScore": 61, "delta": 0},
        ]
    }

    text = summarize_profile_advice(PROFILE, impact)

    assert "已评分技能" in text and "Java: 58 分" in text
    assert "仅简历声明、未验证的技能" in text and "Redis" in text
    assert "55 → 58（上升 3）" in text
    assert "无历史基线，不谈涨幅" in text
    assert "可优先补强的方向" in text and "Java（58 分，待提升）" in text


def test_advice_without_any_data_says_so():
    text = summarize_profile_advice({"skills": [], "declaredSkills": []}, None)

    assert "还没有任何画像数据" in text


def _transport(
    profile: dict[str, Any], impact: dict[str, Any] | None = None
) -> httpx.MockTransport:
    """Java 侧两种信封：Agent Tool 走 ToolResponse{tool,data}，会话 REST 直接是 Result.data。"""

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("list_skills"):
            body: Any = {"code": 200, "data": {"tool": path, "data": SKILLS}, "message": "ok"}
        elif path.endswith("get_skill_profile"):
            body = {"code": 200, "data": {"tool": path, "data": profile}, "message": "ok"}
        elif path.endswith("/profile-impact"):
            body = {
                "code": 200,
                "data": impact or {"sessionId": "sess-a", "skills": []},
                "message": "ok",
            }
        else:
            raise AssertionError(f"未预期的调用: {path}")
        return httpx.Response(200, json=body)

    return httpx.MockTransport(handler)


class _EchoModel:
    """把收到的提示原样吐出的模型：断言「喂进去了什么」用（固定文本的 fake 断言不了上下文）。"""

    def bind(self, **kwargs: Any) -> "_EchoModel":  # noqa: ANN401 - 与真实模型接口对齐
        return self

    async def ainvoke(self, messages: list) -> Any:  # noqa: ANN401
        from tests.conftest import FakeChatResult

        return FakeChatResult("".join(str(getattr(m, "content", "")) for m in messages))

    async def astream(self, messages: list) -> Any:  # noqa: ANN401
        from tests.conftest import FakeChatResult

        for message in messages:
            yield FakeChatResult(str(getattr(message, "content", "")))


def _deps(transport: httpx.MockTransport, answer: Any = "推荐") -> GraphDeps:
    llm = make_fake_executor(answer)
    return GraphDeps(
        intent_router=None,
        answerer=Answerer(llm),
        backend=BackendClient(base_url="http://test", transport=transport),
        llm=llm,
    )


def _echo_deps(transport: httpx.MockTransport) -> GraphDeps:
    """用回声模型：回答流即「喂进模型的上下文」，便于断言建议素材真的进了 Prompt。"""
    from tests.conftest import executor_for

    llm = executor_for(_EchoModel())
    return GraphDeps(
        intent_router=None,
        answerer=Answerer(llm),
        backend=BackendClient(base_url="http://test", transport=transport),
        llm=llm,
    )


async def test_focus_skill_from_profile_becomes_focus_and_required_topic():
    """一键定向：用户指定的技能必须进「重点 + 必要覆盖」，并给出依据。"""
    deps = _deps(
        _transport(PROFILE),
        {
            "direction": "java-backend",
            "difficulty": "mid",
            "focus": ["MYSQL"],
            "required_topics": ["MYSQL"],
            "summary": "推荐",
        },
    )
    state = {
        "message": "针对「Java」来一场定向面试",
        "action": {
            "type": "ACTION_SELECTED",
            "action": "START_INTERVIEW",
            "payload": {"focusSkill": "Java"},
        },
    }
    try:
        result = await interview_proposal(state, deps)
    finally:
        await deps.backend.aclose()

    block = result["plan"].blocks[0]
    assert block.focus[0] == "JAVA", "用户指定的技能要置顶，优先于模型推荐"
    assert block.required_topics[0] == "JAVA", "定向面试要把它列为必要覆盖，Java 才会硬性考察"
    assert any("58 分" in reason for reason in block.reasons)


async def test_focus_skill_outside_direction_is_stated_not_silently_replaced():
    """指定的技能不在本方向分类里：如实说明，不假装考得到。"""
    deps = _deps(
        _transport(PROFILE),
        {"direction": "java-backend", "difficulty": "mid", "focus": ["JAVA"], "summary": "推荐"},
    )
    state = {
        "message": "针对「CSS」来一场定向面试",
        "action": {"type": "ACTION_SELECTED", "action": "START_INTERVIEW",
                   "payload": {"focusSkill": "CSS"}},
    }
    try:
        result = await interview_proposal(state, deps)
    finally:
        await deps.backend.aclose()

    block = result["plan"].blocks[0]
    assert "CSS" not in block.focus
    assert any("不在推荐方向的考察分类里" in reason for reason in block.reasons)


async def test_profile_query_uses_impact_for_next_steps():
    """Copilot 建议：差分进入上下文，且明确「声明 ≠ 已验证」。"""
    impact = {
        "sessionId": "sess-a",
        "skills": [{"skill": "Java", "beforeScore": 50, "afterScore": 58, "delta": 8}],
    }
    deps = _echo_deps(_transport(PROFILE, impact))
    try:
        result = await profile_query({"message": "我该练什么？"}, deps)
        rendered = "".join([chunk async for chunk in result["plan"].text])
    finally:
        await deps.backend.aclose()

    assert "50 → 58（上升 8）" in rendered
    assert "不要当成水平依据" in rendered
    assert "可优先补强的方向" in rendered
    assert result["plan"].blocks[0].type == "skill_profile"


async def test_profile_query_degrades_when_impact_unavailable():
    """差分读不到时退化为画像事实，不阻断回答，也不编造涨幅。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/profile-impact"):
            return httpx.Response(200, json={"code": 500, "message": "boom", "data": None})
        data = SKILLS if request.url.path.endswith("list_skills") else PROFILE
        return httpx.Response(
            200, json={"code": 200, "data": {"tool": "x", "data": data}, "message": "ok"}
        )

    deps = _echo_deps(httpx.MockTransport(handler))
    try:
        result = await profile_query({"message": "我该练什么？"}, deps)
        rendered = "".join([chunk async for chunk in result["plan"].text])
    finally:
        await deps.backend.aclose()

    assert "Java: 58 分" in rendered
    assert "最近一场面试带来的变化" not in rendered
