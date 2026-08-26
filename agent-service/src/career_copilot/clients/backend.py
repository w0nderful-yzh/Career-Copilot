"""Java Backend HTTP 客户端。

所有对 Spring Boot 业务后端的调用都集中在本类，避免在 Tool / Agent 层散落 httpx 请求。
Agent Service 不直接访问业务数据库，业务数据一律经由 Java 的 /api/agent/tools 统一入口获取。
"""

from typing import Any

import httpx


class BusinessToolError(Exception):
    """Java 后端业务错误，转换为 Agent 可理解的结构化错误。

    code 对应 Java ErrorCode；retryable 表示网络类瞬时错误（可重试），
    业务错误（如资源不存在）不应重试。
    """

    def __init__(self, code: int, message: str, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


class BackendClient:
    """复用长生命周期 AsyncClient，避免每次 Tool 调用重建连接池。"""

    def __init__(
        self,
        base_url: str,
        timeout: float = 30.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        # transport 仅测试注入 MockTransport 使用，生产环境为 None
        self._client = httpx.AsyncClient(
            base_url=base_url, timeout=timeout, transport=transport
        )

    async def call_tool(self, tool: str, arguments: dict[str, Any] | None = None) -> Any:
        """调用 Java Agent Tool 统一入口并解包 Result 信封。

        Java 侧约定：HTTP 200 + Result{code, message, data}，
        code != 200 表示业务失败，需转为 BusinessToolError。
        工具响应 data 为 ToolResponse{tool, data}，需再解包内层业务数据。
        """
        payload: dict[str, Any] = {"arguments": arguments or {}}
        try:
            response = await self._client.post(f"/api/agent/tools/{tool}", json=payload)
        except httpx.HTTPError as exc:
            # 网络/连接类错误属于瞬时错误，允许上层有限重试
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc

        try:
            body = response.json()
        except ValueError as exc:
            raise BusinessToolError(500, "后端返回非 JSON 响应", retryable=True) from exc

        if body.get("code") != 200:
            # 业务失败（如资源不存在、参数错误），不可重试
            raise BusinessToolError(
                code=body.get("code", -1),
                message=body.get("message", "后端业务错误"),
            )
        data = body.get("data")
        # 解包 ToolResponse 信封：{tool: str, data: 业务数据}
        if isinstance(data, dict) and "tool" in data and "data" in data:
            return data["data"]
        return data

    async def list_resumes(self) -> list[dict[str, Any]]:
        """简历列表（含最新分析分数与面试次数）。"""
        data = await self.call_tool("get_resume_list")
        return data if isinstance(data, list) else []

    async def get_interview_history(self) -> list[dict[str, Any]]:
        """模拟面试历史列表。"""
        data = await self.call_tool("get_interview_history")
        return data if isinstance(data, list) else []

    async def list_knowledge_bases(self) -> list[dict[str, Any]]:
        """知识库列表，用于 KNOWLEDGE_QA 时挑选检索目标。"""
        data = await self.call_tool("list_knowledge_bases")
        return data if isinstance(data, list) else []

    async def search_knowledge(
        self, question: str, knowledge_base_ids: list[int]
    ) -> dict[str, Any]:
        """RAG 问答：Java 侧负责查询改写、pgvector 检索与 LLM 作答。"""
        data = await self.call_tool(
            "search_knowledge",
            {"knowledgeBaseIds": knowledge_base_ids, "question": question},
        )
        return data if isinstance(data, dict) else {}

    async def get_agent_llm_config(self) -> dict[str, Any]:
        """拉取 Java 侧 Agent Provider 的连接配置（baseUrl / model / apiKey）。

        该接口由 Java 的 llm-provider 模块提供，返回解密后的 apiKey，
        仅用于内网可信的服务间调用（Agent Runtime → Java Backend）。
        """
        try:
            response = await self._client.get("/api/llm-provider/agent-config")
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc

        try:
            body = response.json()
        except ValueError as exc:
            raise BusinessToolError(500, "后端返回非 JSON 响应", retryable=True) from exc

        if body.get("code") != 200:
            raise BusinessToolError(
                code=body.get("code", -1),
                message=body.get("message", "后端业务错误"),
            )
        data = body.get("data")
        return data if isinstance(data, dict) else {}

    async def create_conversation(self) -> dict[str, Any]:
        """创建 Copilot 对话会话，返回会话列表项（含 id）。"""
        data = await self._post_plain("/api/agent/conversations", payload={})
        return data if isinstance(data, dict) else {}

    async def list_conversations(self) -> list[dict[str, Any]]:
        """Copilot 会话列表（置顶优先、按更新时间倒序）。"""
        try:
            response = await self._client.get("/api/agent/conversations")
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc
        body = self._unwrap_result(response)
        data = body.get("data")
        return data if isinstance(data, list) else []

    async def save_conversation_messages(
        self,
        conversation_id: int,
        messages: list[dict[str, Any]],
    ) -> None:
        """保存一轮消息（USER + ASSISTANT，含 blocks JSON）。

        由 Agent 在流式结束后调用；保存失败不影响流式响应（上层仅告警）。
        """
        payload = {
            "messages": [
                {
                    "role": message.get("role"),
                    "content": message.get("content") or "",
                    "blocks": message.get("blocks"),  # JSON 字符串或 None
                }
                for message in messages
            ]
        }
        await self._post_plain(f"/api/agent/conversations/{conversation_id}/messages", payload)

    async def _post_plain(self, path: str, payload: dict[str, Any]) -> Any:
        """通用 POST：请求 Java 非 Tool 端点并解包 Result。"""
        try:
            response = await self._client.post(path, json=payload)
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc
        body = self._unwrap_result(response)
        return body.get("data")

    def _unwrap_result(self, response: httpx.Response) -> dict[str, Any]:
        """解包 Java Result{code, message, data} 信封。"""
        try:
            body = response.json()
        except ValueError as exc:
            raise BusinessToolError(500, "后端返回非 JSON 响应", retryable=True) from exc
        if not isinstance(body, dict):
            raise BusinessToolError(500, "后端返回非对象响应", retryable=True)
        if body.get("code") != 200:
            raise BusinessToolError(
                code=body.get("code", -1),
                message=body.get("message", "后端业务错误"),
            )
        return body

    async def aclose(self) -> None:
        await self._client.aclose()