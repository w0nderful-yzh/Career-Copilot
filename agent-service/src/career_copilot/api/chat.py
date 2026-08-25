"""Chat API：Agent 统一对话入口。

API 层只负责请求校验、依赖组装与意图短路编排，业务决策在 Agent/Tool 层。
"""

import logging
from collections.abc import AsyncIterator
from typing import Annotated, Any

from fastapi import APIRouter, Depends
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.response import navigation_response, text_response
from career_copilot.agent.router import Intent, IntentRouter, NavigationRoute
from career_copilot.clients.backend import BackendClient, BusinessToolError
from career_copilot.config import settings
from career_copilot.schemas.message import ChatRequest, CopilotResponse
from career_copilot.tools import (
    resolve_knowledge_base_ids,
    summarize_interviews,
    summarize_resumes,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["chat"])

# Java 同步过来的 Agent LLM 配置缓存；None 表示尚未同步（回落 .env 配置）
_agent_llm_config: dict[str, Any] | None = None


def _resolve_llm_config() -> tuple[str, SecretStr | None, str]:
    """解析 Agent 使用的 LLM 连接配置。

    优先使用 Java 同步的 Agent Provider 配置（设置页统一管理），
    同步失败或未配置时回落 agent-service/.env 的 LLM_* 配置。
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
        settings.llm_model,
    )


def _openai_model(model: str, temperature: float) -> ChatOpenAI:
    """构造 OpenAI 兼容模型客户端，连接信息来自同步的 Agent Provider 配置。"""
    base_url, api_key, default_model = _resolve_llm_config()
    return ChatOpenAI(
        model=model or default_model,
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


@router.post("/chat", response_model=CopilotResponse)
async def chat(
    request: ChatRequest,
    intent_router: Annotated[IntentRouter, Depends(get_intent_router)],
    answerer: Annotated[Answerer, Depends(get_answerer)],
    backend: Annotated[BackendClient, Depends(get_backend_client)],
) -> CopilotResponse:
    """Agent 主入口：识别意图后短路执行对应分支，不做通用 Planning。"""
    classification = await intent_router.classify(request.message)

    # 按意图短路分派：简单意图直接走对应分支，返回结构化响应
    match classification.intent:
        case Intent.RESUME_QUERY:
            return await _handle_resume_query(request.message, answerer, backend)
        case Intent.INTERVIEW_REVIEW:
            return await _handle_interview_review(request.message, answerer, backend)
        case Intent.KNOWLEDGE_QA:
            return await _handle_knowledge_qa(request.message, backend)
        case Intent.NAVIGATION:
            return _handle_navigation(classification.navigation_route)
        case Intent.GENERAL_CHAT:
            return text_response(await answerer.answer(request.message))
        case _:
            # 理论不可达：枚举新增时未处理会落入此处
            return text_response(await answerer.answer(request.message))


async def _handle_resume_query(
    message: str, answerer: Answerer, backend: BackendClient
) -> CopilotResponse:
    """简历查询：拉取简历列表摘要，交给 LLM 结合上下文回答。"""
    resumes = await backend.list_resumes()
    if not resumes:
        return navigation_response(
            "你还没有上传简历，上传后我可以帮你分析简历与岗位的匹配情况。",
            route=NavigationRoute.RESUME_UPLOAD.value,
            label="上传简历",
        )
    context = await summarize_resumes(resumes)
    return text_response(await answerer.answer(message, context))


async def _handle_interview_review(
    message: str, answerer: Answerer, backend: BackendClient
) -> CopilotResponse:
    """面试回顾：拉取最近面试历史摘要，交给 LLM 结合上下文回答。"""
    history = await backend.get_interview_history()
    if not history:
        return navigation_response(
            "你还没有模拟面试记录，可以先来一场模拟面试练练手。",
            route=NavigationRoute.INTERVIEW_CREATE.value,
            label="开始模拟面试",
        )
    context = await summarize_interviews(history)
    return text_response(await answerer.answer(message, context))


async def _handle_knowledge_qa(
    message: str, backend: BackendClient
) -> CopilotResponse:
    """知识问答：复用 Java RAG 链路，RAG 结果直接作为回答返回。"""
    knowledge_base_ids = await resolve_knowledge_base_ids(backend)
    if not knowledge_base_ids:
        return text_response("知识库为空，暂时无法检索资料。")
    try:
        result = await backend.search_knowledge(message, knowledge_base_ids)
    except BusinessToolError:
        return text_response("知识库查询失败，请稍后重试。")
    return text_response(result.get("answer") or "未检索到相关内容。")


def _handle_navigation(route: NavigationRoute | None) -> CopilotResponse:
    """导航意图：根据分类结果返回对应导航建议。"""
    match route:
        case NavigationRoute.RESUME_UPLOAD:
            return navigation_response(
                "好的，我们先从简历开始吧。",
                route=route.value,
                label="上传简历",
            )
        case NavigationRoute.INTERVIEW_CREATE:
            return navigation_response(
                "好的，准备开始一场模拟面试。",
                route=route.value,
                label="开始模拟面试",
            )
        case NavigationRoute.INTERVIEW_HISTORY:
            return navigation_response(
                "好的，这是你的面试记录入口。",
                route=route.value,
                label="查看面试历史",
            )
        case _:
            return navigation_response(
                "你想开始哪项操作？",
                route=NavigationRoute.INTERVIEW_CREATE.value,
                label="开始模拟面试",
            )