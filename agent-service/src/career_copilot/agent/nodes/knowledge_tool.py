"""knowledge_tool：KNOWLEDGE_QA 复用 Java RAG 链路，产出引用块与答案文本。

RAG（Chunk / Embedding / pgvector / 检索）全部由 Java 管理，Python 只调用 Tool。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import citations_block
from career_copilot.agent.state import CareerAgentState
from career_copilot.clients.backend import BusinessToolError
from career_copilot.tools import resolve_knowledge_base_ids


async def knowledge_tool(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    message = state.get("message") or ""
    backend = deps.backend

    knowledge_base_ids = await resolve_knowledge_base_ids(backend)
    if not knowledge_base_ids:
        return {"plan": StreamPlan(text=static_text("知识库为空，暂时无法检索资料。"))}

    try:
        result = await backend.search_knowledge(message, knowledge_base_ids)
    except BusinessToolError:
        return {"plan": StreamPlan(text=static_text("知识库查询失败，请稍后重试。"))}

    answer = result.get("answer") or "未检索到相关内容。"
    blocks: list[Any] = []
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
    return {"plan": StreamPlan(blocks=blocks, text=static_text(answer))}