"""resume_optimization：简历优化子图（P2-1 / P2 待修正），替换原 stub 占位。

流程（无状态回合 + 提案持久化 HITL，不使用 LangGraph interrupt）：
1. resolve_resume：活动简历（附件 > 会话绑定，resolve_context 已处理）
2. determine_mode：确定性判定 GENERAL / TARGET_DIRECTION / JD_TARGETED
3. context_check：上下文不足才澄清（JD 缺失 / 方向未明），ChoiceBlock 确定性问询
4. load_resume_version：结构化版本（无 ACTIVE 版本 → 引导先完成解析确认）
5. load_jd / load_profile：定向坐标系 + 描述强度约束
6. generate_patch：LLM 结构化输出 JSON-path Patch
7. validate_patch：代码校验器（结构性 + 真实性：数字/技术栈/公司/经历事实）
8. 提案落 Java（含模式与目标 JD/方向，审计追溯）→ ResumeOptimizationBlock + WAITING_USER

模式判定为什么用确定性规则而非 LLM：模式直接决定落库的优化坐标系与 prompt 分支，
判错会让「按 JD 优化」退化成通用优化。规则是代码边界（显式方向 > JD 信号 > 会话绑定 JD > 通用），
可单测、可复现，不额外消耗一次模型调用。

应用修改由用户在 Block 上选择后经 APPLY_RESUME_PATCHES action 触发
（P2-1c 的 apply_resume_patches CONFIRM_WRITE Tool）。
"""

import json
import logging
import re
from enum import StrEnum
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
from career_copilot.schemas.resume_patch import ResumePatch, ResumePatchProposal
from career_copilot.tools import summarize_skill_profile

logger = logging.getLogger(__name__)


class OptimizationMode(StrEnum):
    """简历优化模式（与 Java 侧 OptimizationType 枚举严格一致）。"""

    GENERAL = "GENERAL"  # 通用优化：表达精炼、结构归属、规范名词
    TARGET_DIRECTION = "TARGET_DIRECTION"  # 目标方向优化：按某方向组织表达
    JD_TARGETED = "JD_TARGETED"  # JD 定向优化：按具体岗位 JD 匹配


# JD 信号词：命中即认为用户想按具体岗位 JD 优化
_JD_SIGNAL_PATTERN = re.compile(
    r"(?:jd|job\s*description|职位|岗位|招聘要求)",
    re.IGNORECASE,
)

# 方向抽取：只认「按/针对/面向 X 优化」这类显式表达，避免把普通改写要求误判为方向
_DIRECTION_PATTERNS = (
    re.compile(
        r"(?:按照|按|针对|面向|往|向)\s*[「“\"']?"
        r"(?P<d>[A-Za-z\u4e00-\u9fa5][^，。,.；;、\n]{0,18}?)"
        r"[」”\"']?\s*(?:方向|岗位|职位|来)?\s*(?:优化|修改|改写|调整|改)"
    ),
    re.compile(
        r"(?P<d>[A-Za-z\u4e00-\u9fa5][^，。,.；;、\n]{0,18}?)\s*"
        r"(?:方向|岗位|职位)\s*(?:的)?\s*(?:优化|修改|改写|调整|改)"
    ),
)

# 方向候选里的泛指词/指示词：说明用户只说「按方向优化」而没给具体方向 → 需要澄清
_GENERIC_DIRECTION_WORDS = frozenset({
    "", "我", "我的", "自己", "目标", "这个", "那个", "该", "此", "这份", "相关",
    "对应", "上述", "上面", "下面", "方向", "岗位", "职位", "简历", "jd",
})

# 指示词前缀：中文无词边界，抽到的候选可能是「这份JD」这类指代
_GENERIC_DIRECTION_PREFIXES = ("这", "那", "该", "此", "上述", "上面", "下面", "自己", "我")

# 澄清块给出的方向选项上限（Token 纪律 + 选择过载）
_MAX_DIRECTION_OPTIONS = 4


def determine_mode(message: str, has_job: bool) -> tuple[str, str | None]:
    """确定性模式判定：显式方向 > JD 信号 > 会话绑定 JD > 通用。

    返回 (mode, target_direction)。命中方向句式但未给出具体方向时返回
    (TARGET_DIRECTION, None)，由 context_check 澄清。
    """
    text = (message or "").strip()

    matched, direction = _extract_direction(text)
    if direction:
        return OptimizationMode.TARGET_DIRECTION.value, direction
    if _JD_SIGNAL_PATTERN.search(text):
        # 「按这份 JD 优化」会被方向句式捕获成「这份 JD」，此处按 JD 模式收敛
        return OptimizationMode.JD_TARGETED.value, None
    if matched:
        return OptimizationMode.TARGET_DIRECTION.value, None
    if has_job:
        # 会话已绑定 JD：沿用 P2-5 行为，默认对这份 JD 定向优化
        return OptimizationMode.JD_TARGETED.value, None
    return OptimizationMode.GENERAL.value, None


def _extract_direction(text: str) -> tuple[bool, str | None]:
    """抽取目标方向：返回 (是否命中方向句式, 具体方向)。"""
    for pattern in _DIRECTION_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        candidate = (match.group("d") or "").strip().rstrip("的").strip()
        if _is_generic_direction(candidate):
            return True, None
        return True, candidate
    return False, None


def _is_generic_direction(candidate: str) -> bool:
    """候选方向是否只是泛指/指代（「这份JD」「目标」「我的简历」），而非真实方向。"""
    normalized = candidate.replace(" ", "").lower()
    if not normalized or normalized in _GENERIC_DIRECTION_WORDS:
        return True
    if _JD_SIGNAL_PATTERN.search(candidate):
        return True
    return normalized.startswith(_GENERIC_DIRECTION_PREFIXES)


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

# 目标方向约束（P2 待修正：TARGET_DIRECTION → 按方向组织表达）
_TARGET_DIRECTION_HINT = """
# 目标方向（定向优化坐标系）
目标：{direction}
围绕该方向的岗位要求组织表达：优先突出与该方向相关的经历与技能，
把该方向的核心技术名词自然融入已有描述
（只允许重组原文已有信息，禁止编造新经历或新技能来凑匹配度）。"""

# 简历结构说明（LLM 需要知道 JSON 结构才能给出合法 path）
_RESUME_STRUCTURE_HINT = """
# 简历结构化内容（path 以此为坐标系）
{content_json}
"""

# 自评审（P2 待修正，默认关闭）：只淘汰不新增，防止改写绕过真实性校验
REVIEW_SYSTEM_PROMPT = """你是简历优化建议的评审员。
逐条评审修改建议是否值得采纳：表达是否更精炼/更专业、是否与原文事实一致、
是否与其它建议重复、是否属于无意义改写。

# 边界（违反会被代码丢弃）
- 只能决定「保留」或「淘汰」，禁止新增建议、禁止改写建议内容
- 只能使用给定的 patch id，臆造的 id 会被忽略

# 输出格式
只输出 json 对象，不要输出任何额外文本：
keep: 字符串数组，保留的 patch id
drop: 对象数组，每项包含 id、reason（一句话说明为什么淘汰）"""


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

    # 1. 模式判定（P2 待修正）：显式方向 > JD 信号 > 会话绑定 JD > 通用。
    #    澄清块回传的 mode/direction 优先（用户已在上一轮明确选择）。
    raw_job_id = state.get("active_job_id")
    job_id = int(raw_job_id) if raw_job_id is not None else None
    forced_mode = state.get("optimization_mode")
    if isinstance(forced_mode, str) and forced_mode in OptimizationMode.__members__:
        mode = forced_mode
        direction = state.get("target_direction")
    else:
        mode, direction = determine_mode(state.get("message") or "", job_id is not None)
    logger.info(
        "简历优化模式判定: resumeId=%s mode=%s direction=%s jobId=%s",
        resume_id, mode, direction, job_id,
    )

    # 2. 上下文不足才澄清（只问影响方向的问题）；澄清期间不拉版本、不生成建议
    clarification = await _context_check(
        deps, mode=mode, direction=direction, job_id=job_id, resume_id=resume_id
    )
    if clarification is not None:
        emit_run_status(RunStatus.WAITING_USER.value)
        return {"plan": clarification}

    # 3. 结构化版本（简历优化取数基础；解析未确认时如实引导）
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

    # 4. 画像注入描述强度约束（P3 消费点；失败不阻断）
    emit_tool_started("profile_query")
    try:
        profile = await deps.backend.get_skill_profile()
        profile_summary = summarize_skill_profile(profile, limit=5)
    except BusinessToolError:
        profile_summary = ""
    emit_tool_completed("profile_query")

    # 5. JD 上下文（P2-5）：仅 JD_TARGETED 注入 JD 全文（截断）；
    #    用户显式指定方向时以方向为准，不被会话绑定的 JD 覆盖；失败不阻断
    jd_context = ""
    if mode == OptimizationMode.JD_TARGETED.value and job_id is not None:
        emit_tool_started("job_query")
        try:
            job = await deps.backend.get_job(job_id)
            jd_text = (job.get("contentText") or "")[: settings.jd_context_max_chars]
            if jd_text:
                jd_context = (
                    f"目标岗位：{job.get('title') or '未命名岗位'}"
                    f"（{job.get('company') or '公司未知'}）\n{jd_text}"
                )
        except BusinessToolError:
            jd_context = ""
        emit_tool_completed("job_query")

    # 6. LLM 生成 Patch 提案（结构化输出）
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
            target_direction=direction,
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

    # 7. 代码校验（结构性 + 真实性：数字/技术栈/公司/经历事实）
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

    # 7.5 配置化自评审（默认关闭，见 settings.resume_self_review_rounds）。
    # 只允许「淘汰」不允许新增/改写——改写会绕过上面的确定性真实性校验；
    # 评审失败不阻断主流程（已通过校验的建议照常展示）。
    review_dropped: list[tuple[str, str]] = []
    if patches and settings.resume_self_review_rounds > 0:
        emit_tool_started("review_patches")
        try:
            patches, review_dropped = await _self_review_patches(
                deps,
                content_json=content_json,
                resume_text=resume_text,
                patches=patches,
                rounds=settings.resume_self_review_rounds,
            )
        except Exception:
            logger.exception("自评审失败，跳过评审直接展示已校验建议: resumeId=%s", resume_id)
        emit_tool_completed("review_patches")
        if review_dropped:
            logger.info(
                "自评审淘汰 %d 条建议: resumeId=%s ids=%s",
                len(review_dropped), resume_id, [pid for pid, _ in review_dropped],
            )

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

    # 8. 提案落 Java（HITL 审计；用户确认后才应用）。
    #    优化坐标系（模式 + 目标 JD / 目标方向）随提案落库，应用时透传到新版本，
    #    否则「按这份 JD 优化」在审计上会退化成通用优化。
    version_id = int(version.get("id") or 0)
    emit_tool_started("save_proposal")
    try:
        proposal_id = await deps.backend.create_optimization_proposal(
            resume_id=resume_id,
            source_version_id=version_id,
            optimization_type=mode,
            summary=proposal.summary,
            patches=[patch.model_dump() for patch in patches],
            target_job_id=job_id if mode == OptimizationMode.JD_TARGETED.value else None,
            target_direction=direction if mode == OptimizationMode.TARGET_DIRECTION.value else None,
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
    # 剔除事实如实告知：校验器剔除与自评审淘汰分开表述（两者依据不同）
    note_parts: list[str] = []
    if validation.has_rejection:
        note_parts.append(f"已剔除 {len(validation.rejected)} 条不合规建议")
    if review_dropped:
        note_parts.append(f"自评审淘汰 {len(review_dropped)} 条")
    block = resume_optimization_block(
        proposal_id=proposal_id,
        resume_id=resume_id,
        version_id=version_id,
        summary=proposal.summary,
        patches=patches,
        rejected_note="；".join(note_parts) or None,
        optimization_type=mode,
        target_direction=(
            direction if mode == OptimizationMode.TARGET_DIRECTION.value else None
        ),
    )
    mode_hint = _mode_hint(mode, direction)
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
                f"我{mode_hint}给出了 {len(patches)} 条修改建议（见下方卡片）。"
                "每条都附了修改理由，你可以逐条勾选后应用；"
                "应用前原简历不会被改动。"
                + (f"注意：{block.rejectedNote}。" if getattr(block, "rejectedNote", None) else "")
            ),
        )
    }


def _mode_hint(mode: str, direction: str | None) -> str:
    """把模式如实告诉用户，避免「按 JD 优化」被当成通用优化。"""
    if mode == OptimizationMode.JD_TARGETED.value:
        return "按这份 JD 定向"
    if mode == OptimizationMode.TARGET_DIRECTION.value and direction:
        return f"按「{direction}」方向"
    return ""


async def _context_check(
    deps: GraphDeps,
    *,
    mode: str,
    direction: str | None,
    job_id: int | None,
    resume_id: int,
) -> StreamPlan | None:
    """上下文不足时的澄清（只问影响方向的问题）。

    - JD_TARGETED 但拿不到 JD：请用户上传/发送 JD，或改走通用优化
    - TARGET_DIRECTION 但方向未明：用 Java 技能方向列表给出确定性选项
    信息够用时返回 None，不打扰用户。
    """
    if mode == OptimizationMode.JD_TARGETED.value and job_id is None:
        return StreamPlan(
            blocks=[
                ChoiceBlock(
                    title="还没有可用的 JD",
                    options=[
                        ChoiceOption(
                            action=AgentAction.OPTIMIZE_RESUME.value,
                            label="先按通用优化",
                            payload={"resumeId": resume_id, "mode": OptimizationMode.GENERAL.value},
                        ),
                    ],
                ),
            ],
            text=static_text(
                "你说要按 JD 优化，但我这边还没有这份 JD 的内容。"
                "可以把 JD 文件直接发给我（在输入框把附件类型切换成「JD」再上传），"
                "或者先按通用优化看看。"
            ),
        )

    if mode == OptimizationMode.TARGET_DIRECTION.value and not direction:
        options = await _direction_choices(deps, resume_id)
        if not options:
            # 拿不到可选方向就不追问（追问反而无解），直接按通用优化继续
            logger.info("方向列表不可用，跳过方向澄清: resumeId=%s", resume_id)
            return None
        return StreamPlan(
            blocks=[ChoiceBlock(title="想按哪个方向优化？", options=options)],
            text=static_text(
                "你想按某个方向优化简历，但我还不确定是哪个方向。"
                "选一个我再动手（也可以直接在消息里说明，比如「按 Java 后端方向优化」）。"
            ),
        )
    return None


async def _direction_choices(deps: GraphDeps, resume_id: int) -> list[ChoiceOption]:
    """澄清选项：复用 Java 技能方向列表（不臆造方向，失败则不给选项）。"""
    emit_tool_started("list_skills")
    try:
        skills = await deps.backend.list_skills()
    except BusinessToolError:
        skills = []
    emit_tool_completed("list_skills")

    options = [
        ChoiceOption(
            action=AgentAction.OPTIMIZE_RESUME.value,
            label=f"按「{skill.get('name')}」优化",
            payload={
                "resumeId": resume_id,
                "mode": OptimizationMode.TARGET_DIRECTION.value,
                "direction": str(skill.get("name")),
            },
        )
        for skill in skills[:_MAX_DIRECTION_OPTIONS]
        if skill.get("name")
    ]
    options.append(
        ChoiceOption(
            action=AgentAction.OPTIMIZE_RESUME.value,
            label="先按通用优化",
            payload={"resumeId": resume_id, "mode": OptimizationMode.GENERAL.value},
        )
    )
    return options


async def _self_review_patches(
    deps: GraphDeps,
    *,
    content_json: str,
    resume_text: str,
    patches: list[ResumePatch],
    rounds: int,
) -> tuple[list[ResumePatch], list[tuple[str, str]]]:
    """配置化自评审（P2 待修正）：至多 rounds 轮，只淘汰不新增。

    返回（保留的建议, [(被淘汰的 patch id, 理由)])。模型未注入 / 输出无法解析时
    由调用方捕获异常并保留原建议——自评审是可选增强，绝不能成为主流程的单点故障。

    边界（对齐「LLM 判语义、代码控边界」）：
    - 只接受对**已存在 patch id** 的淘汰决定，模型臆造的 id 一律忽略；
    - 淘汰后若集合无变化（模型给的 id 全不存在）立即终止，避免空转。
    """
    model = getattr(deps.answerer, "_model", None)
    if model is None:
        return patches, []

    current = list(patches)
    dropped: list[tuple[str, str]] = []
    for _ in range(max(0, rounds)):
        if not current:
            break
        response = await model.ainvoke(
            [
                SystemMessage(content=REVIEW_SYSTEM_PROMPT),
                HumanMessage(
                    content="\n".join([
                        _RESUME_STRUCTURE_HINT.format(content_json=content_json),
                        f"# 简历原文（真实性基准）\n{resume_text[:2000]}",
                        "# 待评审建议\n"
                        + json.dumps(
                            [
                                {
                                    "id": patch.id,
                                    "type": patch.type.value,
                                    "path": patch.path,
                                    "newValue": patch.newValue,
                                    "reason": patch.reason,
                                }
                                for patch in current
                            ],
                            ensure_ascii=False,
                        ),
                        "请给出保留/淘汰结论。",
                    ])
                ),
            ]
        )
        verdict = json.loads(_strip_code_fence(str(getattr(response, "content", "")).strip()))
        drop_map = {
            str(item.get("id")): str(item.get("reason") or "自评审认为价值不足")
            for item in (verdict.get("drop") or [])
            if isinstance(item, dict) and item.get("id")
        }
        known_ids = {patch.id for patch in current}
        effective = {pid: reason for pid, reason in drop_map.items() if pid in known_ids}
        if not effective:
            break
        kept: list[ResumePatch] = []
        for patch in current:
            if patch.id in effective:
                dropped.append((patch.id, effective[patch.id]))
            else:
                kept.append(patch)
        current = kept
    return current, dropped


def _strip_code_fence(raw: str) -> str:
    """剥掉模型可能输出的 markdown 代码块围栏，返回纯 JSON 文本。"""
    if not raw.startswith("```"):
        return raw
    body = raw.split("```")[1]
    return body[4:] if body.startswith("json") else body


async def _generate_patches(
    deps: GraphDeps,
    *,
    message: str,
    content_json: str,
    profile_summary: str,
    jd_context: str = "",
    target_direction: str | None = None,
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
    if target_direction:
        prompt_parts.append(_TARGET_DIRECTION_HINT.format(direction=target_direction))
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
    raw = _strip_code_fence(str(getattr(response, "content", "")).strip())
    try:
        parsed = json.loads(raw)
        return ResumePatchProposal.model_validate(parsed)
    except (json.JSONDecodeError, ValidationError) as exc:
        logger.error("优化提案 JSON 解析失败: %s", exc)
        raise
