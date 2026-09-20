"""profile_query：能力画像查询，产出 SkillProfileBlock + Evidence 驱动的回答。

画像数值来自 Java Profile Aggregator（证据等权均值），Agent 只做解读不改写：
上下文注入聚合分与证据明细，回答流式生成；无画像数据时引导先参加面试。
"""

import logging
from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_tool_completed, emit_tool_started
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import skill_profile_block
from career_copilot.agent.router import ActionRoute
from career_copilot.agent.state import CareerAgentState
from career_copilot.clients.backend import BusinessToolError
from career_copilot.schemas.message import ActionBlock
from career_copilot.tools import (
    format_history,
    latest_interview_session_id,
    summarize_profile_advice,
    summarize_skill_profile,
)

logger = logging.getLogger(__name__)


async def profile_query(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    """PROFILE_QUERY 分支：读画像 → 画像块 → 基于证据的流式解读。"""
    message = state.get("message") or ""
    history = format_history(
        state.get("history") or [],
        state.get("history_summary"),
        snapshot=state.get("user_snapshot"),
    )

    emit_tool_started("profile_query")
    profile = await deps.backend.get_skill_profile()
    emit_tool_completed("profile_query")

    skills = profile.get("skills") or []
    if not skills:
        # 如实告知无数据：画像由面试证据驱动，尚未有可追溯评分
        return {
            "plan": StreamPlan(
                blocks=[
                    ActionBlock(route=ActionRoute.INTERVIEW_CREATE.value, label="开始模拟面试")
                ],
                text=static_text(
                    "我还没有你的能力画像数据。画像由模拟面试的真实评分累积而来，"
                    "完成一场面试后我就能告诉你各技能的水平和证据来源。"
                ),
            )
        }

    # 本场变化（P6-3）：从画像证据里找最近一场面试，再取 Java 算好的前后分。
    # 差分是 Java 的业务判断（跨场聚合），Python 只取用；取不到就退化为画像事实，不自己算分。
    impact: dict[str, Any] = {}
    session_id = latest_interview_session_id(profile)
    if session_id:
        emit_tool_started("profile_impact")
        try:
            impact = await deps.backend.get_profile_impact(session_id)
        except BusinessToolError as exc:
            logger.info("画像差分读取失败，建议退化为画像事实: code=%s", exc.code)
        emit_tool_completed("profile_impact")

    context = (
        f"{summarize_skill_profile(profile)}\n\n"
        f"{summarize_profile_advice(profile, impact)}\n\n"
        "以上是可宣称的事实：没有证据的技能不要描述其水平或提升（只有简历声明 ≠ 已验证）；"
        "给建议时请指向可执行的动作（如针对某个待补强技能来一场定向面试）。"
    )
    return {
        "plan": StreamPlan(
            blocks=[skill_profile_block(profile)],
            text=deps.answerer.answer_stream(message, context, history or None),
        )
    }
