"""LangGraph Studio 调试入口（`langgraph dev`）。

生产入口是 `api/chat.py`（FastAPI + 每请求依赖 + SSE）。Studio 走的是另一套加载模型，
本模块是两者的适配层，**不参与线上链路**：

- 平台只支持模块级变量或单参数 factory（参数为 RunnableConfig），拿不到 FastAPI 的
  Request / Depends，因此依赖在这里按进程构造一次，而不是每请求注入；
- 不挂 checkpointer：平台会用自己的实现覆盖 factory 返回值
  （langgraph_api.graph.py 在 factory 返回后执行 copy(update={"checkpointer": ...})），
  且本图的 `plan` 字段含 AsyncIterator 不可序列化，不能进 checkpoint；
- 不同步 Java 侧的 Agent 模型配置（没有 lifespan 钩子）。**注意**：Agent 的真实 LLM
  由 Java Provider 提供，本仓库 .env 只是占位，因此 Studio 只适合查看图结构 / 条件边，
  LLM 相关节点在此模式下不可用；要跑通完整对话请用 api/chat.py 起服务。
"""

from typing import Any

from langchain_core.runnables.config import RunnableConfig
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.graph import build_graph
from career_copilot.agent.llm import LlmExecutor
from career_copilot.agent.router import IntentRouter
from career_copilot.clients.backend import BackendClient
from career_copilot.config import settings

# 占位密钥：ChatOpenAI 在缺 key 时构造即抛 OpenAIError，会让图直接加载失败。
# 这里只保证对象能构造（Studio 查看结构不会真正调用模型）。
_PLACEHOLDER_API_KEY = "studio-placeholder-key"

#: 进程级缓存：平台每请求调用一次 factory，重建连接池会持续泄漏
_deps: GraphDeps | None = None


def _chat_model(model: str, temperature: float) -> ChatOpenAI:
    """按 settings 构造模型客户端（与 api/chat.py 的 .env 回落分支一致）。"""
    return ChatOpenAI(
        model=model,
        api_key=settings.llm_api_key or SecretStr(_PLACEHOLDER_API_KEY),
        base_url=settings.llm_base_url,
        temperature=temperature,
        timeout=settings.llm_timeout_seconds,
    )


def _studio_deps() -> GraphDeps:
    """构造 Studio 用的 Graph 依赖，进程内复用。"""
    global _deps
    if _deps is None:
        llm = LlmExecutor(
            _chat_model(settings.llm_model, temperature=0.3),
            default_timeout=settings.llm_timeout_background_seconds,
            parse_retries=settings.llm_parse_retries,
        )
        _deps = GraphDeps(
            intent_router=IntentRouter(
                LlmExecutor(
                    _chat_model(settings.llm_intent_model, temperature=0.0),
                    default_timeout=settings.llm_timeout_realtime_seconds,
                    parse_retries=settings.llm_parse_retries,
                )
            ),
            answerer=Answerer(llm),
            backend=BackendClient(settings.backend_base_url, settings.backend_timeout),
            llm=llm,
        )
    return _deps


def graph(config: RunnableConfig) -> Any:
    """Studio 图工厂：单参数签名是平台的硬性要求。"""
    return build_graph(_studio_deps())
