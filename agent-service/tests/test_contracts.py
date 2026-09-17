"""Agent Tool 契约测试（ARCH-1：契约单一事实源）。

契约两侧各看住一半：
- Java 侧（`AgentToolContractTest`）比对「现时导出结果 vs docs/contracts/agent-tools.json」；
- 本文件比对「Python 包内副本 vs 该 golden 文件」，并验证调用前校验真的拦得住非法入参。

这样「Java 加了参数、Python 没传」与「Python 传了 Java 不认」都会在 CI 暴露，
而不是等某个功能静默失效（`focusCategories` 就曾这样变成纯装饰）。
"""

import json
import re
from pathlib import Path

import httpx
import pytest

from career_copilot import contracts
from career_copilot.clients.backend import BackendClient, BusinessToolError

#: Java 导出的 golden 契约（仓库根 docs 下）
GOLDEN_FILE = Path(__file__).resolve().parents[2] / "docs" / "contracts" / "agent-tools.json"

#: BackendClient 里直接写死的 Tool 名（call_tool("xxx"）
_LITERAL_TOOL_CALL = re.compile(r'call_tool\(\s*"([a-z_]+)"')


def test_python_copy_matches_golden_contract():
    """包内副本必须与 Java 导出的 golden 文件逐字节一致。"""
    assert GOLDEN_FILE.exists(), "缺少 docs/contracts/agent-tools.json（由 Java 导出）"
    golden = json.loads(GOLDEN_FILE.read_text(encoding="utf-8"))
    bundled = json.loads(contracts.CONTRACT_FILE.read_text(encoding="utf-8"))

    assert bundled == golden, (
        "Python 契约副本已过期，请同步：\n"
        "  cp docs/contracts/agent-tools.json "
        "agent-service/src/career_copilot/contracts/agent-tools.json"
    )


def test_literal_tool_calls_exist_in_contract():
    """代码里写死的 Tool 名必须都在契约中——契约是唯一事实源。"""
    backend_source = (
        Path(contracts.__file__).resolve().parents[1] / "clients" / "backend.py"
    ).read_text(encoding="utf-8")
    called = set(_LITERAL_TOOL_CALL.findall(backend_source))

    assert called, "没有扫描到任何 Tool 调用，检查正则是否失效"
    unknown = sorted(called - set(contracts.tool_names()))
    assert not unknown, f"这些 Tool 不在契约中: {unknown}"


def test_contract_covers_tools_used_by_agent():
    """Agent 实际会调用的 Tool 都在契约里（抽样关键能力，避免整份漏导出）。"""
    expected = {
        "get_resume",
        "get_resume_version",
        "get_skill_profile",
        "list_skills",
        "create_interview",
        "search_knowledge",
        "apply_resume_patches",
        "get_job",
    }
    assert expected <= set(contracts.tool_names())


@pytest.mark.asyncio
async def test_unknown_tool_is_rejected_before_http():
    """契约里没有的 Tool 直接拒绝，不必打后端。"""

    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("未知 Tool 不应发出请求")

    client = BackendClient(base_url="http://test", transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(BusinessToolError) as exc_info:
            await client.call_tool("no_such_tool")
        assert exc_info.value.code == 12002
        assert "契约中没有这个 Tool" in str(exc_info.value)
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tool", "arguments", "hint"),
    [
        # 未知参数：静默忽略会让「调用方以为传了、服务端其实没用」长期隐身
        ("get_resume", {"resumeId": 1, "resume_id": 2}, "resume_id"),
        # 类型错误
        ("get_resume", {"resumeId": "abc"}, "类型错误"),
        # 缺必填
        ("search_knowledge", {"knowledgeBaseIds": [1]}, "question"),
        # 空数组（@NotEmpty → minItems）
        ("search_knowledge", {"knowledgeBaseIds": [], "question": "JVM"}, "knowledgeBaseIds"),
        # 超出范围
        ("create_interview", {"skillId": "java-backend", "questionCount": 99}, "范围"),
        # 必填为空串（@NotBlank → minLength）
        ("create_interview", {"skillId": ""}, "skillId"),
    ],
)
async def test_invalid_arguments_are_rejected_before_http(tool, arguments, hint):
    """非法入参在发请求之前就被拒，且错误信息点名字段。"""

    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("参数不合法时不应发出请求")

    client = BackendClient(base_url="http://test", transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(BusinessToolError) as exc_info:
            await client.call_tool(tool, arguments)
        assert exc_info.value.code == 12002
        assert hint in str(exc_info.value)
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_create_interview_sends_request_id_and_focus():
    """requestId / focusCategories 必须真的出现在请求体里（参数存在就要真生效）。"""
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["arguments"] = json.loads(request.content).get("arguments", {})
        return httpx.Response(
            200,
            json={"code": 200, "data": {"sessionId": "s1"}, "message": "success"},
        )

    client = BackendClient(base_url="http://test", transport=httpx.MockTransport(handler))
    try:
        await client.create_interview(
            "java-backend",
            "mid",
            request_id="confirm-abc",
            focus_categories=["JVM"],
        )
        assert captured["arguments"]["requestId"] == "confirm-abc"
        assert captured["arguments"]["focusCategories"] == ["JVM"]
    finally:
        await client.aclose()
