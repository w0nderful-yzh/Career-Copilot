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

@pytest.mark.asyncio
async def test_get_resume_passes_max_chars():
    """get_resume 应透传 resumeId 与可选 maxChars 参数。"""
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        captured["arguments"] = json.loads(request.content).get("arguments", {})
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {
                    "id": 1,
                    "filename": "resume.pdf",
                    "resumeText": "姓名：张三",
                    "analyzeStatus": "COMPLETED",
                },
                "message": "success",
            },
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await client.get_resume(1, max_chars=8000)
        assert result["filename"] == "resume.pdf"
        assert result["resumeText"] == "姓名：张三"
        assert captured["arguments"] == {"resumeId": 1, "maxChars": 8000}

        await client.get_resume(1)
        assert captured["arguments"] == {"resumeId": 1}
    finally:
        await client.aclose()

@pytest.mark.asyncio
async def test_create_conversation():
    """创建会话应 POST /api/agent/conversations 并返回会话项。"""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {"id": 3, "title": "新对话", "messageCount": 0},
                "message": "success",
            },
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        conversation = await client.create_conversation()
        assert conversation["id"] == 3
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_save_conversation_messages():
    """保存消息应透传 role/content/blocks 到会话消息端点。"""
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["json"] = request.read().decode()
        return httpx.Response(200, json={"code": 200, "data": None, "message": "success"})

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        await client.save_conversation_messages(
            7,
            [
                {"role": "USER", "content": "你好", "blocks": None},
                {
                    "role": "ASSISTANT",
                    "content": "你好呀",
                    "blocks": '[{"type": "action", "route": "SETTINGS"}]',
                },
            ],
        )
        assert captured["url"].endswith("/api/agent/conversations/7/messages")
        assert '"role":"USER"' in captured["json"]
        assert '\\"type\\": \\"action\\"' in captured["json"]
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_save_conversation_messages_failure_raises():
    """保存消息业务失败应抛 BusinessToolError。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"code": 13001, "data": None, "message": "对话不存在"}
        )

    client = BackendClient(
        base_url="http://test",
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(BusinessToolError) as exc_info:
            await client.save_conversation_messages(999, [
                {"role": "USER", "content": "hi", "blocks": None}
            ])
        assert exc_info.value.code == 13001
    finally:
        await client.aclose()
