"""resolve_context：恢复当前 Conversation 的活动业务资源引用。

只加载 Reference（resumeId / jobId / planId），真正需要业务内容时再由 Tool 读取。
优先级：附件显式指定 > 会话绑定（Conversation Memory）；
plan 待 Preparation 落地后扩展。
"""

from typing import Any

from career_copilot.agent.state import CareerAgentState


def resolve_context(state: CareerAgentState) -> dict[str, Any]:
    active_resume_id: int | None = None
    active_job_id: int | None = None
    for attachment in state.get("attachments") or []:
        kind = attachment.get("kind")
        if kind == "resume" and attachment.get("resume_id") is not None:
            active_resume_id = int(attachment["resume_id"])
        elif kind == "job_description" and attachment.get("job_id") is not None:
            active_job_id = int(attachment["job_id"])
    if active_resume_id is None:
        # 无附件：回退会话绑定（上一轮定向分析的简历，跨轮恢复目标）
        bound = state.get("bound_resume_id")
        if bound is not None:
            active_resume_id = int(bound)
    if active_job_id is None:
        bound_job = state.get("bound_job_id")
        if bound_job is not None:
            active_job_id = int(bound_job)
    return {
        "active_resume_id": active_resume_id,
        "active_job_id": active_job_id,
        "active_plan_id": None,
    }
