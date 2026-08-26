"""Chat API：Agent 统一对话入口。

API 层只负责请求校验、依赖组装与意图短路编排，业务决策在 Agent/Tool 层。
提供同步 JSON 与 SSE 流式两种响应，流式供 Copilot Workspace 使用。
"""

import json
import logging
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Annotated, Any

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.response import (
    citations_block,
    interview_summary_block,
    resume_summary_block,
)
from career_copilot.agent.router import ActionRoute, Intent, IntentRouter
from career_copilot.clients.backend import BackendClient, BusinessToolError
from career_copilot.config import settings
from career_copilot.schemas.message import (
    ActionBlock,
    ChatRequest,
    CopilotResponse,
    MessageBlock,
)
from career_copilot.tools import (
    resolve_knowledge_base_ids,
    summarize_interviews,
    summarize_resumes,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["chat"])

# Java 同步过来的 Agent LLM 配置缓存；None 表示尚未同步（回落 .env 配置）
_agent_llm_config: dict[str, Any] | None = None


def _resolve_llm_config() -> tuple[str, SecretStr | None, str | None]:
    """解析 Agent 使用的 LLM 连接配置。

    优先使用 Java 同步的 Agent Provider 配置（设置页统一管理，MVP 阶段意图与回答共用同一模型），
    同步失败或未配置时回落 agent-service/.env 的 LLM_* 配置（此时模型由调用方传入）。
    """
    config = _agent_llm_config
    if config and config.get("baseUrl") and config.get("model"):
        api_key = config.get("apiKey")
        return (
            config["baseUrl"],
            SecretStr(api_key) if api_key else None,
            config["model"],
        )
    return (
        settings.llm_base_url,
        settings.llm_api_key,
        None,
    )


def _openai_model(model: str, temperature: float) -> ChatOpenAI:
    """构造 OpenAI 兼容模型客户端，连接信息来自同步的 Agent Provider 配置。

    同步成功时使用 Java 侧配置的模型；回落 .env 时才使用调用方传入的模型参数。
    """
    base_url, api_key, synced_model = _resolve_llm_config()
    return ChatOpenAI(
        model=synced_model or model,
        api_key=api_key,
        base_url=base_url,
        temperature=temperature,
    )


async def sync_agent_llm_config() -> None:
    """启动时从 Java 同步 Agent 模型配置。

    失败不阻断启动：回落 .env 配置并记录告警，服务仍可用。
    """
    global _agent_llm_config
    try:
        client = BackendClient(settings.backend_base_url, settings.backend_timeout)
        try:
            config = await client.get_agent_llm_config()
        finally:
            await client.aclose()
        _agent_llm_config = config
        logger.info(
            "Agent LLM config synced from backend: provider=%s model=%s",
            config.get("providerId"),
            config.get("model"),
        )
    except BusinessToolError as exc:
        logger.warning(
            "Agent LLM config sync failed, fallback to .env: code=%s message=%s",
            exc.code,
            exc.message,
        )
    except Exception:
        logger.exception("Agent LLM config sync unexpected error, fallback to .env")


def get_intent_router() -> IntentRouter:
    """意图分类用低延迟模型 + temperature=0，保证分类稳定。"""
    return IntentRouter(_openai_model(settings.llm_intent_model, temperature=0.0))


def get_answerer() -> Answerer:
    """回答生成使用常规模型，允许一定自由度。"""
    return Answerer(_openai_model(settings.llm_model, temperature=0.3))


async def get_backend_client() -> AsyncIterator[BackendClient]:
    """每个请求一个短生命周期 Client，请求结束关闭连接池。"""
    client = BackendClient(settings.backend_base_url, settings.backend_timeout)
    try:
        yield client
    finally:
        await client.aclose()


@dataclass
class StreamPlan:
    """意图分支的执行计划：先产出的结构化块 + 可选的流式文本。

    blocks 在文本流之前一次性产出，text 为 None 表示无文本。
    """

    blocks: list[MessageBlock] = field(default_factory=list)
    text: AsyncIterator[str] | None = None


async def _static_text(content: str) -> AsyncIterator[str]:
    """静态文本一次性产出。"""
    yield content


def _action_plan(route: ActionRoute) -> StreamPlan:
    """NAVIGATION 分支：动作块 + 引导文案，路由来自白名单。"""
    copy: dict[ActionRoute, tuple[str, str]] = {
        ActionRoute.RESUME_UPLOAD: ("好的，我们先从简历开始吧。", "上传简历"),
        ActionRoute.INTERVIEW_CREATE: ("好的，准备开始一场模拟面试。", "开始模拟面试"),
        ActionRoute.INTERVIEW_HISTORY: ("好的，这是你的面试记录入口。", "查看面试历史"),
        ActionRoute.KNOWLEDGE_BASE: ("好的，知识库管理入口在这里。", "管理知识库"),
        ActionRoute.KNOWLEDGE_CHAT: ("好的，可以在这里向知识库提问。", "打开问答助手"),
        ActionRoute.SETTINGS: ("好的，模型与系统设置在这里。", "打开设置"),
    }
    content, label = copy.get(route, ("你想开始哪项操作？", "开始模拟面试"))
    return StreamPlan(
        blocks=[ActionBlock(route=route.value, label=label)],
        text=_static_text(content),
    )


async def _build_plan(
    classification: Any,
    message: str,
    answerer: Answerer,
    backend: BackendClient,
) -> StreamPlan:
    """按意图短路构建执行计划，供同步与流式端点共用。"""
    match classification.intent:
        case Intent.RESUME_QUERY:
            return await _plan_resume_query(message, answerer, backend)
        case Intent.INTERVIEW_REVIEW:
            return await _plan_interview_review(message, answerer, backend)
        case Intent.KNOWLEDGE_QA:
            return await _plan_knowledge_qa(message, backend)
        case Intent.NAVIGATION:
            return _action_plan(classification.action_route)
        case _:
            # GENERAL_CHAT 及未匹配意图：无块，纯流式文本
            return StreamPlan(text=answerer.answer_stream(message))


async def _plan_resume_query(
    message: str, answerer: Answerer, backend: BackendClient
) -> StreamPlan:
    """简历查询：先产出 resume_summary 块，再基于摘要流式回答。"""
    resumes = await backend.list_resumes()
    if not resumes:
        return StreamPlan(
            blocks=[ActionBlock(route=ActionRoute.RESUME_UPLOAD.value, label="上传简历")],
            text=_static_text("你还没有上传简历，上传后我可以帮你分析简历与岗位的匹配情况。"),
        )
    context = await summarize_resumes(resumes)
    return StreamPlan(
        blocks=[resume_summary_block(resumes)],
        text=answerer.answer_stream(message, context),
    )


async def _plan_interview_review(
    message: str, answerer: Answerer, backend: BackendClient
) -> StreamPlan:
    """面试回顾：先产出 interview_summary 块，再基于摘要流式回答。"""
    history = await backend.get_interview_history()
    if not history:
        return StreamPlan(
            blocks=[
                ActionBlock(route=ActionRoute.INTERVIEW_CREATE.value, label="开始模拟面试")
            ],
            text=_static_text("你还没有模拟面试记录，可以先来一场模拟面试练练手。"),
        )
    context = await summarize_interviews(history)
    return StreamPlan(
        blocks=[interview_summary_block(history)],
        text=answerer.answer_stream(message, context),
    )


async def _plan_knowledge_qa(
    message: str, backend: BackendClient
) -> StreamPlan:
    """知识问答：复用 Java RAG 链路，产出引用块与答案文本。"""
    knowledge_base_ids = await resolve_knowledge_base_ids(backend)
    if not knowledge_base_ids:
        return StreamPlan(text=_static_text("知识库为空，暂时无法检索资料。"))
    try:
        result = await backend.search_knowledge(message, knowledge_base_ids)
    except BusinessToolError:
        return StreamPlan(text=_static_text("知识库查询失败，请稍后重试。"))
    answer = result.get("answer") or "未检索到相关内容。"
    blocks: list[MessageBlock] = []
    if result.get("knowledgeBaseId") is not None:
        blocks.append(
            citations_block(
                [
                    {
                        "knowledgeBaseId": result["knowledgeBaseId"],
                        "name": result.get("knowledgeBaseName") or "知识库",
                    }
                ]
            )
        )
    return StreamPlan(blocks=blocks, text=_static_text(answer))


def _sse(event: dict[str, Any]) -> str:
    """序列化 SSE data 行：事件内容为 {type, payload}，前端按 type 分派。"""
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"


@router.post("/chat", response_model=CopilotResponse)
async def chat(
    request: ChatRequest,
    intent_router: Annotated[IntentRouter, Depends(get_intent_router)],
    answerer: Annotated[Answerer, Depends(get_answerer)],
    backend: Annotated[BackendClient, Depends(get_backend_client)],
) -> CopilotResponse:
    """同步入口：完整 JSON 响应，供简单调用与测试使用。"""
    classification = await intent_router.classify(request.message)
    plan = await _build_plan(classification, request.message, answerer, backend)
    content = ""
    if plan.text is not None:
        async for chunk in plan.text:
            content += chunk
    return CopilotResponse(content=content, blocks=plan.blocks)


@router.post("/chat/stream")
async def chat_stream(
    request: ChatRequest,
    intent_router: Annotated[IntentRouter, Depends(get_intent_router)],
    answerer: Annotated[Answerer, Depends(get_answerer)],
    backend: Annotated[BackendClient, Depends(get_backend_client)],
) -> StreamingResponse:
    """SSE 流式入口：block → message_delta... → done / error，供 Copilot Workspace 使用。"""

    async def event_stream() -> AsyncIterator[str]:
        try:
            classification = await intent_router.classify(request.message)
            plan = await _build_plan(classification, request.message, answerer, backend)
            # 结构化块先于文本一次性产出，前端无需从 token 流中猜测块边界
            for block in plan.blocks:
                yield _sse({"type": "block", "payload": block.model_dump()})
            if plan.text is not None:
                async for chunk in plan.text:
                    yield _sse({"type": "message_delta", "payload": {"content": chunk}})
        except Exception:
            logger.exception("chat stream failed")
            yield _sse({"type": "error", "payload": {"message": "处理失败，请稍后重试"}})
        finally:
            yield _sse({"type": "done", "payload": {}})

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )