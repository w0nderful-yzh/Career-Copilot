"""Chat API：Agent 统一对话入口。

API 层只负责：请求解析 → 构造初始 State → 调用 Copilot Turn Graph → SSE 流式转发
→ 流式结束后持久化。业务编排全部在 Graph / Tool 层，本文件保持薄。
"""

import json
import logging
from collections.abc import AsyncIterator
from typing import Annotated, Any

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.graph import build_graph, build_initial_state
from career_copilot.agent.router import IntentRouter
from career_copilot.clients.backend import BackendClient, BusinessToolError
from career_copilot.config import settings
from career_copilot.schemas.message import ChatRequest, CopilotResponse

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


def _build_deps(
    intent_router: IntentRouter,
    answerer: Answerer,
    backend: BackendClient,
) -> GraphDeps:
    """组装 Graph 依赖（每请求一次）。"""
    return GraphDeps(intent_router=intent_router, answerer=answerer, backend=backend)


def _initial_state(request: ChatRequest) -> dict[str, Any]:
    """把 ChatRequest 归一化为 Graph 初始状态。"""
    return build_initial_state(
        conversation_id=request.conversation_id,
        message=request.message,
        attachments=[att.model_dump() for att in request.attachments],
        action=request.action.model_dump() if request.action else None,
    )


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
    graph = build_graph(_build_deps(intent_router, answerer, backend))
    state = await graph.ainvoke(_initial_state(request))
    plan = state.get("plan")
    content = ""
    if plan is not None and plan.text is not None:
        async for chunk in plan.text:
            content += chunk
    return CopilotResponse(content=content, blocks=plan.blocks if plan else [])


@router.post("/chat/stream")
async def chat_stream(
    request: ChatRequest,
    intent_router: Annotated[IntentRouter, Depends(get_intent_router)],
    answerer: Annotated[Answerer, Depends(get_answerer)],
    backend: Annotated[BackendClient, Depends(get_backend_client)],
) -> StreamingResponse:
    """SSE 流式入口：block → message_delta... → done / error，供 Copilot Workspace 使用。

    流式结束后若携带 conversation_id，则把本轮（用户消息 + 助手回复含 blocks）保存到 Java。
    """

    async def event_stream() -> AsyncIterator[str]:
        collected_blocks: list[dict[str, Any]] = []
        collected_content: list[str] = []
        try:
            graph = build_graph(_build_deps(intent_router, answerer, backend))
            state = await graph.ainvoke(_initial_state(request))
            plan = state.get("plan")
            # 结构化块先于文本一次性产出，前端无需从 token 流中猜测块边界
            for block in plan.blocks if plan else []:
                collected_blocks.append(block.model_dump())
                yield _sse({"type": "block", "payload": block.model_dump()})
            if plan is not None and plan.text is not None:
                async for chunk in plan.text:
                    collected_content.append(chunk)
                    yield _sse({"type": "message_delta", "payload": {"content": chunk}})
        except Exception:
            logger.exception("chat stream failed")
            yield _sse({"type": "error", "payload": {"message": "处理失败，请稍后重试"}})
        finally:
            yield _sse({"type": "done", "payload": {}})
            # 持久化本轮消息：失败不影响流式响应，仅告警
            await _persist_conversation_turn(
                backend,
                request.conversation_id,
                request.message.strip() or "[附件]",
                "".join(collected_content),
                collected_blocks,
            )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


async def _persist_conversation_turn(
    backend: BackendClient,
    conversation_id: str | int | None,
    user_message: str,
    assistant_content: str,
    assistant_blocks: list[dict[str, Any]],
) -> None:
    """把一轮对话（用户消息 + 助手回复）保存到 Java conversation 模块。

    无 conversation_id 或内容为空时跳过；保存失败仅告警（对话可用性优先）。
    blocks 以 JSON 字符串持久化，与 Java AgentMessageEntity.blocks 列对齐。
    """
    if conversation_id is None or not user_message:
        return
    try:
        conversation_id_int = int(conversation_id)
    except (TypeError, ValueError):
        logger.warning("conversation_id 非法，跳过持久化: %s", conversation_id)
        return

    blocks_json = (
        json.dumps(assistant_blocks, ensure_ascii=False) if assistant_blocks else None
    )
    try:
        await backend.save_conversation_messages(
            conversation_id_int,
            [
                {"role": "USER", "content": user_message, "blocks": None},
                {
                    "role": "ASSISTANT",
                    "content": assistant_content,
                    "blocks": blocks_json,
                },
            ],
        )
        logger.info("conversation turn persisted: id=%s", conversation_id)
    except BusinessToolError as exc:
        logger.warning(
            "conversation turn persist failed: id=%s code=%s message=%s",
            conversation_id,
            exc.code,
            exc.message,
        )
    except Exception:
        logger.exception("conversation turn persist unexpected error: id=%s", conversation_id)