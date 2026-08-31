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

    async def get_resume_analysis(self, resume_id: int) -> dict[str, Any]:
        """获取指定简历的最新分析结果（评分/优势/建议）。

        分析尚未完成或不存在时抛 BusinessToolError（RESUME_ANALYSIS_NOT_FOUND），
        由上层决定如何引导（如实告知正在后台分析）。
        """
        data = await self.call_tool("get_resume_analysis", {"resumeId": resume_id})
        return data if isinstance(data, dict) else {}

    async def get_resume(
        self, resume_id: int, max_chars: int | None = None
    ) -> dict[str, Any]:
        """获取指定简历的完整内容（解析文本）与元信息。

        max_chars 由服务端截断（Token 纪律）；简历不存在抛
        BusinessToolError（RESUME_NOT_FOUND）。
        """
        arguments: dict[str, Any] = {"resumeId": resume_id}
        if max_chars is not None:
            arguments["maxChars"] = max_chars
        data = await self.call_tool("get_resume", arguments)
        return data if isinstance(data, dict) else {}

    async def get_interview_history(self) -> list[dict[str, Any]]:
        """模拟面试历史列表。"""
        data = await self.call_tool("get_interview_history")
        return data if isinstance(data, list) else []

    async def get_skill_profile(self) -> dict[str, Any]:
        """用户技能画像：各技能聚合分 + 可追溯证据（来自哪些面试、每题得分）。

        无任何画像数据时返回 {"skills": []}，由上层引导用户先参加面试。
        """
        data = await self.call_tool("get_skill_profile")
        return data if isinstance(data, dict) else {"skills": []}

    async def get_resume_version(
        self, resume_id: int, version: int | None = None
    ) -> dict[str, Any]:
        """简历结构化版本（简历优化取数入口）。

        默认最新 ACTIVE 版本；简历无已确认版本时抛
        BusinessToolError（RESUME_VERSION_NOT_READY）。
        """
        arguments: dict[str, Any] = {"resumeId": resume_id}
        if version is not None:
            arguments["version"] = version
        data = await self.call_tool("get_resume_version", arguments)
        return data if isinstance(data, dict) else {}

    async def create_optimization_proposal(
        self,
        resume_id: int,
        source_version_id: int,
        optimization_type: str,
        summary: str,
        patches: list[dict[str, Any]],
    ) -> int:
        """创建优化提案（HITL：提案先落 Java 审计，返回提案 id）。

        用户在前端确认后经 apply_resume_patches Tool 应用。
        """
        data = await self._post_plain(
            "/internal/agent/resume-optimization/proposals",
            {
                "resumeId": resume_id,
                "sourceVersionId": source_version_id,
                "optimizationType": optimization_type,
                "summary": summary,
                "patches": patches,
            },
        )
        return int(data)

    async def apply_resume_patches(
        self, proposal_id: int, patch_ids: list[str] | None = None
    ) -> dict[str, Any]:
        """应用用户确认的优化建议（CONFIRM_WRITE Tool）。

        Java 侧逐条 JSON path 应用（oldValue 一致性校验）并生成新版本
        （AI_OPTIMIZE，原版本不动）；内容漂移时抛 PATCH_CONFLICT。
        """
        arguments: dict[str, Any] = {"proposalId": proposal_id}
        if patch_ids:
            arguments["patchIds"] = patch_ids
        data = await self.call_tool("apply_resume_patches", arguments)
        return data if isinstance(data, dict) else {}

    async def list_skills(self) -> list[dict[str, Any]]:
        """可用的模拟面试技能方向列表（含分类）。"""
        data = await self.call_tool("list_skills")
        return data if isinstance(data, list) else []

    async def create_interview(
        self,
        skill_id: str,
        difficulty: str,
        question_count: int | None = None,
        resume_id: int | None = None,
        resume_text: str | None = None,
        force_create: bool = False,
    ) -> dict[str, Any]:
        """创建模拟面试会话（CONFIRM_WRITE，用户确认后才由 Agent 调用）。

        复用 Java Interview Engine 现有创建链路（含 requestId 幂等与未完成会话复用），
        返回 InterviewSessionDTO，sessionId 供前端跳转面试页。
        """
        arguments: dict[str, Any] = {
            "skillId": skill_id,
            "difficulty": difficulty,
        }
        if question_count is not None:
            arguments["questionCount"] = question_count
        if resume_id is not None:
            arguments["resumeId"] = resume_id
        if resume_text is not None:
            arguments["resumeText"] = resume_text
        if force_create:
            arguments["forceCreate"] = True
        data = await self.call_tool("create_interview", arguments)
        return data if isinstance(data, dict) else {}

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

    async def get_conversation_context(
        self, conversation_id: int, limit: int = 8
    ) -> dict[str, Any]:
        """拉取会话上下文：最近 N 条消息（role/content）+ 会话滚动摘要。

        短期记忆权威来源是 Java（System of Record），checkpoint 重启后仍可恢复。
        """
        try:
            response = await self._client.get(
                f"/api/agent/conversations/{conversation_id}/context",
                params={"limit": limit},
            )
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc
        body = self._unwrap_result(response)
        data = body.get("data")
        return data if isinstance(data, dict) else {"messages": [], "summary": None}

    async def update_conversation_summary(
        self, conversation_id: int, summary: str
    ) -> None:
        """把会话滚动摘要写回 Java（短期记忆持久化，重启后可恢复）。"""
        try:
            response = await self._client.put(
                f"/api/agent/conversations/{conversation_id}/summary",
                json={"summary": summary},
            )
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc
        self._unwrap_result(response)

    async def bind_active_resume(
        self, conversation_id: int, resume_id: int | None
    ) -> None:
        """绑定会话活动简历（resumeId 为 None 表示解绑）。

        定向简历分析/优化后调用，使下一轮无附件提问也能锁定目标简历。
        """
        try:
            response = await self._client.put(
                f"/api/agent/conversations/{conversation_id}/active-resume",
                json={"resumeId": resume_id},
            )
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc
        self._unwrap_result(response)

    async def bind_active_job(
        self, conversation_id: int, job_id: int | None
    ) -> None:
        """绑定会话活动 JD（P2-5，对称 bind_active_job；jobId 为 None 表示解绑）。"""
        try:
            response = await self._client.put(
                f"/api/agent/conversations/{conversation_id}/active-job",
                json={"jobId": job_id},
            )
        except httpx.HTTPError as exc:
            raise BusinessToolError(500, f"后端服务不可达: {exc}", retryable=True) from exc
        self._unwrap_result(response)

    async def get_job(self, job_id: int) -> dict[str, Any]:
        """JD 完整内容与元信息（get_job READ Tool；JD_TARGETED / JD 匹配取数入口）。"""
        data = await self.call_tool("get_job", {"jobId": job_id})
        return data if isinstance(data, dict) else {}

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