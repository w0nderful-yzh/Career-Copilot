"""Copilot Turn Graph：Agent 统一入口。

职责：编排「这一次输入应该进入哪个处理流程，以及完成后下一步是什么」。
Graph 不重新实现业务能力：业务数据通过 Tool → BackendClient → Java 获取。

执行模型：分支节点产出 StreamPlan（blocks + 惰性文本源），API 层负责 SSE 流式。
"""

from functools import partial
from typing import Any

from langgraph.graph import END, START, StateGraph

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.nodes.attachment_flow import attachment_flow
from career_copilot.agent.nodes.build_response import build_response
from career_copilot.agent.nodes.business_tools import business_tools
from career_copilot.agent.nodes.direct_answer import direct_answer
from career_copilot.agent.nodes.execute_action import execute_action
from career_copilot.agent.nodes.knowledge_tool import knowledge_tool
from career_copilot.agent.nodes.load_history import load_history
from career_copilot.agent.nodes.navigation_action import navigation_action
from career_copilot.agent.nodes.normalize_input import normalize_input
from career_copilot.agent.nodes.resolve_context import resolve_context
from career_copilot.agent.nodes.route_intent import ACTION_INTENT, route_intent
from career_copilot.agent.nodes.stub import goal_execution, resume_optimization
from career_copilot.agent.router import Intent
from career_copilot.agent.state import CareerAgentState

# intent（含确定性 ACTION / ATTACHMENT）→ 分支节点
# path_map 键用 Hashable 对齐 langgraph 条件边签名（值必须为节点名）
INTENT_BRANCHES: dict[Any, str] = {
    Intent.GENERAL_CHAT.value: "direct_answer",
    Intent.RESUME_QUERY.value: "business_tools",
    Intent.INTERVIEW_REVIEW.value: "business_tools",
    Intent.PROFILE_QUERY.value: "business_tools",
    Intent.PREPARATION_QUERY.value: "business_tools",
    Intent.KNOWLEDGE_QA.value: "knowledge_tool",
    Intent.NAVIGATION.value: "navigation_action",
    Intent.RESUME_OPTIMIZATION.value: "resume_optimization",
    Intent.COMPLEX_GOAL.value: "goal_execution",
    Intent.ATTACHMENT_RECEIVED.value: "attachment_flow",
    ACTION_INTENT: "execute_action",
}


def route_by_intent(state: dict[str, Any]) -> str:
    """route_intent 条件边：按 state.intent 返回意图 key（path_map 键为意图值）。

    未知/缺失意图回退 GENERAL_CHAT，保证路由总能落到白名单分支。
    """
    return state.get("intent") or Intent.GENERAL_CHAT.value


def build_graph(deps: GraphDeps, checkpointer: Any = None) -> Any:
    """构造并编译 Copilot Turn Graph。

    依赖通过闭包注入（partial），每请求编译一次；测试可注入 fake 依赖。
    checkpointer 为 LangGraph checkpoint（thread_id=conversation_id），
    用于跨轮次持久化工作状态；None 表示不启用。
    """
    graph = StateGraph(CareerAgentState)

    # 前置管线（确定性）
    graph.add_node("normalize_input", normalize_input)
    graph.add_node("load_history", partial(load_history, deps=deps))
    graph.add_node("resolve_context", resolve_context)
    graph.add_node("route_intent", partial(route_intent, deps=deps))

    # 意图分支
    graph.add_node("direct_answer", partial(direct_answer, deps=deps))
    graph.add_node("business_tools", partial(business_tools, deps=deps))
    graph.add_node("knowledge_tool", partial(knowledge_tool, deps=deps))
    graph.add_node("navigation_action", partial(navigation_action, deps=deps))
    graph.add_node("attachment_flow", partial(attachment_flow, deps=deps))
    graph.add_node("execute_action", partial(execute_action, deps=deps))
    graph.add_node("resume_optimization", partial(resume_optimization, deps=deps))
    graph.add_node("goal_execution", partial(goal_execution, deps=deps))

    # 收尾
    graph.add_node("build_response", partial(build_response, deps=deps))

    graph.add_edge(START, "normalize_input")
    graph.add_edge("normalize_input", "load_history")
    graph.add_edge("load_history", "resolve_context")
    graph.add_edge("resolve_context", "route_intent")

    graph.add_conditional_edges("route_intent", route_by_intent, INTENT_BRANCHES)

    for branch in set(INTENT_BRANCHES.values()):
        graph.add_edge(branch, "build_response")
    graph.add_edge("build_response", END)

    return graph.compile(checkpointer=checkpointer)


def build_initial_state(
    *,
    conversation_id: str | int | None,
    message: str,
    attachments: list[dict[str, Any]],
    action: dict[str, Any] | None,
    user_id: str = "default",
) -> dict[str, Any]:
    """构造 Graph 初始状态（与 ChatRequest 对齐），由 API 层调用。"""
    return {
        "conversation_id": conversation_id,
        "user_id": user_id,
        "message": message or "",
        "attachments": attachments,
        "action": action,
        "tool_results": [],
        "status": "RUNNING",
    }