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

import logging
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.events import emit_run_status, emit_tool_completed, emit_tool_started
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.response import interview_proposal_block
from career_copilot.agent.state import CareerAgentState, RunStatus
from career_copilot.clients.backend import BusinessToolError
from career_copilot.config import settings
from career_copilot.prompts import load
from career_copilot.tools import (
    profile_reasons_for,
    summarize_resume_version,
    summarize_skill_profile,
    summarize_skills,
)

logger = logging.getLogger(__name__)

# 提示词已迁至 career_copilot/prompts/interview_proposal.md（带 id 与版本）
# 难度枚举 → 中文展示名
DIFFICULTY_NAMES_ZH = {
    "junior": "校招",
    "mid": "中级",
    "senior": "高级",
}

class _ProposalDraft(BaseModel):
    """LLM 返回的推荐配置草案。

    focus 用宽松类型（list[Any]）：模型偶尔给数字或对象，语义过滤交给
    _sanitize_focus 的白名单——不该因为一个元素类型不对就让整次推荐回落到默认值。
    """

    direction: str | None = None
    difficulty: str | None = None
    focus: list[Any] = Field(default_factory=list)
    planned_duration_minutes: int | None = None
    required_topics: list[Any] = Field(default_factory=list)
    summary: str | None = None


DEFAULT_DIRECTION = "java-backend"
DEFAULT_DIFFICULTY = "mid"

# 「低分」阈值：与前端画像色阶一致（<60 为待提升），用于把需要补强的技能选进 focus
LOW_SCORE_THRESHOLD = 60

# focus 上限：与 prompt 里的「1-3 个」保持同量级，留一点冗余
MAX_FOCUS = 4

# 画像参考注入 Prompt 的技能条数上限（Token 纪律）
PROFILE_SKILL_LIMIT = 6

MIN_DURATION_MINUTES = 5
MAX_DURATION_MINUTES = 120


async def interview_proposal(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    """面试发起：读技能方向 + 目标简历 → 推荐配置 → 提案确认块。

    <p>P4-6b：从画像低分项「一键定向」发起时，动作载荷会带 `focusSkill`——
    它是用户的明确选择，**优先级高于模型推荐**：能对上方向分类就强制进重点与必要覆盖，
    对不上就如实说明（不悄悄换成一个考不到的重点）。
    """
    emit_tool_started("interview_proposal")
    focus_skill = _requested_focus_skill(state)

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
        focus_skill=focus_skill,
    )

    # 5. 产出提案确认块（Interview Mode 重构：手动调整由前端内联面板完成，
    #    不再下发「重新推荐」Choice 触发聊天消息；自然语言调整直接在 Composer 输入，
    #    由 LLM 结合本条消息（含用户调整诉求）重新推荐）
    emit_run_status(RunStatus.WAITING_USER.value)
    # 依据要解释**实际推荐的重点**：用户点了按钮就用他指定的那个，
    # 否则用推导后的首个重点——不然「建议补强 Java」到了提案卡上就只剩一句泛泛的推荐理由
    explained_skill = focus_skill or (proposal["focus"][0] if proposal["focus"] else None)
    reasons = profile_reasons_for(profile, explained_skill)
    if focus_skill and not proposal["requested_focus_applied"]:
        # 用户的指定没落到本方向分类上：如实说明，不假装考得到（P6-3：无依据不宣称）
        reasons.append(f"{focus_skill} 不在推荐方向的考察分类里，本场先按方向推荐")
    block = interview_proposal_block(
        direction=proposal["direction"],
        direction_name=_direction_name(skills, proposal["direction"]),
        difficulty=proposal["difficulty"],
        difficulty_name=DIFFICULTY_NAMES_ZH.get(proposal["difficulty"], proposal["difficulty"]),
        focus=proposal["focus"],
        planned_duration_minutes=proposal["planned_duration_minutes"],
        required_topics=proposal["required_topics"],
        resume_id=resume_id,
        summary=proposal["summary"],
        reasons=reasons,
    )
    return {
        "plan": StreamPlan(
            blocks=[block],
            text=static_text(
                f"根据你的情况，我推荐一场约 {block.planned_duration_minutes} 分钟的 "
                f"{block.direction_name} · {block.difficulty_name} 模拟面试。{block.summary} "
                "你可以按推荐直接开始，或点「调整配置」手动修改，"
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
    focus_skill: str | None = None,
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
        prompt_ref = load("interview_proposal")
        # 结构化契约与有限修复都在 executor 里；失败按错误分类抛出，由下面 except 兜住
        draft = (
            await deps.llm.json(
                prompt_ref,
                [SystemMessage(content=prompt_ref.text), HumanMessage(content=prompt)],
                _ProposalDraft,
            )
        ).unwrap()
        direction = draft.direction or DEFAULT_DIRECTION
        difficulty = draft.difficulty or DEFAULT_DIFFICULTY
        if difficulty not in DIFFICULTY_NAMES_ZH:
            difficulty = DEFAULT_DIFFICULTY
        focus_raw = draft.focus
        categories = _direction_categories(skills, direction)
        focus = _sanitize_focus(
            [str(item) for item in focus_raw if isinstance(item, str)], categories
        )
        if not focus:
            # 模型没给出可用分类（或全被白名单拦掉）时，用画像的确定性候选兜底
            focus = _profile_focus_hints(profile, categories)
        required_topics = _sanitize_focus(
            [str(item) for item in draft.required_topics if isinstance(item, str)],
            categories,
        )
        if not required_topics:
            # 提案说是重点，就至少触及其中前两个；Java 创建后还会剔除候选池不存在的话题。
            required_topics = focus[:2]
        focus, required_topics, applied = _apply_requested_focus(
            focus, required_topics, focus_skill, categories
        )
        summary = (draft.summary or "")[:80]
        return {
            "direction": direction,
            "difficulty": difficulty,
            "focus": focus,
            "planned_duration_minutes": _normalize_duration(
                draft.planned_duration_minutes
            ),
            "required_topics": required_topics,
            "summary": summary,
            "requested_focus_applied": applied,
        }
    except Exception:
        # 模型异常不应阻断面试发起：回落确定性默认推荐（focus 仍尽量取画像候选）
        logger.exception("面试推荐配置推导失败，回落默认值")
        fallback_focus = _profile_focus_hints(
            profile, _direction_categories(skills, DEFAULT_DIRECTION)
        )
        applied = focus_skill is None
        return {
            "direction": DEFAULT_DIRECTION,
            "difficulty": DEFAULT_DIFFICULTY,
            "focus": fallback_focus,
            "planned_duration_minutes": settings.interview_default_duration_minutes,
            "required_topics": fallback_focus[:2],
            "summary": "按 Java 后端 · 中级难度推荐",
            "requested_focus_applied": applied,
        }


def _requested_focus_skill(state: CareerAgentState) -> str | None:
    """从动作载荷取用户指定的重点技能（画像低分项「一键定向」发起时带上）。"""
    action = state.get("action") or {}
    payload = action.get("payload") or {}
    raw = payload.get("focusSkill")
    if not isinstance(raw, str) or not raw.strip():
        return None
    return raw.strip()


def _apply_requested_focus(
    focus: list[str],
    required_topics: list[str],
    focus_skill: str | None,
    categories: list[dict[str, str]],
) -> tuple[list[str], list[str], bool]:
    """把用户指定的重点技能强制放进 focus 与必要覆盖。

    用户的选择优先于模型推荐：能对上本方向分类就置顶；对不上则原样返回并让调用方如实说明，
    不悄悄换一个考不到的重点（那会让用户以为在补强 A、实际一直在考 B）。
    """
    if not focus_skill:
        return focus, required_topics, True
    matched = next(
        (
            category["key"]
            for category in categories
            if str(category.get("label", "")).strip().lower() == focus_skill.strip().lower()
            or str(category.get("key", "")).strip().lower() == focus_skill.strip().lower()
        ),
        None,
    )
    if matched is None:
        return focus, required_topics, False
    return _dedupe_first(matched, focus), _dedupe_first(matched, required_topics), True


def _dedupe_first(head: str, items: list[str]) -> list[str]:
    """把 head 放到首位并去重（保留原有顺序）。"""
    return [head] + [item for item in items if item != head]


def _normalize_duration(value: int | None) -> int:
    """预计时长由用户意图决定，但服务端边界固定为 5-120 分钟。"""
    if value is None:
        return settings.interview_default_duration_minutes
    return max(MIN_DURATION_MINUTES, min(MAX_DURATION_MINUTES, value))


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
