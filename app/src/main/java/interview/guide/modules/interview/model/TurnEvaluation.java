package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 逐题轻量评估结果（P4-2）。
 *
 * 只服务「下一题怎么问」的决策（FOLLOW_UP / NEXT_QUESTION / UPGRADE ...），
 * 不是整场报告，不落库。score / answerState 语义一致（由服务归一保证）；
 * coverage 由 coveredPoints / missingPoints 按代码计算，避免模型直接输出浮点。
 */
public record TurnEvaluation(
    int score,                     // 0-100 该题回答质量分
    double coverage,               // 期望要点覆盖比例 0.0-1.0
    List<String> coveredPoints,    // 已答出的期望要点
    List<String> missingPoints,    // 遗漏/答错的期望要点
    AnswerState answerState,       // 语义状态（决策主输入）
    String recommendedFocus,       // 建议继续追问的方向（30 字内，可为空）
    boolean evaluatedByLlm         // 是否为 LLM 评估结果；false = NO_ANSWER 短路或 LLM 失败回落
) {
    public enum AnswerState {
        EXCELLENT, GOOD, PARTIAL, WEAK, WRONG, NO_ANSWER
    }

    /** 回答状态 → 默认分数（模型未给分时按状态映射，保证状态与分数自洽） */
    public static int defaultScoreFor(AnswerState state) {
        return switch (state) {
            case EXCELLENT -> 90;
            case GOOD -> 75;
            case PARTIAL -> 55;
            case WEAK -> 35;
            case WRONG -> 15;
            case NO_ANSWER -> 0;
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

    /** NO_ANSWER 短路结果（不调 LLM） */
    public static TurnEvaluation noAnswer() {
        return new TurnEvaluation(0, 0.0, List.of(), List.of(), AnswerState.NO_ANSWER, "", false);
    }

    /** LLM 评估失败时的中性回落：未知质量按 PARTIAL 处理，由决策引擎保守推进 */
    public static TurnEvaluation unknownFallback() {
        return new TurnEvaluation(50, 0.5, List.of(), List.of(), AnswerState.PARTIAL, "", false);
    }
}
