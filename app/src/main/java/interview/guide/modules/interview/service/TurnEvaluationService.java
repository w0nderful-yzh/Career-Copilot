package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.TurnEvaluation;
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
 * - 模型失败经 StructuredOutputInvoker 重试后仍失败 → 中性回落，不阻塞答题；
 * - prompt 只含 当前题 + 期望要点 + 回答，token 保持最小。
 */
@Service
public class TurnEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TurnEvaluationService.class);

    /** NO_ANSWER 短路词表（精确匹配，避免误伤；其余由 LLM 自行判 NO_ANSWER） */
    private static final Set<String> NO_ANSWER_PHRASES = Set.of(
        "不会", "不知道", "不清楚", "没复习", "忘了", "忘记了", "不记得",
        "跳过", "答不上来", "下一个", "不会做", "没学过",
        "pass", "skip", "next", "no answer", "i don't know", "i do not know", "unknown"
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
        String recommendedFocus
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
     * @param question   当前被回答的题（使用 difficulty/expectedPoints/category）
     * @param userAnswer 用户回答原文
     */
    public TurnEvaluation evaluateTurn(ChatClient chatClient, InterviewQuestionDTO question, String userAnswer) {
        String answer = userAnswer == null ? "" : userAnswer.trim();
        if (answer.isEmpty() || isNoAnswerPhrase(answer)) {
            return TurnEvaluation.noAnswer();
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS);
        }

        Map<String, Object> variables = Map.of(
            "difficultyLabel", difficultyLabel(question),
            "category", question.category() != null ? question.category() : "",
            "topicSummary", question.topicSummary() != null ? question.topicSummary() : "",
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
            // 评估失败不应阻塞答题：中性回落，决策引擎按未知质量保守推进
            log.warn("回合评估失败，回落中性结果: questionIndex={}, error={}",
                question.questionIndex(), e.getMessage());
            return TurnEvaluation.unknownFallback();
        }
    }

    static boolean isNoAnswerPhrase(String answer) {
        String normalized = answer.trim().toLowerCase();
        return NO_ANSWER_PHRASES.contains(normalized);
    }

    /**
     * 归一化模型输出：
     * - score 夹取 0-100；缺失时按 answerState 默认分补齐；
     * - answerState 非法/缺失时按 score 分段推导；
     * - coverage 由 covered/missing 代码计算。
     */
    static TurnEvaluation normalize(TurnEvalDTO dto) {
        AnswerState state = parseState(dto.answerState());
        Integer rawScore = dto.score();
        int score;
        if (state == null) {
            // 状态缺失：由分数推导状态（分数也缺失则取中性 PARTIAL）
            score = clampScore(rawScore == null ? 55 : rawScore);
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
