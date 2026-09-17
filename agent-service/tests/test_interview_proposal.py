"""面试提案的 focus 白名单与画像候选（P3 待收口）。

关键约束：
1. focus 必须真的影响出题——只有**存在于该方向 categories** 的分类才能下发；
   LLM 臆造的分类被拦截后，用画像的确定性候选兜底。
2. 画像候选的优先级是「简历已列但从未考过 > 已考但低分」：没考过的技能信息量最大。
"""

import json
from typing import Any

import httpx

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.nodes.interview_proposal import (
    _derive_proposal,
    _direction_categories,
    _profile_focus_hints,
    _sanitize_focus,
    interview_proposal,
)
from career_copilot.clients.backend import BackendClient
from tests.conftest import make_fake_executor

CATEGORIES = [
    {"key": "JAVA", "label": "Java"},
    {"key": "MYSQL", "label": "MySQL"},
    {"key": "REDIS", "label": "Redis"},
    {"key": "PROJECT", "label": "项目经历"},
]

SKILLS = [
    {"id": "java-backend", "name": "Java 后端", "categories": CATEGORIES},
]


def test_sanitize_focus_matches_key_label_and_substring():
    # key 精确、label 精确、子串（SQL → MySQL）、臆造分类被丢弃
    sanitized = _sanitize_focus(["JAVA", "Redis", "SQL", "不存在的分类"], CATEGORIES)

    assert sanitized == ["JAVA", "REDIS", "MYSQL"]


def test_sanitize_focus_preserves_order_and_caps():
    # 上限 MAX_FOCUS=4：第 5 个命中项被截断，且顺序保持输入顺序
    sanitized = _sanitize_focus(
        ["PROJECT", "redis", "Java", "MYSQL", "spring"], CATEGORIES
    )

    assert sanitized == ["PROJECT", "REDIS", "JAVA", "MYSQL"]


def test_sanitize_focus_with_empty_categories_returns_empty():
    assert _sanitize_focus(["JAVA"], []) == []
    assert _sanitize_focus([], CATEGORIES) == []


def test_direction_categories_reads_selected_direction_only():
    categories = _direction_categories(SKILLS, "java-backend")

    assert [c["key"] for c in categories] == ["JAVA", "MYSQL", "REDIS", "PROJECT"]
    assert _direction_categories(SKILLS, "不存在的方向") == []


def test_profile_focus_hints_prefers_never_tested_then_low_scores():
    profile = {
        "skills": [
            {"skill": "Redis", "score": 55},
            {"skill": "MySQL", "score": 70},  # 已考且不低分 → 不进候选
        ],
        "declaredSkills": [{"skill": "SQL", "resumeId": "7"}],  # 从未考过 → 最优先
    }

    hints = _profile_focus_hints(profile, CATEGORIES)

    # 声明技能在前（SQL → MYSQL），其后才是低分的 Redis
    assert hints == ["MYSQL", "REDIS"]


def test_profile_focus_hints_ignores_unmappable_skill_names():
    # JVM 无法安全映射到本方向分类（不该由确定性代码瞎猜），交给 LLM 语义判断
    profile = {"skills": [{"skill": "JVM", "score": 30}], "declaredSkills": []}

    assert _profile_focus_hints(profile, CATEGORIES) == []


def test_profile_focus_hints_without_profile_or_categories():
    assert _profile_focus_hints({}, CATEGORIES) == []
    assert _profile_focus_hints({"skills": [{"skill": "Redis", "score": 10}]}, []) == []


class _FakeModel:
    """answerer._model 的最小替身：返回预设 JSON。"""

    def __init__(self, payload: dict[str, Any]) -> None:
        self._payload = payload

    async def ainvoke(self, messages):  # noqa: ANN001, ANN202
        class _Result:
            content = json.dumps(self._payload, ensure_ascii=False)

        return _Result()


class _FakeAnswerer:
    def __init__(self, payload: dict[str, Any]) -> None:
        self._model = _FakeModel(payload)


def _deps(payload: dict[str, Any]) -> GraphDeps:
    llm = make_fake_executor(payload)
    return GraphDeps(
        intent_router=None,  # _derive_proposal 不用路由
        answerer=Answerer(llm),
        backend=BackendClient(base_url="http://test", transport=httpx.MockTransport(_noop)),
            llm=make_fake_executor()
    )


def _noop(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json={"code": 200, "data": {}, "message": "success"})


async def test_derive_proposal_whitelists_invented_categories_with_profile_fallback():
    """LLM 臆造的分类被白名单拦掉后，用画像候选兜底而不是下发无效 focus。"""
    payload = {
        "direction": "java-backend",
        "difficulty": "mid",
        "focus": ["JVM", "Elasticsearch"],  # JVM 不是 java-backend 的分类
        "summary": "推荐",
    }
    profile = {"skills": [{"skill": "MySQL", "score": 40}], "declaredSkills": []}

    proposal = await _derive_proposal(
        _deps(payload),
        message="来一场面试",
        skills=SKILLS,
        skills_summary="（方向略）",
        resume_context=None,
        profile=profile,
        profile_summary="（画像略）",
    )

    # JVM / Elasticsearch 均不在分类里 → 落到画像候选（低分 MySQL）
    assert proposal["focus"] == ["MYSQL"]
    assert proposal["direction"] == "java-backend"


async def test_derive_proposal_falls_back_to_default_on_model_error():
    class _BrokenAnswerer:
        _model = None  # 模型未注入

    deps = GraphDeps(
        intent_router=None,
        answerer=Answerer(make_fake_executor("{}")),
        backend=BackendClient(base_url="http://test", transport=httpx.MockTransport(_noop)),
            llm=make_fake_executor()
    )
    profile = {"declaredSkills": [{"skill": "Redis", "resumeId": "7"}]}

    proposal = await _derive_proposal(
        deps,
        message="面试",
        skills=SKILLS,
        skills_summary="",
        resume_context=None,
        profile=profile,
        profile_summary="",
    )

    assert proposal["direction"] == "java-backend"
    assert proposal["difficulty"] == "mid"
    # 默认方向的画像候选仍然生效：简历已列未考的 Redis 优先
    assert proposal["focus"] == ["REDIS"]


# ---------------------------------------------------------------------------
# 简历取数（P4Q-1）：读「已确认的结构化版本」，读不到时给出可见原因
# ---------------------------------------------------------------------------

ACTIVE_VERSION: dict[str, Any] = {
    "id": 3,
    "resumeId": 7,
    "version": 2,
    "confirmationStatus": "ACTIVE",
    "content": {
        "basicInfo": {"jobIntention": "Java 后端实习"},
        "education": [],
        "experience": [
            {
                "company": "某公司",
                "position": "后端实习生",
                "startDate": "2025-07",
                "endDate": "2025-10",
                "bullets": ["个人负责消费端幂等改造"],
            }
        ],
        "projects": [
            {
                "name": "订单系统",
                "role": "开发",
                "startDate": "2025-03",
                "endDate": "2025-06",
                "techStack": "Java / Kafka",
                "bullets": ["Kafka 重平衡排查"],
            }
        ],
        "skills": [{"category": "语言", "content": "Java（熟练）"}],
        "customSections": [],
    },
}


class _RecordingAnswerer:
    """记录最后一次调用消息的 answerer 替身（用于断言注入的上下文）。"""

    def __init__(self, payload: dict[str, Any]) -> None:
        self.model = _RecordingModel(payload)
        self._model = self.model


class _RecordingModel:
    def __init__(self, payload: dict[str, Any]) -> None:
        self._payload = payload
        self.messages: list[Any] = []

    async def ainvoke(self, messages):  # noqa: ANN001, ANN202
        self.messages = list(messages)

        class _Result:
            content = json.dumps(self._payload, ensure_ascii=False)

        return _Result()


def _ok_transport() -> httpx.MockTransport:
    """只声明本流程应当调用的 Tool；调用了别的（如 get_resume）会直接失败。"""

    def handler(request: httpx.Request) -> httpx.Response:
        path = str(request.url)
        payloads = {
            "list_skills": SKILLS,
            "get_skill_profile": {"skills": [], "declaredSkills": []},
            "get_resume_version": ACTIVE_VERSION,
        }
        for tool, payload in payloads.items():
            if path.endswith(f"/api/agent/tools/{tool}"):
                return httpx.Response(
                    200,
                    json={"code": 200, "data": {"tool": tool, "data": payload}, "message": "ok"},
                )
        raise AssertionError(f"未预期的后端调用: {request.url}")

    return httpx.MockTransport(handler)


def _failing_version_transport(code: int, message: str) -> httpx.MockTransport:
    """简历版本读取失败：前期 Tool 正常，get_resume_version 返回业务错误。"""

    def handler(request: httpx.Request) -> httpx.Response:
        path = str(request.url)
        if path.endswith("/api/agent/tools/list_skills"):
            return httpx.Response(
                200, json={"code": 200, "data": {"tool": "list_skills", "data": SKILLS},
                           "message": "ok"})
        if path.endswith("/api/agent/tools/get_skill_profile"):
            return httpx.Response(
                200, json={"code": 200,
                           "data": {"tool": "get_skill_profile",
                                    "data": {"skills": [], "declaredSkills": []}},
                           "message": "ok"})
        if path.endswith("/api/agent/tools/get_resume_version"):
            return httpx.Response(200, json={"code": code, "data": None, "message": message})
        raise AssertionError(f"未预期的后端调用: {request.url}")

    return httpx.MockTransport(handler)


async def _collect(stream) -> str:  # noqa: ANN001
    return "".join([chunk async for chunk in stream])


async def test_proposal_reads_confirmed_resume_version():
    """提案取数走「已确认的结构化版本」，并把确认过的经历事实注入推荐上下文。"""
    llm = make_fake_executor(
        {"direction": "java-backend", "difficulty": "mid", "focus": ["JAVA"], "summary": "推荐"}
    )
    deps = GraphDeps(
        intent_router=None,
        answerer=Answerer(llm),
        backend=BackendClient(base_url="http://test", transport=_ok_transport()),
        llm=llm,
    )
    try:
        result = await interview_proposal({"active_resume_id": 7, "message": "来一场面试"}, deps)
    finally:
        await deps.backend.aclose()

    blocks = result["plan"].blocks
    assert blocks, "应当产出面试提案块"
    assert blocks[0].type == "interview_proposal"
    prompt = str(llm.model.messages[-1].content)
    assert "消费端幂等改造" in prompt
    assert "Java / Kafka" in prompt


async def test_proposal_reports_visible_reason_when_resume_unavailable():
    """明确指定了简历却读不到：给出可见原因与下一步，**不静默变成通用面试**。"""
    answerer = _RecordingAnswerer(
        {"direction": "java-backend", "difficulty": "mid", "focus": [], "summary": "推荐"}
    )
    transport = _failing_version_transport(
        12005, "简历还没有已确认的结构化版本，请先在简历库完成解析确认"
    )
    deps = GraphDeps(
        intent_router=None,
        answerer=answerer,
        backend=BackendClient(base_url="http://test", transport=transport), llm=make_fake_executor()
    )
    try:
        result = await interview_proposal(
            {"active_resume_id": 7, "message": "用我的简历面一场"}, deps
        )
    finally:
        await deps.backend.aclose()

    # 不产出提案块：否则用户会以为即将面的是自己的简历
    assert result["plan"].blocks == []
    text = await _collect(result["plan"].text)
    assert "还没有已确认的结构化版本" in text
    assert "通用面试" in text
    # 也不该再花一次模型调用去推荐一份"看不到简历"的配置
    assert answerer.model.messages == []
