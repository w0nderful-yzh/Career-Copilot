"""预留分支的占位实现：COMPLEX_GOAL。

Goal Execution 子图后续单独实现，占位返回友好引导避免空回复。
Resume Optimization 子图已在 nodes/resume_optimization.py 落地（P2-1）。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.state import CareerAgentState


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
