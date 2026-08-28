"""load_snapshot：新会话首轮注入用户快照（低成本跨会话感知）。

仅当会话没有任何历史时拉取（首轮判定）：top 技能画像 + 最近面试概要，
两个 READ Tool 并行调用。快照是「背景感知」而非完整数据——后续意图分支
真正需要画像/面试详情时仍走各自的 Tool 链路（Token 纪律）。

拉取失败静默降级为空快照，不阻断对话。
"""

import asyncio
from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_tool_completed, emit_tool_started
from career_copilot.agent.state import CareerAgentState
from career_copilot.tools import summarize_interviews, summarize_skill_profile

# 快照裁剪上限：画像 top N 技能 / 最近 N 场面试（背景感知够用，控制注入体积）
SNAPSHOT_SKILL_LIMIT = 3
SNAPSHOT_INTERVIEW_LIMIT = 3


async def load_snapshot(state: CareerAgentState, deps: GraphDeps) -> dict[str, Any]:
    # 非首轮（已有历史或滚动摘要）：不重复注入，背景感知交给会话记忆
    if state.get("history") or state.get("history_summary"):
        return {"user_snapshot": None}

    backend = deps.backend
    emit_tool_started("user_snapshot")

    # 两个只读 Tool 并行拉取；单边失败不阻断另一边
    profile_result, interviews_result = await asyncio.gather(
        backend.get_skill_profile(), backend.get_interview_history(),
        return_exceptions=True,
    )
    emit_tool_completed("user_snapshot")

    sections: list[str] = []
    if isinstance(profile_result, dict) and profile_result.get("skills"):
        sections.append(summarize_skill_profile(profile_result, limit=SNAPSHOT_SKILL_LIMIT))
    if isinstance(interviews_result, list) and interviews_result:
        sections.append(
            await summarize_interviews(interviews_result, limit=SNAPSHOT_INTERVIEW_LIMIT)
        )

    if not sections:
        return {"user_snapshot": None}

    snapshot = "【用户背景快照（新会话自动注入，回答时可自然引用）】\n" + "\n".join(sections)
    return {"user_snapshot": snapshot}
