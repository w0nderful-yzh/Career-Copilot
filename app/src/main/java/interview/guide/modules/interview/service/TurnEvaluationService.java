package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
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
 * - prompt 只含当前题、回答与三类参照物（简历片段 / 最近相关问答 / 画像基线），均已按预算裁剪。
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
            TurnEvaluationProperties properties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
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

        Map<String, Object> variables = Map.of(
            "difficultyLabel", difficultyLabel(question),
            "category", question.category() != null ? question.category() : "",
            "topicSummary", question.topicSummary() != null ? question.topicSummary() : "",
            "resumeSnippet", optionalText(request.resumeSnippet(), NO_RESUME_CONTEXT),
            "recentTurns", recentTurnsText(request.recentTurns()),
            "profileBaseline", optionalText(request.profileBaseline(), NO_PROFILE_BASELINE),
            "question", question.question() == null ? "" : question.question(),
            "expectedPoints", expectedPointsText(question.expectedPoints()),
            "answer", answer
        );
        String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
        String userPrompt = userPromptTemplate.render(variables);

        try {
            TurnEvalDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "回合评估失败：", "回合评估", log
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
     * 同技能的最近几轮问答（不含当前轮）。
     *
     * <p>判据是「该题确有作答记录」而不是索引位置：题单里含候选择问，被策略跳过的候选
     * 从未发生过——把它们的空答案当历史，会让模型以为「用户当时什么都没说」。
     */
    static List<String> recentTurnsFor(List<InterviewQuestionDTO> questions, int currentIndex,
                                       String category) {
        if (questions == null || currentIndex <= 0) {
            return List.of();
        }
        List<String> turns = new ArrayList<>();
        for (int index = Math.min(currentIndex, questions.size()) - 1; index >= 0; index--) {
            InterviewQuestionDTO candidate = questions.get(index);
            if (!candidate.wasAsked() || candidate.userAnswer() == null
                || candidate.userAnswer().isBlank()) {
                continue;
            }
            if (category != null && !category.isBlank() && candidate.category() != null
                && !candidate.category().equalsIgnoreCase(category)) {
                continue;
            }
            turns.add(0, "- 问：" + shorten(candidate.question())
                + "\n  答：" + shorten(candidate.userAnswer()));
            if (turns.size() >= MAX_RECENT_TURNS) {
                break;
            }
        }
        return turns;
    }

    /** 参照物为空时给出可读说明：留白会让模型以为「这段本来就没有」而不是「没有内容」 */
    private static String optionalText(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
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
