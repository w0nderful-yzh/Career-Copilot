"""attachment_flow：附件识别 → 文档类型 → 确定性引导。

V1 只识别简历附件（kind=resume），产出「已登记到简历库」说明 + ChoiceBlock，
让用户选择下一步（分析/优化/模拟面试/岗位匹配），而非默认入库行为。
未知文档类型时询问用户。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.state import CareerAgentState
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ChoiceBlock, ChoiceOption

# ChoiceBlock 文案：给简历附件的默认操作选择
RESUME_CHOICE_TITLE = "你想用它做什么？"


async def attachment_flow(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    attachments = state.get("attachments") or []
    resume = next(
        (att for att in attachments if att.get("kind") == "resume"), None
    )
    if resume is None:
        return {
            "plan": StreamPlan(
                text=static_text("我暂时无法识别这个文件的类型，目前支持上传 PDF 简历。")
            )
        }
    return _resume_attachment_plan(resume)


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