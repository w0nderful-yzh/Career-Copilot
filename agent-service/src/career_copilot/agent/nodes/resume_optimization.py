"""resume_optimization：简历优化子图（P2-1），替换原 stub 占位。

流程（无状态回合 + 提案持久化 HITL，不使用 LangGraph interrupt）：
1. resolve_resume：活动简历（附件 > 会话绑定，resolve_context 已处理）
2. load_resume_version：结构化版本（无 ACTIVE 版本 → 引导先完成解析确认）
3. load_profile：技能画像注入描述强度约束（P3 消费点）
4. generate_patch：LLM 结构化输出 JSON-path Patch
5. validate_patch：代码校验器（REORDER 拒绝 + 真实性双保险）
6. 提案落 Java（审计追溯）→ ResumeOptimizationBlock + WAITING_USER

应用修改由用户在 Block 上选择后经 APPLY_RESUME_PATCHES action 触发
（P2-1c 的 apply_resume_patches CONFIRM_WRITE Tool）。
"""

import json
import logging
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_run_status, emit_tool_completed, emit_tool_started
from career_copilot.agent.nodes.patch_validator import validate_patches
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import resume_optimization_block
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.config import settings
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ChoiceBlock, ChoiceOption
from career_copilot.schemas.resume_patch import ResumePatchProposal
from career_copilot.tools import summarize_skill_profile

logger = logging.getLogger(__name__)

PATCH_SYSTEM_PROMPT = """你是 Career Copilot 的简历优化顾问。
基于简历结构化内容生成修改建议（Patch），每条建议用 JSON path 精确定位。

# 真实性铁律（违反会被代码校验器直接拒绝）
1. **禁止编造量化数字**：不得新增原文没有的 QPS、百分比、时间、数量。改写只能重组原文已有信息。
2. **禁止虚构经历/奖项/技术栈**：不得添加原文没有的项目、证书、技能。
3. **允许的优化**：表达精炼（动词开头、删除冗余）、技术名词规范（Java/Spring Boot 大小写）、
   突出技术职责、调整结构归属、删除重复内容。

# 输出格式
只输出 json 对象，不要输出任何额外文本，结构如下：
summary: 字符串，一句话总结本轮优化思路
patches: 数组，每项包含 id（patch_N）、type（REPLACE/ADD/DELETE）、
path（如 projects[0].bullets[1]）、oldValue（原文精确片段）、
newValue（改写后内容）、reason（修改理由一句话）

# 约束
- type 只能是 REPLACE / ADD / DELETE（REORDER 暂不支持）
- REPLACE/DELETE 的 oldValue 必须从原文精确摘录（应用时会做一致性校验）
- 一次给出 3-8 条高价值建议，宁缺毋滥；没有值得修改的就返回空 patches
- 描述强度如实：用户画像中某技能分数偏低时，避免「精通」「深入掌握」等超出门水平的表述"""

# 画像驱动的描述强度约束（P3 消费点：低分技能 → 谨慎表述）
_PROFILE_STRENGTH_HINT = """
# 用户技能画像（Evidence 驱动，描述强度约束）
{profile_summary}
写技能相关描述时，分数偏低（<60）的技能避免「精通/深入掌握」级表述，保持如实水平。"""

# JD 定向优化约束（P2-5：目标岗位 → 优先突出匹配点）
_JD_TARGETED_HINT = """
# 目标岗位 JD（定向优化坐标系）
{job_content}
优化时优先突出与上述 JD 匹配的经历与技能，把 JD 要求的关键词自然融入相关描述
（只允许重组原文已有信息，禁止编造新经历或新技能来凑匹配度）。"""

# 简历结构说明（LLM 需要知道 JSON 结构才能给出合法 path）
_RESUME_STRUCTURE_HINT = """
# 简历结构化内容（path 以此为坐标系）
{content_json}
"""


async def resume_optimization(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    """简历优化：目标简历 → 结构化版本 → Patch 提案（待用户确认）。"""
    raw_resume_id = state.get("active_resume_id")
    if raw_resume_id is None:
        return {
            "plan": StreamPlan(
                text=static_text(
                    "请先告诉我要优化哪份简历（或把简历文件发给我），"
                    "我再基于简历内容给出具体的修改建议。"
                )
            )
        }
    resume_id = int(raw_resume_id)

    # 1. 结构化版本（简历优化取数基础；解析未确认时如实引导）
    emit_tool_started("resume_version")
    try:
        version = await deps.backend.get_resume_version(resume_id)
    except BusinessToolError as exc:
        emit_tool_completed("resume_version")
        return {
            "plan": StreamPlan(
                text=static_text(
                    "这份简历还没有完成结构化解析确认（解析结果需要你在简历库确认后"
                    "才能开始优化）。请先在简历库完成「解析确认」，再回来让我优化。"
                    f"（原因：{exc.message}）"
                )
            )
        }
    emit_tool_completed("resume_version")

    # 2. 画像注入描述强度约束（P3 消费点；失败不阻断）
    emit_tool_started("profile_query")
    try:
        profile = await deps.backend.get_skill_profile()
        profile_summary = summarize_skill_profile(profile, limit=5)
    except BusinessToolError:
        profile_summary = ""
    emit_tool_completed("profile_query")

    # 2.5 JD 上下文（P2-5）：会话绑定或 action 回传的 active_job_id 存在时
    # 注入 JD 全文（截断），点亮 JD_TARGETED 定向优化；失败不阻断（回落通用优化）
    jd_context = ""
    raw_job_id = state.get("active_job_id")
    if raw_job_id is not None:
        emit_tool_started("job_query")
        try:
            job = await deps.backend.get_job(int(raw_job_id))
            jd_text = (job.get("contentText") or "")[: settings.jd_context_max_chars]
            if jd_text:
                jd_context = (
                    f"目标岗位：{job.get('title') or '未命名岗位'}"
                    f"（{job.get('company') or '公司未知'}）\n{jd_text}"
                )
        except BusinessToolError:
            jd_context = ""
        emit_tool_completed("job_query")

    # 3. LLM 生成 Patch 提案（结构化输出）
    emit_tool_started("generate_patch")
    content_json = json.dumps(version.get("content") or {}, ensure_ascii=False)
    resume_meta = await deps.backend.get_resume(
        resume_id, max_chars=settings.resume_context_max_chars
    )
    resume_text = resume_meta.get("resumeText") or ""
    try:
        proposal = await _generate_patches(
            deps,
            message=state.get("message") or "",
            content_json=content_json,
            profile_summary=profile_summary,
            jd_context=jd_context,
        )
    except Exception:
        # 模型输出解析失败：诚实回落「无建议」而非整轮报错
        # （简历优化不能瞎编建议，宁可不给；docstring 约定即此行为）
        logger.exception("优化提案生成失败，回落无建议回复: resumeId=%s", resume_id)
        emit_tool_completed("generate_patch")
        return {
            "plan": StreamPlan(
                text=static_text(
                    "这次没能生成有价值的优化建议（模型输出异常），暂时不做修改。"
                    "可以稍后再试，或告诉我想优化的具体方向（比如某个项目描述），我再仔细看。"
                )
            )
        }
    emit_tool_completed("generate_patch")

    # 4. 代码校验（REORDER 拒绝 + 真实性双保险）
    patches = proposal.patches
    validation = validate_patches(patches, resume_text)
    if validation.rejected:
        rejected_note = "；".join(
            f"{patches[index].id}（{reason}）" for index, reason in validation.rejected
        )
        logger.info("优化提案部分建议被校验拒绝: resumeId=%s rejected=%s", resume_id, rejected_note)
        patches = [
            patch for index, patch in enumerate(patches)
            if index not in {i for i, _ in validation.rejected}
        ]

    if not patches:
        return {
            "plan": StreamPlan(
                text=static_text(
                    "我仔细看过了这份简历，目前没有值得动手改的地方——"
                    "保持现有内容即可。如果你想针对某个方向（比如某个岗位 JD）"
                    "做定向优化，告诉我方向我再仔细看一遍。"
                )
            )
        }

    # 5. 提案落 Java（HITL 审计；用户确认后才应用）
    version_id = int(version.get("id") or 0)
    emit_tool_started("save_proposal")
    try:
        proposal_id = await deps.backend.create_optimization_proposal(
            resume_id=resume_id,
            source_version_id=version_id,
            optimization_type="GENERAL",
            summary=proposal.summary,
            patches=[patch.model_dump() for patch in patches],
        )
    except BusinessToolError as exc:
        emit_tool_completed("save_proposal")
        return {
            "plan": StreamPlan(
                text=static_text(f"优化提案保存失败：{exc.message}。请稍后重试。")
            )
        }
    emit_tool_completed("save_proposal")

    emit_run_status(RunStatus.WAITING_USER.value)
    block = resume_optimization_block(
        proposal_id=proposal_id,
        resume_id=resume_id,
        version_id=version_id,
        summary=proposal.summary,
        patches=patches,
        rejected_note=(
            f"已剔除 {_len_rejected(validation)} 条不合规建议"
            if validation.has_rejection
            else None
        ),
    )
    return {
        "plan": StreamPlan(
            blocks=[
                block,
                ChoiceBlock(
                    title="其他操作",
                    options=[
                        ChoiceOption(
                            action=AgentAction.START_INTERVIEW.value,
                            label="基于当前简历来一场模拟面试",
                            payload={},
                        ),
                    ],
                ),
            ],
            text=static_text(
                f"我针对这份简历给出了 {len(patches)} 条修改建议（见下方卡片）。"
                "每条都附了修改理由，你可以逐条勾选后应用；"
                "应用前原简历不会被改动。"
                + (f"注意：{block.rejectedNote}。" if getattr(block, "rejectedNote", None) else "")
            ),
        )
    }


def _len_rejected(validation: Any) -> int:
    return len(validation.rejected)


async def _generate_patches(
    deps: GraphDeps,
    *,
    message: str,
    content_json: str,
    profile_summary: str,
    jd_context: str = "",
) -> ResumePatchProposal:
    """调用 Answerer 底层模型生成 Patch 提案（json 输出 + Pydantic 校验）。

    模型输出解析失败时抛异常，由上层回落到「无建议」的诚实回复
    （简历优化不能瞎编建议，宁可不给）。
    """
    from pydantic import ValidationError

    prompt_parts = [
        f"用户消息：{message}" if message else "用户没有附加要求，请做通用优化。",
        _RESUME_STRUCTURE_HINT.format(content_json=content_json),
    ]
    if jd_context:
        prompt_parts.append(_JD_TARGETED_HINT.format(job_content=jd_context))
    if profile_summary:
        prompt_parts.append(_PROFILE_STRENGTH_HINT.format(profile_summary=profile_summary))
    prompt_parts.append("请生成本轮优化建议。")

    model = getattr(deps.answerer, "_model", None)
    if model is None:
        raise RuntimeError("Answerer 未注入模型，无法生成优化提案")
    response = await model.ainvoke(
        [
            SystemMessage(content=PATCH_SYSTEM_PROMPT),
            HumanMessage(content="\n".join(prompt_parts)),
        ]
    )
    raw = str(getattr(response, "content", "")).strip()
    # 容错：剥掉可能的 markdown 代码块围栏
    if raw.startswith("```"):
        raw = raw.split("```")[1]
        if raw.startswith("json"):
            raw = raw[4:]
    try:
        parsed = json.loads(raw)
        return ResumePatchProposal.model_validate(parsed)
    except (json.JSONDecodeError, ValidationError) as exc:
        logger.error("优化提案 JSON 解析失败: %s", exc)
        raise
