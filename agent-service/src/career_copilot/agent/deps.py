"""Graph 依赖注入：每请求构造一次，闭包注入各节点。

依赖来自 FastAPI 依赖系统（IntentRouter / Answerer / BackendClient），
Graph 只通过 deps 访问，测试可注入 fake 实现，避免真实 LLM / Java 调用。
"""

from dataclasses import dataclass

from career_copilot.agent.answerer import Answerer
from career_copilot.agent.router import IntentRouter
from career_copilot.clients.backend import BackendClient


@dataclass
class GraphDeps:
    """Copilot Turn Graph 的外部依赖集合。"""

    intent_router: IntentRouter
    answerer: Answerer
    backend: BackendClient