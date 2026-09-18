package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredCallPolicy;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluationRequest;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 逐题轻量评估服务（P4-2）。
 *
 * 回答后同步对「当前这一题」做轻量结构化评估，只为决策引擎提供输入：
 * score / answerState / covered·missingPoints（对齐期望要点）/ recommendedFocus。
 * 完整报告仍由整场异步评估产出，本服务不落库、不做长篇反馈。
 *
 * 延迟与容错：
 * - 「不会 / 跳过」等短回答直接短路 NO_ANSWER，不调 LLM；
 * - 模型失败经 StructuredOutputInvoker 重试后仍失败 → UNKNOWN，无评分并停止追问；
 * - prompt 只含当前题、回答与三类参照物（简历片段 / 最近相关问答 / 画像基线），
 *   以及 P4Q-2 的覆盖摘要 / 预算 / 合法候选，均已按预算裁剪。
 */
@Service
public class TurnEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TurnEvaluationService.class);

    /**
     * 简历片段上限（P4Q-1）。
     *
     * <p>逐轮上下文直接吃 P95 ≤ 3s 的预算，所以只取与当前题相关的一段而不是整份简历；
     * 这个数是先给的默认值，实测后再调（调它之前先看逐轮耗时的实测数据）。
     */
    static final int MAX_RESUME_SNIPPET_CHARS = 800;

    /** 最近相关问答轮数上限：再多也只是重复同类信息，代价是每轮都在付 */
    static final int MAX_RECENT_TURNS = 2;

    /** 单条问答的截断长度（历史问答只作参照，不需要全文） */
    private static final int MAX_HISTORY_ANSWER_CHARS = 120;

    private static final String NO_RESUME_CONTEXT = "（本场没有简历依据，不要假定候选人有过任何经历）";
    private static final String NO_RECENT_TURNS = "（该技能此前没有已回答的轮次）";
    private static final String NO_PROFILE_BASELINE = "（该技能暂无画像数据，按题目难度独立判断）";
    private static final String NO_COVERAGE_CONTEXT = "（本场没有提供覆盖上下文，不要假定任何话题已覆盖）";
    private static final String NO_BUDGET_CONTEXT = "（本场没有提供时间与追问预算，不要假定任何剩余额度）";
    private static final String NO_LEGAL_CANDIDATES = "（本轮没有可继续问的候选，面试将自然收束）";

    /** 覆盖摘要最多列出的话题数：逐轮上下文吃 P95 预算，覆盖只是决策参照 */
    static final int MAX_COVERAGE_TOPICS = 6;

    /** 本轮合法候选最多列出条数：再多也不会被选中，只会稀释模型注意力 */
    static final int MAX_LEGAL_CANDIDATES = 6;

    /** 合法候选单题文本上限：模型只需据此建议方向，不需要题干全文 */
    private static final int MAX_LEGAL_CANDIDATE_CHARS = 60;

    /**
     * 跳过指令短路词表（P4Q-5，精确匹配）。
     *
     * <p>精确匹配而非包含匹配：包含匹配会把「不会发生死锁」判成不会作答——这是文档明确禁止的。
     * 更复杂的自然语言（如「这题我会，但打字太麻烦，跳过」）交给模型的语义判断，不在这里猜。
     */
    private static final Set<String> SKIP_PHRASES = Set.of(
        "跳过", "下一个", "下一题", "跳过这题", "pass", "skip", "next", "skip this"
    );

    /** 「明确不会」短路词表（精确匹配）：作为诊断信息保留，不作为技术评分 */
    private static final Set<String> DECLINE_PHRASES = Set.of(
        "不会", "不知道", "不清楚", "没复习", "忘了", "忘记了", "不记得",
        "答不上来", "不会做", "没学过",
        "no answer", "i don't know", "i do not know", "unknown"
    );

    /** 数值难度 → 提示用描述（题目难度参与评分校准） */
    private static final Map<Integer, String> DIFFICULTY_LABELS = Map.of(
        1, "基础(1/5)",
        2, "偏基础(2/5)",
        3, "中级(3/5)",
        4, "进阶(4/5)",
        5, "专家(5/5)"
    );

    private static final String DEFAULT_DIFFICULTY_LABEL = "中级(3/5)";
    private static final int MAX_ANSWER_CHARS = 3000;
    private static final int MAX_FOCUS_CHARS = 30;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<TurnEvalDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    /** 实时档预算配置（ARCH-2b）：逐轮评估在用户等待路径上，必须有明确截止时间 */
    private final StructuredOutputProperties structuredOutputProperties;

    /**
     * LLM 回合评估输出。组件名即 JSON 字段名（camelCase），
     * 包可见仅供同包单测直接构造（非对外 API）。
     */
    record TurnEvalDTO(
        Integer score,
        String answerState,
        List<String> coveredPoints,
        List<String> missingPoints,
        String recommendedFocus,
        /** 用户是否在作答中明确要求跳过本题（P4Q-5）；有实质回答内容时即使夹带指令也应为 false */
        Boolean skipRequested
    ) {}

    public TurnEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            TurnEvaluationProperties properties,
            StructuredOutputProperties structuredOutputProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.structuredOutputProperties = structuredOutputProperties;
        this.systemPromptTemplate = loadTemplate(resourceLoader, properties.getSystemPromptPath());
        this.userPromptTemplate = loadTemplate(resourceLoader, properties.getUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(TurnEvalDTO.class);
    }

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 评估单轮回答。永不抛出：NO_ANSWER 短路 / LLM 失败均回落为确定性 TurnEvaluation。
     *
     * @param chatClient 评估用 LLM 客户端（由调用方按会话 provider 获取）
     * @param request    本轮输入：当前题、回答，以及三类参照物（简历片段 / 最近问答 / 画像基线）
     */
    public TurnEvaluation evaluateTurn(ChatClient chatClient, TurnEvaluationRequest request) {
        InterviewQuestionDTO question = request.question();
        String answer = request.answer() == null ? "" : request.answer().trim();
        if (answer.isEmpty()) {
            return TurnEvaluation.noAnswer();
        }
        // 精确匹配的短回答直接短路，不花模型调用；两类指令语义不同，分别落到跳过与明确不会
        TurnEvaluation shortCircuit = shortCircuit(answer);
        if (shortCircuit != null) {
            return shortCircuit;
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS);
        }

        // 上下文键用 LinkedHashMap：字段已超过 Map.of 的 10 组上限，且这里需要可读的顺序
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("difficultyLabel", difficultyLabel(question));
        variables.put("category", question.category() != null ? question.category() : "");
        variables.put("topicSummary", question.topicSummary() != null ? question.topicSummary() : "");
        variables.put("resumeSnippet", optionalText(request.resumeSnippet(), NO_RESUME_CONTEXT));
        variables.put("recentTurns", recentTurnsText(request.recentTurns()));
        variables.put("profileBaseline", optionalText(request.profileBaseline(), NO_PROFILE_BASELINE));
        variables.put("coverageSummary", optionalText(request.coverageSummary(), NO_COVERAGE_CONTEXT));
        variables.put("budgetSummary", optionalText(request.budgetSummary(), NO_BUDGET_CONTEXT));
        variables.put("legalCandidates", legalCandidatesText(request.legalCandidates()));
        variables.put("question", question.question() == null ? "" : question.question());
        variables.put("expectedPoints", expectedPointsText(question.expectedPoints()));
        variables.put("answer", answer);
        String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
        String userPrompt = userPromptTemplate.render(variables);

        try {
            TurnEvalDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "回合评估失败：", "回合评估", log,
                // 实时档：整个操作（含解析重试）共享一个预算，最坏等待可控（ARCH-2b）
                StructuredCallPolicy.realtime(structuredOutputProperties)
            );
            TurnEvaluation evaluation = normalize(dto);
            log.debug("回合评估完成: question={}, score={}, state={}",
                question.questionIndex(), evaluation.score(), evaluation.answerState());
            return evaluation;
        } catch (Exception e) {
            // 失败只说明本轮没有评估依据，不代表用户答得差或部分正确
            log.warn("回合评估不可用，保守跳过追问: questionIndex={}", question.questionIndex(), e);
            return TurnEvaluation.unknownFallback();
        }
    }

    /**
     * 从本场简历上下文里挑出与当前题最相关的一段。
     *
     * <p>逐轮上下文吃的是 P95 预算，不能把整份简历塞进去。策略：先按小节标题找与当前分类
     * 语义相关的一段（如分类「项目经历」对应简历的「## 项目经历」），找不到就取开头——
     * 开头通常是求职意向与最近的经历，信息密度最高。
     */
    static String resumeSnippetFor(String resumeContextText, String category) {
        if (resumeContextText == null || resumeContextText.isBlank()) {
            return null;
        }
        String matched = sectionMatching(resumeContextText, category);
        String picked = (matched != null ? matched : resumeContextText).strip();
        return picked.length() <= MAX_RESUME_SNIPPET_CHARS
            ? picked
            : picked.substring(0, MAX_RESUME_SNIPPET_CHARS) + "…（已截断）";
    }

    /** 按小节标题与分类名做双向子串匹配（大小写不敏感）；没有匹配返回 null */
    private static String sectionMatching(String text, String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String needle = category.strip().toLowerCase(Locale.ROOT);
        for (String section : text.split("(?=\n## )")) {
            String title = sectionTitle(section);
            if (!title.isEmpty() && (title.contains(needle) || needle.contains(title))) {
                return section;
            }
        }
        return null;
    }

    /** 取小节首行标题（去掉 # 号与空白） */
    private static String sectionTitle(String section) {
        int lineEnd = section.indexOf('\n');
        String firstLine = lineEnd > 0 ? section.substring(0, lineEnd) : section;
        return firstLine.replace("#", "").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 同技能的最近几轮问答（不含当前轮），取自**实际轨迹**（P4-1）。
     *
     * <p>判据是「这一轮确有作答内容」而不是索引位置：候选素材里未问过的选择问没有轨迹，
     * 被策略跳过的候选也从未发生——把它们的空答案当历史，会让模型以为「用户当时什么都没说」。
     *
     * @param turns 实际轨迹（按发生顺序）；当前轮已由调用方从轨迹中排除
     */
    static List<String> recentTurnsFor(List<InterviewTurnDTO> turns, String category) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        for (int index = turns.size() - 1; index >= 0; index--) {
            InterviewTurnDTO turn = turns.get(index);
            if (turn.userAnswer() == null || turn.userAnswer().isBlank()) {
                continue;
            }
            if (category != null && !category.isBlank() && turn.category() != null
                && !turn.category().equalsIgnoreCase(category)) {
                continue;
            }
            rendered.add(0, "- 问：" + shorten(turn.question())
                + "\n  答：" + shorten(turn.userAnswer()));
            if (rendered.size() >= MAX_RECENT_TURNS) {
                break;
            }
        }
        return rendered;
    }

    /** 参照物为空时给出可读说明：留白会让模型以为「这段本来就没有」而不是「没有内容」 */
    private static String optionalText(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
    }

    // ===== P4Q-2 批 2b：覆盖 / 预算 / 合法候选 =====

    /**
     * 覆盖摘要（P4Q-2 批 2b）：必要覆盖状态 + 已问话题 + 尚未问的话题。
     *
     * <p>判据全部来自**实际轨迹 × 候选池 × 计划**，与 Java 收束硬边界同源
     * （{@code requiredCoverageSatisfied} 用同一个 {@link InterviewQuestionDTO#matchesTopic}），
     * 因此模型看到的「必要覆盖还差什么」与 Java 是否按覆盖收束不会分叉。
     * 覆盖只表示「已有考察材料」，不表示答对。
     *
     * @return 多行摘要；候选池为空（历史修复等无会话上下文场景）返回 null
     */
    static String coverageSummaryFor(List<InterviewQuestionDTO> candidates,
                                     List<InterviewTurnDTO> turns,
                                     InterviewQuestionDTO current,
                                     InterviewPlan plan) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<InterviewQuestionDTO> pool = InterviewQuestionIdentity.withDerivedIds(candidates);
        List<InterviewTurnDTO> trajectory = turns == null ? List.of() : turns;
        Set<String> askedIds = askedIdsOf(trajectory);
        String currentId = current != null ? current.questionId() : null;

        List<String> lines = new ArrayList<>();
        lines.add(requiredCoverageLine(pool, askedIds, currentId, plan));
        lines.add(askedTopicsLine(trajectory));
        lines.add(pendingTopicsLine(pool, askedIds, currentId));
        return String.join("\n", lines);
    }

    /** 必要覆盖逐项状态：已覆盖 / 本轮正在考察 / 未覆盖（按实际轨迹，不靠模型自述） */
    private static String requiredCoverageLine(List<InterviewQuestionDTO> pool, Set<String> askedIds,
                                               String currentId, InterviewPlan plan) {
        if (plan == null) {
            return "- 必要覆盖：（旧会话未记录计划，不按覆盖完成收束）";
        }
        if (plan.requiredTopics().isEmpty()) {
            return "- 必要覆盖：（本场未声明必要覆盖，不按覆盖完成收束）";
        }
        List<String> states = new ArrayList<>();
        for (String topic : plan.requiredTopics()) {
            states.add(topic + "=" + requiredTopicState(pool, askedIds, currentId, topic));
        }
        return "- 必要覆盖：" + String.join("；", states);
    }

    private static String requiredTopicState(List<InterviewQuestionDTO> pool, Set<String> askedIds,
                                             String currentId, String topic) {
        boolean inProgress = false;
        for (InterviewQuestionDTO question : pool) {
            if (!question.isMain() || !question.matchesTopic(topic)) {
                continue;
            }
            if (question.questionId() != null && question.questionId().equals(currentId)) {
                // 当前题还没提交：它正在被考察，但此刻不算已覆盖
                inProgress = true;
                continue;
            }
            if (askedIds.contains(question.questionId())) {
                return "已覆盖";
            }
        }
        return inProgress ? "本轮正在考察" : "未覆盖";
    }

    /** 已问话题（实际轨迹）：轮次数与其中未作答数——「问过」与「答了」是两件事 */
    private static String askedTopicsLine(List<InterviewTurnDTO> trajectory) {
        Map<String, TopicTally> byTopic = new LinkedHashMap<>();
        for (InterviewTurnDTO turn : trajectory) {
            String topic = topicKeyOf(turn.topic(), turn.category());
            if (topic != null) {
                byTopic.computeIfAbsent(topic, key -> new TopicTally()).add(turn);
            }
        }
        if (byTopic.isEmpty()) {
            return "- 已问话题：（本场尚未问过任何题）";
        }
        List<String> rendered = new ArrayList<>();
        for (Map.Entry<String, TopicTally> entry : byTopic.entrySet()) {
            if (rendered.size() >= MAX_COVERAGE_TOPICS) {
                rendered.add("…还有 " + (byTopic.size() - MAX_COVERAGE_TOPICS) + " 个话题未列出");
                break;
            }
            TopicTally tally = entry.getValue();
            rendered.add(tally.unanswered == 0
                ? entry.getKey() + " " + tally.turns + " 轮"
                : entry.getKey() + " " + tally.turns + " 轮（" + tally.unanswered + " 轮未作答）");
        }
        return "- 已问话题：" + String.join("；", rendered);
    }

    /** 尚未问过的主问题话题：模型转场建议的去处，与实际候选直接对应 */
    private static String pendingTopicsLine(List<InterviewQuestionDTO> pool, Set<String> askedIds,
                                            String currentId) {
        List<String> pending = new ArrayList<>();
        for (InterviewQuestionDTO question : pool) {
            if (!question.isMain() || askedIds.contains(question.questionId())
                || question.questionId().equals(currentId)) {
                continue;
            }
            String topic = topicKeyOf(question.topic(), question.category());
            if (topic != null && !pending.contains(topic)) {
                pending.add(topic);
            }
        }
        if (pending.isEmpty()) {
            return "- 尚未问的话题：（没有未问过的主问题话题）";
        }
        if (pending.size() > MAX_COVERAGE_TOPICS) {
            return "- 尚未问的话题：" + String.join("；", pending.subList(0, MAX_COVERAGE_TOPICS))
                + "；…还有 " + (pending.size() - MAX_COVERAGE_TOPICS) + " 个";
        }
        return "- 尚未问的话题：" + String.join("；", pending);
    }

    /**
     * 时间与追问预算摘要（P4Q-2 批 2b）。
     *
     * @param plan            会话计划；null = 旧会话（明确标注不按时间收束，不编造默认值）
     * @param remainingSeconds 评估时刻的剩余秒数；null = 无计划
     * @param followUpBudget  当前追问组剩余可问条数（结构上限）；null = 无候选上下文
     * @return 多行摘要；三类数据都没有（修复等场景）返回 null，由提示词给可读占位
     */
    static String budgetSummaryFor(InterviewPlan plan, Integer remainingSeconds, Integer followUpBudget) {
        if (plan == null && remainingSeconds == null && followUpBudget == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (plan == null) {
            lines.add("- 时间预算：（旧会话未记录预计时长，不按时间收束）");
        } else if (remainingSeconds == null) {
            lines.add("- 时间预算：预计 " + plan.plannedDurationMinutes()
                + " 分钟（没有已用时间记录，剩余按充足处理）");
        } else {
            int usedSeconds = Math.max(0, plan.budgetSeconds() - remainingSeconds);
            lines.add("- 时间预算：剩余约 " + ceilMinutes(remainingSeconds)
                + " 分钟（预计 " + plan.plannedDurationMinutes() + " 分钟，已用约 "
                + floorMinutes(usedSeconds) + " 分钟；只计用户答题时间，模型等待不计）");
        }
        if (followUpBudget == null) {
            lines.add("- 追问预算：（没有候选上下文，无法给出追问额度）");
        } else if (followUpBudget <= 0) {
            lines.add("- 追问预算：当前话题组没有剩余追问，不要建议继续深挖同一话题");
        } else {
            lines.add("- 追问预算：当前话题组最多还可追问 " + followUpBudget + " 条");
        }
        return String.join("\n", lines);
    }

    /**
     * 当前追问组剩余可问条数（结构上限，P4Q-2）。
     *
     * <p>与 {@code AdaptiveInterviewPolicy} 的口径一致：追问只在本组内消费，
     * 其他组的预置追问策略到不了，也就不算入预算。
     */
    static int remainingFollowUpsFor(List<InterviewQuestionDTO> candidates,
                                     List<InterviewTurnDTO> turns,
                                     InterviewQuestionDTO current) {
        if (candidates == null || candidates.isEmpty() || current == null) {
            return 0;
        }
        String groupId = current.isFollowUp() ? current.parentQuestionId() : current.questionId();
        if (groupId == null || groupId.isBlank()) {
            return 0;
        }
        Set<String> askedIds = askedIdsOf(turns);
        if (current.questionId() != null) {
            askedIds.add(current.questionId());
        }
        return (int) candidates.stream()
            .filter(InterviewQuestionDTO::isFollowUp)
            .filter(question -> groupId.equals(question.parentQuestionId()))
            .filter(question -> !askedIds.contains(question.questionId()))
            .count();
    }

    /**
     * 本轮合法候选（P4Q-2 批 2b）：Java 的策略**实际可能选中**的题，带稳定标识。
     *
     * <p>口径与 {@code AdaptiveInterviewPolicy} 对齐：本组剩余追问优先，其次是尚未问过的主问题；
     * 其他追问组的预置追问不会被策略选中，因此不出现在列表里。列表只列前
     * {@link #MAX_LEGAL_CANDIDATES} 条，截断时明确说明还剩多少。
     */
    static List<String> legalCandidatesFor(List<InterviewQuestionDTO> candidates,
                                           List<InterviewTurnDTO> turns,
                                           InterviewQuestionDTO current) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> askedIds = askedIdsOf(turns);
        if (current != null && current.questionId() != null) {
            askedIds.add(current.questionId());
        }
        List<String> rendered = new ArrayList<>();
        String groupId = current == null ? null
            : (current.isFollowUp() ? current.parentQuestionId() : current.questionId());
        if (groupId != null && !groupId.isBlank()) {
            for (InterviewQuestionDTO question : candidates) {
                if (question.isFollowUp() && groupId.equals(question.parentQuestionId())
                    && !askedIds.contains(question.questionId())) {
                    rendered.add(renderCandidate(question, "追问"));
                }
            }
        }
        for (InterviewQuestionDTO question : candidates) {
            if (question.isMain() && !askedIds.contains(question.questionId())) {
                rendered.add(renderCandidate(question, "主问题"));
            }
        }
        if (rendered.size() <= MAX_LEGAL_CANDIDATES) {
            return rendered;
        }
        List<String> capped = new ArrayList<>(rendered.subList(0, MAX_LEGAL_CANDIDATES));
        capped.add("…还有 " + (rendered.size() - MAX_LEGAL_CANDIDATES) + " 条候选未列出");
        return capped;
    }

    private static String renderCandidate(InterviewQuestionDTO question, String kind) {
        StringBuilder line = new StringBuilder("[").append(question.questionId()).append("] ")
            .append(kind);
        String topic = topicKeyOf(question.topic(), question.category());
        if (topic != null) {
            line.append("｜").append(topic);
        }
        if (question.difficulty() != null) {
            line.append("｜难度").append(question.difficulty());
        }
        String text = question.question() == null ? "" : question.question().strip();
        if (text.length() > MAX_LEGAL_CANDIDATE_CHARS) {
            text = text.substring(0, MAX_LEGAL_CANDIDATE_CHARS) + "…";
        }
        return line.append("：").append(text).toString();
    }

    /** 轨迹与候选里的题目标识集合 */
    private static Set<String> askedIdsOf(List<InterviewTurnDTO> turns) {
        Set<String> ids = new LinkedHashSet<>();
        if (turns != null) {
            for (InterviewTurnDTO turn : turns) {
                if (turn.questionId() != null && !turn.questionId().isBlank()) {
                    ids.add(turn.questionId());
                }
            }
        }
        return ids;
    }

    /** 话题键：topic 优先（P4-1），旧数据退回 category */
    private static String topicKeyOf(String topic, String category) {
        if (topic != null && !topic.isBlank()) {
            return topic.strip();
        }
        return category != null && !category.isBlank() ? category.strip() : null;
    }

    private static int ceilMinutes(int seconds) {
        return Math.max(0, (seconds + 59) / 60);
    }

    private static int floorMinutes(int seconds) {
        return Math.max(0, seconds / 60);
    }

    private static String legalCandidatesText(List<String> legalCandidates) {
        return legalCandidates == null || legalCandidates.isEmpty()
            ? NO_LEGAL_CANDIDATES
            : String.join("\n", legalCandidates);
    }

    /** 话题累计（覆盖摘要用）：轮次数与其中未作答数 */
    private static final class TopicTally {
        private int turns;
        private int unanswered;

        private void add(InterviewTurnDTO turn) {
            turns++;
            if (turn.answerState() != InterviewAnswerEntity.AnswerState.ANSWERED) {
                unanswered++;
            }
        }
    }

    private static String recentTurnsText(List<String> recentTurns) {
        return recentTurns == null || recentTurns.isEmpty()
            ? NO_RECENT_TURNS
            : String.join("\n", recentTurns);
    }

    /** 单条历史问答的截断（只作参照，不需要全文） */
    private static String shorten(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= MAX_HISTORY_ANSWER_CHARS
            ? value
            : value.substring(0, MAX_HISTORY_ANSWER_CHARS) + "…";
    }

    /**
     * 精确匹配的短路结果：跳过指令 / 明确不会；非指令返回 null（交由模型判定）。
     *
     * <p>public 是刻意的：运行期的答案状态归类与历史数据修复必须用**同一判据**，
     * 否则「修复口径」和「今后行为口径」会分叉。
     */
    public static TurnEvaluation shortCircuit(String answer) {
        if (answer == null) {
            return null;
        }
        String normalized = answer.trim().toLowerCase();
        if (SKIP_PHRASES.contains(normalized)) {
            return TurnEvaluation.skipped();
        }
        if (DECLINE_PHRASES.contains(normalized)) {
            return TurnEvaluation.noAnswer();
        }
        return null;
    }

    static boolean isNoAnswerPhrase(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase();
        return DECLINE_PHRASES.contains(normalized) || SKIP_PHRASES.contains(normalized);
    }

    /**
     * 归一化模型输出：
     * - score 夹取 0-100；缺失时按 answerState 默认分补齐；
     * - answerState 非法/缺失时按 score 分段推导；
     * - 两者都不可用时保留 UNKNOWN，不补造默认分；
     * - coverage 由 covered/missing 代码计算。
     */
    static TurnEvaluation normalize(TurnEvalDTO dto) {
        if (dto == null) {
            return TurnEvaluation.unknownFallback();
        }
        // 跳过是独立指令，不依赖模型是否同时提供了质量字段
        if (Boolean.TRUE.equals(dto.skipRequested())) {
            return TurnEvaluation.skipped();
        }
        AnswerState state = parseState(dto.answerState());
        Integer rawScore = dto.score();
        if (state == AnswerState.UNKNOWN || (state == null && rawScore == null)) {
            return TurnEvaluation.unknownFallback();
        }
        int score;
        if (state == null) {
            // 状态缺失但分数可用：沿用分段推导，避免丢弃已有评估依据
            score = clampScore(rawScore);
            state = TurnEvaluation.stateForScore(score);
        } else {
            // 状态合法：分数缺失则按状态默认分补齐；分数与状态同时存在时以分数为准（仅夹取）
            score = clampScore(rawScore == null ? TurnEvaluation.defaultScoreFor(state) : rawScore);
        }

        List<String> covered = cleanList(dto.coveredPoints());
        List<String> missing = cleanList(dto.missingPoints());
        double coverage = coverageOf(covered, missing);
        String focus = dto.recommendedFocus() == null ? "" : dto.recommendedFocus().trim();
        if (focus.length() > MAX_FOCUS_CHARS) {
            focus = focus.substring(0, MAX_FOCUS_CHARS);
        }
        return new TurnEvaluation(score, coverage, covered, missing, state, focus, true);
    }

    private static AnswerState parseState(String answerState) {
        if (answerState == null || answerState.isBlank()) {
            return null;
        }
        try {
            return AnswerState.valueOf(answerState.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static double coverageOf(List<String> covered, List<String> missing) {
        if (covered.isEmpty() && missing.isEmpty()) {
            // 模型未给出任何要点判定：无法判定覆盖，按中性处理
            return 0.5;
        }
        return (double) covered.size() / (covered.size() + missing.size());
    }

    private static List<String> cleanList(List<String> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String point : points) {
            if (point != null && !point.isBlank()) {
                cleaned.add(point.trim());
            }
        }
        return cleaned;
    }

    private static String difficultyLabel(InterviewQuestionDTO question) {
        if (question.difficulty() == null) {
            return DEFAULT_DIFFICULTY_LABEL;
        }
        return DIFFICULTY_LABELS.getOrDefault(question.difficulty(), DEFAULT_DIFFICULTY_LABEL);
    }

    private static String expectedPointsText(List<String> expectedPoints) {
        if (expectedPoints == null || expectedPoints.isEmpty()) {
            return "（题目未提供预设要点，请依据问题常识判定应覆盖的要点）";
        }
        return String.join("；", expectedPoints);
    }
}
