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
    boolean skipRequested
) {

    /** 兼容构造：未识别到跳过指令 */
    public TurnEvaluation(Integer score, Double coverage, List<String> coveredPoints,
                          List<String> missingPoints, AnswerState answerState,
                          String recommendedFocus, boolean evaluatedByLlm) {
        this(score, coverage, coveredPoints, missingPoints, answerState, recommendedFocus,
            evaluatedByLlm, false);
    }

    public enum AnswerState {
        EXCELLENT, GOOD, PARTIAL, WEAK, WRONG, NO_ANSWER, UNKNOWN
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
        return new TurnEvaluation(0, 0.0, List.of(), List.of(), AnswerState.NO_ANSWER, "", false);
    }

    /** 用户明确要求跳过本题（P4Q-5）：不调模型、不追问、不计分 */
    public static TurnEvaluation skipped() {
        return new TurnEvaluation(0, 0.0, List.of(), List.of(), AnswerState.NO_ANSWER, "", false, true);
    }

    /** 无可用评估时保留未知质量，不用虚构的分数或覆盖率驱动追问与画像 */
    public static TurnEvaluation unknownFallback() {
        return new TurnEvaluation(null, null, List.of(), List.of(), AnswerState.UNKNOWN, "", false);
    }
}
