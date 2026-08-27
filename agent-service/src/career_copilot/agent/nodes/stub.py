"""预留分支的占位实现：RESUME_OPTIMIZATION / COMPLEX_GOAL。

Resume Optimization 子图与 Goal Execution 子图后续单独实现，
本轮主 Graph 先完成路由接线，占位返回友好引导避免空回复。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.state import CareerAgentState


async def resume_optimization(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    """Resume Optimization Subgraph 占位：识别意图后如实告知能力尚未上线。"""
    return {
        "plan": StreamPlan(
            text=static_text(
                "简历优化功能正在建设中。当前你可以先查看简历分析或来一场模拟面试。"
            )
        )
    }


async def goal_execution(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    """Goal Execution Subgraph 占位。"""
    return {
        "plan": StreamPlan(
            text=static_text(
                "「制定完整求职准备计划」功能正在建设中。你可以先查看简历或复习进度。"
            )
        )
    }