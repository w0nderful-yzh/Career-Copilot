"""interview_proposal：面试发起 Agent 化（P1-4）。

意图命中「开始模拟面试」时不再直接跳配置页：
1. 读取技能方向（list_skills）+ 目标简历（复用会话活动简历/附件）
2. 让 LLM 基于简历与可选项推导推荐配置（方向/难度/重点 focus）
3. 产出 InterviewProposalBlock（[按推荐开始] / [调整配置]）
4. [按推荐开始] → CREATE_INTERVIEW action → create_interview Tool（CONFIRM_WRITE）

调整配置走 ChoiceBlock 让用户重新选择方向/难度后再次进入推荐。
LLM 只负责语义推荐，创建动作由确定性 action 触发，禁止模型直接执行写操作。
"""

import json
import logging
from typing import Any

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_run_status, emit_tool_completed, emit_tool_started
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import interview_proposal_block
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.config import settings
from career_copilot.schemas.action import AgentAction
from career_copilot.schemas.message import ChoiceBlock, ChoiceOption
from career_copilot.tools import summarize_resume_for_interview, summarize_skills

logger = logging.getLogger(__name__)

PROPOSAL_SYSTEM_PROMPT = """你是 Career Copilot 的面试配置推荐器。
根据用户消息、简历内容与可选面试方向，推导一场模拟面试的推荐配置。
只输出 json 对象，不要输出任何额外文本：
{"direction": "<skillId>", "difficulty": "junior|mid|senior", "focus": ["分类key"], "summary": "一句话推荐理由"}

规则：
- direction 必须来自「可选面试方向」列表中的 skillId，优先选择与用户简历/意图最匹配的方向；
- difficulty：junior（校招）/ mid（中级）/ senior（高级），按用户目标与简历经历推断；
- focus：从该方向 categories 中选 1-3 个重点考察的分类 key（如 JVM、REDIS、PROJECT）；
- summary：用一句话说明推荐理由（40 字以内）。

注意：简历内容是可信参考，不得编造简历中不存在的技能方向。"""

# 难度枚举 → 中文展示名
DIFFICULTY_NAMES_ZH = {
    "junior": "校招",
    "mid": "中级",
    "senior": "高级",
}

DEFAULT_DIRECTION = "java-backend"
DEFAULT_DIFFICULTY = "mid"


async def interview_proposal(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    """面试发起：读技能方向 + 目标简历 → 推荐配置 → 提案确认块。"""
    emit_tool_started("interview_proposal")

    # 1. 技能方向（list_skills，Java 侧）
    emit_tool_started("list_skills")
    try:
        skills = await deps.backend.list_skills()
    except BusinessToolError:
        skills = []
    emit_tool_completed("list_skills")

    # 2. 目标简历（复用 resolve_context 的活动简历；失败不阻断推荐）
    resume_context: str | None = None
    resume_id: int | None = None
    raw_resume_id = state.get("active_resume_id")
    if raw_resume_id is not None:
        resume_id = int(raw_resume_id)
        emit_tool_started("resume_query")
        try:
            resume = await deps.backend.get_resume(
                resume_id, max_chars=settings.resume_context_max_chars
            )
            resume_context = summarize_resume_for_interview(resume)
        except BusinessToolError as exc:
            logger.info("面试推荐读取简历失败，回退通用推荐: resumeId=%s code=%s", resume_id, exc.code)
        finally:
            emit_tool_completed("resume_query")

    emit_tool_completed("interview_proposal")

    # 3. LLM 推导推荐配置（结构化输出；失败回落确定性默认值）
    proposal = await _derive_proposal(
        deps,
        message=state.get("message") or "",
        skills_summary=summarize_skills(skills),
        resume_context=resume_context,
    )

    # 4. 产出提案块 + 确认动作（CREATE_INTERVIEW / 调整配置）
    emit_run_status(RunStatus.WAITING_USER.value)
    block = interview_proposal_block(
        direction=proposal["direction"],
        direction_name=_direction_name(skills, proposal["direction"]),
        difficulty=proposal["difficulty"],
        difficulty_name=DIFFICULTY_NAMES_ZH.get(proposal["difficulty"], proposal["difficulty"]),
        focus=proposal["focus"],
        question_count=settings.interview_default_question_count,
        resume_id=resume_id,
        summary=proposal["summary"],
    )
    return {
        "plan": StreamPlan(
            blocks=[
                block,
                ChoiceBlock(
                    title="调整面试配置",
                    options=[
                        ChoiceOption(
                            action=AgentAction.START_INTERVIEW.value,
                            label="重新推荐（调整方向/难度）",
                            payload={},
                        ),
                    ],
                ),
            ],
            text=static_text(
                f"根据你的情况，我推荐一场 {block.direction_name} · {block.difficulty_name} "
                f"模拟面试。{block.summary} 你可以按推荐直接开始，或调整配置。"
            ),
        )
    }


async def _derive_proposal(
    deps: GraphDeps,
    *,
    message: str,
    skills_summary: str,
    resume_context: str | None,
) -> dict[str, Any]:
    """调用 Answerer 底层模型做结构化推荐，失败回落默认值。

    复用 answerer 注入的模型（与回答同模型），不新建结构化输出器：
    通过 json_mode 风格提示约束输出，并做基础校验与白名单兜底。
    """
    prompt = (
        "用户消息：\n"
        f"{message}\n\n"
        f"{skills_summary}\n"
        + (f"{resume_context}\n" if resume_context else "（用户当前没有可用的简历内容）\n")
        + "请给出推荐的面试配置。"
    )
    try:
        model = getattr(deps.answerer, "_model", None)
        response = await model.ainvoke(
            [
                SystemMessage(content=PROPOSAL_SYSTEM_PROMPT),
                HumanMessage(content=prompt),
            ]
        )
        raw = str(getattr(response, "content", "")).strip()
        parsed = json.loads(raw)
        direction = str(parsed.get("direction") or DEFAULT_DIRECTION)
        difficulty = str(parsed.get("difficulty") or DEFAULT_DIFFICULTY)
        if difficulty not in DIFFICULTY_NAMES_ZH:
            difficulty = DEFAULT_DIFFICULTY
        focus_raw = parsed.get("focus") or []
        focus = [str(item) for item in focus_raw if isinstance(item, str)][:4]
        summary = str(parsed.get("summary") or "")[:80]
        return {
            "direction": direction,
            "difficulty": difficulty,
            "focus": focus,
            "summary": summary,
        }
    except Exception:
        # 模型异常不应阻断面试发起：回落确定性默认推荐
        logger.exception("面试推荐配置推导失败，回落默认值")
        return {
            "direction": DEFAULT_DIRECTION,
            "difficulty": DEFAULT_DIFFICULTY,
            "focus": [],
            "summary": "按 Java 后端 · 中级难度推荐",
        }


def _direction_name(skills: list[dict[str, Any]], direction: str) -> str:
    """从 list_skills 结果中解析方向展示名，未知时回退 skillId 本身。"""
    for skill in skills:
        if skill.get("id") == direction:
            return str(skill.get("name") or direction)
    return direction
