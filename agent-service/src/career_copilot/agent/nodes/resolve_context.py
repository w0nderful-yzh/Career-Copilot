"""resolve_context：恢复当前 Conversation 的活动业务资源引用。

V1 只加载 Reference（resumeId / jobId / planId），真正需要业务内容时再由 Tool 读取。
conversation 尚无资源绑定，resume_id 从简历附件推导；job/plan 恒为 None，
后续 Java 支持 conversation 绑定后再扩展。
"""

from typing import Any

from career_copilot.agent.state import CareerAgentState


def resolve_context(state: CareerAgentState) -> dict[str, Any]:
    active_resume_id: int | None = None
    for attachment in state.get("attachments") or []:
        if attachment.get("kind") == "resume" and attachment.get("resume_id") is not None:
            active_resume_id = int(attachment["resume_id"])
            break
    return {
        "active_resume_id": active_resume_id,
        "active_job_id": None,
        "active_plan_id": None,
    }