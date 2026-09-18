package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 逐题轻量评估结果（P4-2）。
 *
 * 只服务「下一题怎么问」的决策（FOLLOW_UP / NEXT_QUESTION / UPGRADE ...），
 * 不是整场报告，不落库。score / answerState 语义一致（由服务归一保证）；
 * coverage 由 coveredPoints / missingPoints 按代码计算，避免模型直接输出浮点。
 * UNKNOWN 表示没有可用评估，分数与覆盖率均为空，不得作为评分证据。
 */
public record TurnEvaluation(
    Integer score,                 // 0-100 该题回答质量分；评估不可用时为 null
    Double coverage,               // 期望要点覆盖比例 0.0-1.0；评估不可用时为 null
    List<String> coveredPoints,    // 已答出的期望要点
    List<String> missingPoints,    // 遗漏/答错的期望要点
    AnswerState answerState,       // 语义状态（决策主输入）
    String recommendedFocus,       // 建议继续追问的方向（30 字内，可为空）
    boolean evaluatedByLlm,        // 是否为 LLM 评估结果；false = NO_ANSWER 短路或 LLM 失败回落
    /**
     * 用户是否明确要求跳过本题（P4Q-5）。
     *
     * <p>与 answerState=NO_ANSWER 的区别：NO_ANSWER 是「答不上来 / 明确不会」（诊断信息），
     * skipRequested 是「我不想答这题」（一等动作）。判定必须走语义，不能做关键词包含匹配——
     * 否则「不会发生死锁」这类技术回答会被误判成跳过。
     */
    boolean skipRequested,
    /** 模型建议的下一步动作；Java 仍会校验覆盖、预算、题目归属与去重边界 */
    RecommendedAction recommendedAction,
    /** 模型建议的下一题稳定标识；FOLLOW_UP / NEXT_MAIN 时必须来自本轮合法候选 */
    String recommendedQuestionId,
    /** 模型给出的简短依据；落库的是 Java 接纳后的最终依据 */
    String decisionReason,
    /** 可选承接语；只有建议被 Java 原样接纳时才展示和持久化 */
    String transitionMessage,
    /**
     * 受限生成的短追问（P4-4b）：仅当 FOLLOW_UP、合法候选中没有针对该缺口的追问、且追问预算 > 0 时非空。
     * Java 校验（预算 / 去重 / 长度 / 类型）并持久化后才成为正式下一题。
     */
    String generatedFollowUp,
    /** 生成追问的考察点（P4-4b）：为什么问这个 */
    String generatedExpectedPoint,
    /** 生成追问引用的候选人原话 / 待验证点（P4-4b）：保证追问可追溯到回答 */
    String generatedAnswerBasis,
    /** 难度调整建议（P4Q-3c）：与答案质量分开表达，不影响 score / answerState */
    DifficultyAdjust difficultyAdjust,
    /** 是否明确要求停止当前话题深挖（P4Q-3c）：保留已给技术证据，仅软转场不计 NO_ANSWER */
    boolean stopDeepDive
) {

    /** 兼容构造：未识别到跳过指令 */
    public TurnEvaluation(Integer score, Double coverage, List<String> coveredPoints,
                          List<String> missingPoints, AnswerState answerState,
                          String recommendedFocus, boolean evaluatedByLlm) {
        this(score, coverage, coveredPoints, missingPoints, answerState, recommendedFocus,
            evaluatedByLlm, false, null, null, "", "", "", "", "", DifficultyAdjust.NONE, false);
    }

    /** 兼容既有构造点：未识别到节奏建议 */
    public TurnEvaluation(Integer score, Double coverage, List<String> coveredPoints,
                          List<String> missingPoints, AnswerState answerState,
                          String recommendedFocus, boolean evaluatedByLlm,
                          boolean skipRequested) {
        this(score, coverage, coveredPoints, missingPoints, answerState, recommendedFocus,
            evaluatedByLlm, skipRequested, null, null, "", "", "", "", "", DifficultyAdjust.NONE, false);
    }

    /** P4Q-3b 的 12 参构造：未含受限生成与难度信号 */
    public TurnEvaluation(Integer score, Double coverage, List<String> coveredPoints,
                          List<String> missingPoints, AnswerState answerState,
                          String recommendedFocus, boolean evaluatedByLlm, boolean skipRequested,
                          RecommendedAction recommendedAction, String recommendedQuestionId,
                          String decisionReason, String transitionMessage) {
        this(score, coverage, coveredPoints, missingPoints, answerState, recommendedFocus,
            evaluatedByLlm, skipRequested, recommendedAction, recommendedQuestionId, decisionReason,
            transitionMessage, "", "", "", DifficultyAdjust.NONE, false);
    }

    public enum AnswerState {
        EXCELLENT, GOOD, PARTIAL, WEAK, WRONG, NO_ANSWER, UNKNOWN
    }

    /** 一次逐轮语义调用对下一步的建议；是否执行由 Java 硬边界决定 */
    public enum RecommendedAction {
        FOLLOW_UP,
        /** 无合适候选时基于回答受限生成一条短追问（P4-4b） */
        FOLLOW_UP_GENERATED,
        NEXT_MAIN,
        FINISH
    }

    /** 难度调整信号（P4Q-3c）：显式指令才生效，不凭回答长短推测 */
    public enum DifficultyAdjust {
        EASIER, HARDER, NONE
    }

    /** 回答状态 → 默认分数（模型未给分时按状态映射，保证状态与分数自洽） */
    public static Integer defaultScoreFor(AnswerState state) {
        return switch (state) {
            case EXCELLENT -> 90;
            case GOOD -> 75;
            case PARTIAL -> 55;
            case WEAK -> 35;
            case WRONG -> 15;
            case NO_ANSWER -> 0;
            case UNKNOWN -> null;
        };
    }

    /** 分数 → 兜底状态（模型给了分但状态缺失/非法时使用） */
    public static AnswerState stateForScore(int score) {
        if (score >= 85) {
            return AnswerState.EXCELLENT;
        }
        if (score >= 70) {
            return AnswerState.GOOD;
        }
        if (score >= 50) {
            return AnswerState.PARTIAL;
        }
        if (score >= 30) {
            return AnswerState.WEAK;
        }
        return AnswerState.WRONG;
    }

    /** NO_ANSWER 短路结果（不调 LLM）：用户答不上来 / 明确不会 */
    public static TurnEvaluation noAnswer() {
        return new TurnEvaluation(0, 0.0, List.of(), List.of(), AnswerState.NO_ANSWER, "", false,
            false, null, null, "", "");
    }

    /** 用户明确要求跳过本题（P4Q-5）：不调模型、不追问、不计分 */
    public static TurnEvaluation skipped() {
        return new TurnEvaluation(0, 0.0, List.of(), List.of(), AnswerState.NO_ANSWER, "", false,
            true, null, null, "", "");
    }

    /** 无可用评估时保留未知质量，不用虚构的分数或覆盖率驱动追问与画像 */
    public static TurnEvaluation unknownFallback() {
        return new TurnEvaluation(null, null, List.of(), List.of(), AnswerState.UNKNOWN, "", false,
            false, null, null, "", "");
    }
}
