"""BackendClient 测试：验证统一入口调用、参数透传与错误映射。"""

import httpx
import pytest

from career_copilot.clients.backend import BackendClient, BusinessToolError


@pytest.mark.asyncio
async def test_call_tool_unwraps_result(mock_backend_transport):
    """正常调用应解包 Result 与 ToolResponse 双层信封并返回业务数据。"""
    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(mock_backend_transport),
    )
    try:
        data = await client.call_tool("get_resume_list")
        assert data == [{"id": 1, "filename": "resume.pdf", "latestScore": 82}]
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_call_tool_raises_business_error():
    """业务失败（code != 200）应转换为不可重试的 BusinessToolError。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"code": 12001, "data": None, "message": "未知 Tool: x"}
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(BusinessToolError) as exc_info:
            await client.call_tool("x")
        assert exc_info.value.code == 12001
        assert exc_info.value.retryable is False
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_call_tool_raises_on_network_error():
    """网络异常应转换为可重试的 BusinessToolError。"""

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(BusinessToolError) as exc_info:
            await client.call_tool("get_resume_list")
        assert exc_info.value.retryable is True
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_get_agent_llm_config():
    """agent-config 应解包 Result 并返回连接配置。"""
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {
                    "providerId": "dashscope",
                    "baseUrl": "https://api.example.com/v1",
                    "model": "qwen3.5-flash",
                    "apiKey": "secret",
                },
                "message": "success",
            },
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        config = await client.get_agent_llm_config()
        assert config["providerId"] == "dashscope"
        assert config["model"] == "qwen3.5-flash"
        assert captured["url"].endswith("/api/llm-provider/agent-config")
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_get_agent_llm_config_raises_on_business_error():
    """agent-config 业务失败应转为 BusinessToolError。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"code": 11008, "data": None, "message": "模块不存在"}
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(BusinessToolError) as exc_info:
            await client.get_agent_llm_config()
        assert exc_info.value.code == 11008
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_search_knowledge_passes_arguments():
    """search_knowledge 应透传知识库 ID 列表与问题参数。"""
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["json"] = request.content
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {"answer": "JVM 是 Java 虚拟机。"},
                "message": "success",
            },
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await client.search_knowledge("JVM 是什么", [1, 2])
        assert result["answer"] == "JVM 是 Java 虚拟机。"
    finally:
        await client.aclose()