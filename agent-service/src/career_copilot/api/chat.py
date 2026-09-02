"""Chat API：Agent 统一对话入口。

API 层只负责：请求解析 → 构造初始 State → 调用 Copilot Turn Graph → SSE 流式转发
→ 流式结束后持久化。业务编排全部在 Graph / Tool 层，本文件保持薄。
"""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from typing import Annotated, Any, cast

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.graph import build_graph, build_initial_state
from career_copilot.agent.router import IntentRouter
from career_copilot.agent.state import RunStatus
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
        # 单次调用超时：模型侧卡住时快速失败，避免 SSE 无限挂起（前端"无响应"）
        timeout=settings.llm_timeout_seconds,
    )


async def sync_agent_llm_config() -> None:
    """启动时从 Java 同步 Agent 模型配置。

    失败不阻断启动：回落 .env 配置并记录告警，服务仍可用。
    启动后仍可由 _ensure_llm_config_synced 在首个请求时惰性重试。
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


_sync_lock = asyncio.Lock()


async def _ensure_llm_config_synced() -> None:
    """启动同步失败后的惰性重试。

    解决 Agent 先于 Java 启动导致的配置缺失：首个请求到来且尚未同步成功时
    重试一次（带锁防并发），成功后后续请求直接使用。
    """
    global _agent_llm_config
    if _agent_llm_config is not None:
        return
    async with _sync_lock:
        if _agent_llm_config is not None:
            return
        await sync_agent_llm_config()


async def get_intent_router() -> IntentRouter:
    """意图分类用低延迟模型 + temperature=0，保证分类稳定。

    async：构造前先确保配置已同步 —— 若作为同步 Depends，会在启动竞态下
    定型于 .env 回落地址（Java 未就绪时同步失败），整轮请求打到死 URL。
    测试经 dependency_overrides 整体替换本函数，不受影响。
    """
    await _ensure_llm_config_synced()
    return IntentRouter(_openai_model(settings.llm_intent_model, temperature=0.0))


async def get_answerer() -> Answerer:
    """回答生成使用常规模型，允许一定自由度（async 语义同 get_intent_router）。"""
    await _ensure_llm_config_synced()
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


def _initial_state(payload: ChatRequest) -> dict[str, Any]:
    """把 ChatRequest 归一化为 Graph 初始状态。"""
    return build_initial_state(
        conversation_id=payload.conversation_id,
        message=payload.message,
        attachments=[att.model_dump() for att in payload.attachments],
        action=payload.action.model_dump() if payload.action else None,
    )


def _graph_config(initial_state: dict[str, Any]) -> dict[str, Any] | None:
    """Graph 调用 config：conversation_id 作为 checkpoint thread_id（跨轮次持久化）。"""
    conversation_id = initial_state.get("conversation_id")
    if conversation_id is None:
        return None
    return {"configurable": {"thread_id": str(conversation_id)}}


async def _invoke_graph(
    graph: Any, initial_state: dict[str, Any]
) -> dict[str, Any]:
    """带可选 checkpoint 配置调用 Graph。

    无 conversation_id（无 thread_id）时 LangGraph 要求禁用 checkpoint，
    由调用方在编译阶段决定是否挂载 checkpointer。
    """
    config = _graph_config(initial_state)
    if config is None:
        return cast(dict[str, Any], await graph.ainvoke(initial_state))
    return cast(dict[str, Any], await graph.ainvoke(initial_state, config=config))


def _build_graph_with_checkpointer(
    http_request: Any, deps: GraphDeps, initial_state: dict[str, Any]
) -> Any:
    """按请求是否携带 conversation_id 决定是否挂载 checkpoint。

    checkpointer 以 conversation_id 作为 thread_id；无会话时编译不带
    checkpoint（LangGraph 要求 checkpointer 必须提供 thread_id）。
    """
    config = _graph_config(initial_state)
    checkpointer = _get_checkpointer(http_request) if config is not None else None
    return build_graph(deps, checkpointer=checkpointer)


def _get_checkpointer(http_request: Any) -> Any:
    """从应用状态取共享 checkpointer（未初始化时为 None）。"""
    return getattr(getattr(http_request, "app", None), "state", None) and getattr(
        http_request.app.state, "checkpointer", None
    )


def _sse(event: dict[str, Any]) -> str:
    """序列化 SSE data 行：事件内容为 {type, payload}，前端按 type 分派。"""
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"


@router.post("/chat", response_model=CopilotResponse)
async def chat(
    payload: ChatRequest,
    http_request: Request,
    intent_router: Annotated[IntentRouter, Depends(get_intent_router)],
    answerer: Annotated[Answerer, Depends(get_answerer)],
    backend: Annotated[BackendClient, Depends(get_backend_client)],
) -> CopilotResponse:
    """同步入口：完整 JSON 响应，供简单调用与测试使用。"""
    # 启动同步失败时在首个请求惰性重试（Java 可能晚于 Agent 就绪）
    await _ensure_llm_config_synced()
    initial_state = _initial_state(payload)
    deps = _build_deps(intent_router, answerer, backend)
    graph = _build_graph_with_checkpointer(http_request, deps, initial_state)
    state = await _invoke_graph(graph, initial_state)
    plan = state.get("plan")
    content = ""
    if plan is not None and plan.text is not None:
        async for chunk in plan.text:
            content += chunk
    return CopilotResponse(content=content, blocks=plan.blocks if plan else [])


@router.post("/chat/stream")
async def chat_stream(
    payload: ChatRequest,
    http_request: Request,
    intent_router: Annotated[IntentRouter, Depends(get_intent_router)],
    answerer: Annotated[Answerer, Depends(get_answerer)],
    backend: Annotated[BackendClient, Depends(get_backend_client)],
) -> StreamingResponse:
    """SSE 流式入口：run_status/tool_* → block → message_delta... → done / error。

    Graph 执行期经 LangGraph custom stream 实时转发节点内埋点
    （tool_started / tool_completed / WAITING_USER 等 run_status），
    执行完毕后按既有顺序产出 blocks 与文本增量。
    流式结束后若携带 conversation_id，则把本轮保存到 Java。
    """

    async def event_stream() -> AsyncIterator[str]:
        collected_blocks: list[dict[str, Any]] = []
        collected_content: list[str] = []
        try:
            # 启动同步失败时在首个请求惰性重试（Java 可能晚于 Agent 就绪）
            await _ensure_llm_config_synced()
            initial_state = _initial_state(payload)
            deps = _build_deps(intent_router, answerer, backend)
            graph = _build_graph_with_checkpointer(http_request, deps, initial_state)
            config = _graph_config(initial_state)

            yield _sse({"type": "run_status", "payload": {"status": RunStatus.RUNNING.value}})

            # astream 泵：custom 模式实时转发节点事件，values 追踪最终状态
            state_holder: dict[str, dict[str, Any]] = {}
            stream_kwargs: dict[str, Any] = {"stream_mode": ["custom", "values"]}
            if config is not None:
                stream_kwargs["config"] = config
            async for mode, chunk in graph.astream(initial_state, **stream_kwargs):
                if mode == "custom":
                    yield _sse(chunk)
                else:
                    state_holder["state"] = chunk

            state = state_holder.get("state") or {}
            plan = state.get("plan")
            # 结构化块先于文本一次性产出，前端无需从 token 流中猜测块边界
            for block in plan.blocks if plan else []:
                collected_blocks.append(block.model_dump())
                yield _sse({"type": "block", "payload": block.model_dump()})
            if plan is not None and plan.text is not None:
                async for chunk in plan.text:
                    collected_content.append(chunk)
                    yield _sse({"type": "message_delta", "payload": {"content": chunk}})
            yield _sse(
                {"type": "run_status", "payload": {"status": RunStatus.COMPLETED.value}}
            )
        except Exception:
            logger.exception("chat stream failed")
            yield _sse({"type": "error", "payload": {"message": "处理失败，请稍后重试"}})
            yield _sse({"type": "run_status", "payload": {"status": RunStatus.FAILED.value}})
        finally:
            yield _sse({"type": "done", "payload": {}})
            # 持久化本轮消息：失败不影响流式响应，仅告警
            await _persist_conversation_turn(
                backend,
                payload.conversation_id,
                payload.message.strip() or "[附件]",
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

    用户消息总是保存（失败轮也保留，避免刷新后消息"消失"）；
    assistant 内容为空（如本轮处理失败）时只存用户消息，不发送空回复
    （Java 端校验消息内容非空）。保存失败仅告警（对话可用性优先）。
    blocks 以 JSON 字符串持久化，与 Java AgentMessageEntity.blocks 列对齐。
    """
    if conversation_id is None or not user_message:
        return
    try:
        conversation_id_int = int(conversation_id)
    except (TypeError, ValueError):
        logger.warning("conversation_id 非法，跳过持久化: %s", conversation_id)
        return

    messages = [{"role": "USER", "content": user_message, "blocks": None}]
    if assistant_content:
        blocks_json = (
            json.dumps(assistant_blocks, ensure_ascii=False) if assistant_blocks else None
        )
        messages.append(
            {
                "role": "ASSISTANT",
                "content": assistant_content,
                "blocks": blocks_json,
            }
        )
    try:
        await backend.save_conversation_messages(conversation_id_int, messages)
        logger.info(
            "conversation turn persisted: id=%s messages=%d",
            conversation_id,
            len(messages),
        )
    except BusinessToolError as exc:
        logger.warning(
            "conversation turn persist failed: id=%s code=%s message=%s",
            conversation_id,
            exc.code,
            exc.message,
        )
    except Exception:
        logger.exception("conversation turn persist unexpected error: id=%s", conversation_id)