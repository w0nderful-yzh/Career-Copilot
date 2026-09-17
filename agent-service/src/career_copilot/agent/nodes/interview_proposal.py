"""interview_proposal：面试发起 Agent 化（P1-4）。

意图命中「开始模拟面试」时不再直接跳配置页：
1. 读取技能方向（list_skills）+ 目标简历（复用会话活动简历/附件）
2. 让 LLM 基于简历与可选项推导推荐配置（方向/难度/重点 focus）
3. 产出 InterviewProposalBlock（[按推荐开始] / [调整配置]）
4. [按推荐开始] → CREATE_INTERVIEW action → create_interview Tool（CONFIRM_WRITE）

手动调整配置由前端内联面板完成（本地 state → CREATE_INTERVIEW，不发聊天消息）；
自然语言调整直接在 Composer 输入（如「难度高一点，多问 JVM」），由本节点的 LLM
结合用户消息重新推荐。LLM 只负责语义推荐，创建动作由确定性 action 触发。
"""

import json
import logging
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_run_status, emit_tool_completed, emit_tool_started
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import interview_proposal_block
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.config import settings
from career_copilot.tools import (
    summarize_resume_version,
    summarize_skill_profile,
    summarize_skills,
)

logger = logging.getLogger(__name__)

PROPOSAL_SYSTEM_PROMPT = """你是 Career Copilot 的面试配置推荐器。
根据用户消息、简历内容与可选面试方向，推导一场模拟面试的推荐配置。
只输出 json 对象，不要输出任何额外文本：
{
  "direction": "<skillId>",
  "difficulty": "junior|mid|senior",
  "focus": ["分类key"],
  "summary": "一句话推荐理由"
}

规则：
- direction 必须来自「可选面试方向」列表中的 skillId，优先选择与用户简历/意图最匹配的方向；
- difficulty：junior（校招）/ mid（中级）/ senior（高级），按用户目标与简历经历推断；
- focus：从**所选方向**的 categories 中选 1-3 个重点考察的分类 key（如 JVM、REDIS、PROJECT）；
- summary：用一句话说明推荐理由（40 字以内）。

重点考察（focus）的挑选依据：
- 优先选「画像参考」里分数偏低、以及「简历已列但尚无评分（从未考过）」的技能所对应的分类；
  从没考过的技能信息量最大，应该被优先安排；
- focus 只能取自所选方向的 categories，不要臆造分类名；
- 若画像没有可参考的信息，按简历与用户意图挑最相关、最能拉开区分度的分类。

注意：简历与画像内容是可信参考，不得编造其中不存在的技能方向。"""

# 难度枚举 → 中文展示名
DIFFICULTY_NAMES_ZH = {
    "junior": "校招",
    "mid": "中级",
    "senior": "高级",
}

DEFAULT_DIRECTION = "java-backend"
DEFAULT_DIFFICULTY = "mid"

# 「低分」阈值：与前端画像色阶一致（<60 为待提升），用于把需要补强的技能选进 focus
LOW_SCORE_THRESHOLD = 60

# focus 上限：与 prompt 里的「1-3 个」保持同量级，留一点冗余
MAX_FOCUS = 4

# 画像参考注入 Prompt 的技能条数上限（Token 纪律）
PROFILE_SKILL_LIMIT = 6


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

    # 2. 技能画像（P3 待收口）：低分技能 + 简历已列未考 → 决定重点考察方向。
    #    失败不阻断推荐：没有画像时退化为「按简历与意图推荐」。
    emit_tool_started("get_skill_profile")
    try:
        profile = await deps.backend.get_skill_profile()
    except BusinessToolError as exc:
        logger.info("面试推荐读取技能画像失败，跳过重点考察推荐: code=%s", exc.code)
        profile = {}
    emit_tool_completed("get_skill_profile")

    # 3. 目标简历：优先用**已确认的结构化版本**（与简历优化同一取数入口）。
    #    明确指定了简历却读不到时如实说明原因，不静默退化成通用面试——
    #    用户以为在面自己的简历、实际拿到通用题，是最糟的失败方式（P4Q-1）。
    resume_context: str | None = None
    resume_id: int | None = None
    raw_resume_id = state.get("active_resume_id")
    if raw_resume_id is not None:
        resume_id = int(raw_resume_id)
        emit_tool_started("resume_version")
        try:
            version = await deps.backend.get_resume_version(resume_id)
        except BusinessToolError as exc:
            logger.info("面试推荐读取简历版本失败: resumeId=%s code=%s", resume_id, exc.code)
            emit_tool_completed("resume_version")
            emit_tool_completed("interview_proposal")
            return {"plan": StreamPlan(text=static_text(_resume_unavailable_message(exc)))}
        resume_context = summarize_resume_version(version)
        emit_tool_completed("resume_version")

    emit_tool_completed("interview_proposal")

    # 4. LLM 推导推荐配置（结构化输出；失败回落确定性默认值 + 画像候选）
    proposal = await _derive_proposal(
        deps,
        message=state.get("message") or "",
        skills=skills,
        skills_summary=summarize_skills(skills),
        resume_context=resume_context,
        profile=profile,
        profile_summary=summarize_skill_profile(profile, limit=PROFILE_SKILL_LIMIT),
    )

    # 5. 产出提案确认块（Interview Mode 重构：手动调整由前端内联面板完成，
    #    不再下发「重新推荐」Choice 触发聊天消息；自然语言调整直接在 Composer 输入，
    #    由 LLM 结合本条消息（含用户调整诉求）重新推荐）
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
            blocks=[block],
            text=static_text(
                f"根据你的情况，我推荐一场 {block.direction_name} · {block.difficulty_name} "
                f"模拟面试。{block.summary} 你可以按推荐直接开始，或点「调整配置」手动修改，"
                "也可以直接告诉我想要的调整（如「难度高一点，多问 JVM」）。"
            ),
        )
    }


async def _derive_proposal(
    deps: GraphDeps,
    *,
    message: str,
    skills: list[dict[str, Any]],
    skills_summary: str,
    resume_context: str | None,
    profile: dict[str, Any],
    profile_summary: str,
) -> dict[str, Any]:
    """调用 Answerer 底层模型做结构化推荐，失败回落默认值。

    复用 answerer 注入的模型（与回答同模型），不新建结构化输出器：
    通过 json_mode 风格提示约束输出，并做基础校验与白名单兜底。

    focus 遵循「LLM 判语义、代码控边界」：模型负责把画像里的技能名（如 JVM）
    语义映射到方向的分类，但只有**确实存在于该方向 categories** 的分类才会下发，
    否则用户会看到一个永远不会被考到的重点。
    """
    prompt = (
        "用户消息：\n"
        f"{message}\n\n"
        f"{skills_summary}\n"
        + (f"{resume_context}\n" if resume_context else "（用户当前没有可用的简历内容）\n")
        + f"{profile_summary}\n"
        + "请给出推荐的面试配置。"
    )
    try:
        model = getattr(deps.answerer, "_model", None)
        if model is None:
            # 模型未注入（配置缺失）属于可恢复场景：走 except 回落确定性默认推荐
            raise RuntimeError("answerer 未注入模型，无法推导面试推荐配置")
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
        categories = _direction_categories(skills, direction)
        focus = _sanitize_focus(
            [str(item) for item in focus_raw if isinstance(item, str)], categories
        )
        if not focus:
            # 模型没给出可用分类（或全被白名单拦掉）时，用画像的确定性候选兜底
            focus = _profile_focus_hints(profile, categories)
        summary = str(parsed.get("summary") or "")[:80]
        return {
            "direction": direction,
            "difficulty": difficulty,
            "focus": focus,
            "summary": summary,
        }
    except Exception:
        # 模型异常不应阻断面试发起：回落确定性默认推荐（focus 仍尽量取画像候选）
        logger.exception("面试推荐配置推导失败，回落默认值")
        return {
            "direction": DEFAULT_DIRECTION,
            "difficulty": DEFAULT_DIFFICULTY,
            "focus": _profile_focus_hints(
                profile, _direction_categories(skills, DEFAULT_DIRECTION)
            ),
            "summary": "按 Java 后端 · 中级难度推荐",
        }


def _direction_categories(skills: list[dict[str, Any]], direction: str) -> list[dict[str, str]]:
    """所选方向的分类清单（key + label）；方向不存在时返回空。"""
    for skill in skills:
        if skill.get("id") != direction:
            continue
        return [
            {
                "key": str(category.get("key")),
                "label": str(category.get("label") or category.get("key")),
            }
            for category in (skill.get("categories") or [])
            if category.get("key")
        ]
    return []


def _sanitize_focus(
    raw_focus: list[str], categories: list[dict[str, str]]
) -> list[str]:
    """把 focus 收进该方向真实存在的分类，返回分类 key 列表。

    匹配顺序：key 精确 → label 精确 → 双向子串（容忍「SQL」对上「MySQL」这类表述）。
    三条都不中说明该分类不存在于本方向，直接丢弃——把不存在的分类下发给 Java
    只会得到「未命中、按原方向全量出题」，等于白算一轮。
    """
    if not categories or not raw_focus:
        return []
    by_key = {category["key"].lower(): category["key"] for category in categories}
    by_label = {category["label"].lower(): category["key"] for category in categories}

    matched: list[str] = []
    for item in raw_focus:
        name = str(item).strip().lower()
        if not name:
            continue
        target = by_key.get(name) or by_label.get(name)
        if target is None:
            target = next(
                (
                    category["key"]
                    for category in categories
                    if name in category["label"].lower()
                    or category["label"].lower() in name
                ),
                None,
            )
        if target and target not in matched:
            matched.append(target)
        if len(matched) >= MAX_FOCUS:
            break
    return matched


def _profile_focus_hints(
    profile: dict[str, Any], categories: list[dict[str, str]]
) -> list[str]:
    """画像 → 该方向的 focus 候选（确定性，不依赖 LLM）。

    优先级：**简历已列但从未考过**（信息量最大）> 已考但低分。
    两类都只保留能匹配到本方向分类的技能；匹配不上的技能名无法安全映射
    （例如 JVM 之于 java-backend 的分类体系），交给 LLM 的语义判断去处理。
    """
    if not categories or not profile:
        return []

    ordered: list[str] = [
        str(item.get("skill") or "")
        for item in (profile.get("declaredSkills") or [])
        if isinstance(item, dict)
    ]
    low_scores = sorted(
        (
            skill
            for skill in (profile.get("skills") or [])
            if isinstance(skill, dict) and isinstance(skill.get("score"), int)
        ),
        key=lambda skill: skill["score"],
    )
    ordered.extend(
        str(skill.get("skill") or "")
        for skill in low_scores
        if skill["score"] < LOW_SCORE_THRESHOLD
    )
    return _sanitize_focus(ordered, categories)


def _direction_name(skills: list[dict[str, Any]], direction: str) -> str:
    """从 list_skills 结果中解析方向展示名，未知时回退 skillId 本身。"""
    for skill in skills:
        if skill.get("id") == direction:
            return str(skill.get("name") or direction)
    return direction


def _resume_unavailable_message(exc: BusinessToolError) -> str:
    """简历读不到时的**可见原因与下一步**（不静默变成通用面试）。"""
    return (
        f"这份简历现在还不能用来出题：{exc.message}。"
        "你可以先在简历库完成解析并确认版本，然后让我重新推荐；"
        "也可以直接说「来一场通用面试」，我就不参考简历。"
    )
