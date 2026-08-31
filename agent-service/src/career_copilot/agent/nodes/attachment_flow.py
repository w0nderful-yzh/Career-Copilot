"""attachment_flow：附件识别 → 文档类型 → 确定性引导。

识别简历附件（kind=resume）与 JD 附件（kind=job_description），产出
「已登记说明 + ChoiceBlock」，让用户选择下一步而非默认行为。
未知文档类型时询问用户。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_run_status
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ChoiceBlock, ChoiceOption

# ChoiceBlock 文案：给简历附件的默认操作选择
RESUME_CHOICE_TITLE = "你想用它做什么？"
JD_CHOICE_TITLE = "这份 JD 你想怎么用？"


async def attachment_flow(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    attachments = state.get("attachments") or []
    resume = next(
        (att for att in attachments if att.get("kind") == "resume"), None
    )
    if resume is not None:
        # 产出选择块即进入等待用户决策状态（前端可据此高亮待选项）
        emit_run_status(RunStatus.WAITING_USER.value)
        return _resume_attachment_plan(resume)

    job = next(
        (att for att in attachments if att.get("kind") == "job_description"), None
    )
    if job is not None:
        emit_run_status(RunStatus.WAITING_USER.value)
        return await _job_attachment_plan(state, deps, job)

    return {
        "plan": StreamPlan(
            text=static_text("我暂时无法识别这个文件的类型，目前支持上传 PDF 简历和岗位 JD。")
        )
    }


def _resume_attachment_plan(attachment: dict[str, Any]) -> dict[str, Any]:
    """简历附件的确定性确认：如实说明入库结果 + 让用户选择下一步。"""
    filename = attachment.get("filename") or "简历"
    resume_id = attachment.get("resume_id")

    if attachment.get("duplicate"):
        intro = (
            f"「{filename}」与简历库中的已有简历相同（ID: {resume_id}），"
            "已直接复用历史记录，没有重复上传。"
        )
    else:
        intro = f"已收到「{filename}」，并加入简历库（ID: {resume_id}），正在后台分析。"

    options = [
        ChoiceOption(
            action=AgentAction.ANALYZE_RESUME.value,
            label="分析简历",
            payload={"resumeId": resume_id},
        ),
        ChoiceOption(
            action=AgentAction.OPTIMIZE_RESUME.value,
            label="优化简历",
            payload={"resumeId": resume_id},
        ),
        ChoiceOption(
            action=AgentAction.START_INTERVIEW.value,
            label="模拟面试",
            payload={"resumeId": resume_id},
        ),
        ChoiceOption(
            action=AgentAction.JOB_MATCH.value,
            label="岗位匹配",
            payload={"resumeId": resume_id},
        ),
    ]
    return {
        "plan": StreamPlan(
            blocks=[
                ChoiceBlock(title=RESUME_CHOICE_TITLE, options=options),
            ],
            text=static_text(intro),
        )
    }


async def _job_attachment_plan(
    state: CareerAgentState, deps: GraphDeps, attachment: dict[str, Any]
) -> dict[str, Any]:
    """JD 附件的确定性确认：绑定会话 + 让用户选择用途（P2-5）。"""
    filename = attachment.get("filename") or "岗位 JD"
    job_id = attachment.get("job_id")

    # 绑定会话活动 JD（对称 P1-3 active_resume）：后续「按这份 JD 优化」无需重复上传；
    # 绑定失败不阻断（下一轮重新上传或指名也能走通）
    conversation_id = state.get("conversation_id")
    if conversation_id is not None and job_id is not None:
        try:
            await deps.backend.bind_active_job(int(conversation_id), int(job_id))
        except Exception:
            pass

    options = [
        ChoiceOption(
            action=AgentAction.OPTIMIZE_RESUME.value,
            label="按这份 JD 优化简历",
            payload={"jobId": job_id},
        ),
        ChoiceOption(
            action=AgentAction.JOB_MATCH.value,
            label="JD 与简历匹配分析",
            payload={"jobId": job_id},
        ),
        ChoiceOption(
            action=AgentAction.START_INTERVIEW.value,
            label="基于 JD 出题模拟面试",
            payload={"jobId": job_id},
        ),
    ]
    return {
        "plan": StreamPlan(
            blocks=[
                ChoiceBlock(title=JD_CHOICE_TITLE, options=options),
            ],
            text=static_text(
                f"已收到「{filename}」（JD ID: {job_id}），本会话后续会默认使用这份 JD。"
                "如果简历库里有多份简历，优化时会用最近一份；也可以先告诉我要优化哪份。"
            ),
        )
    }
