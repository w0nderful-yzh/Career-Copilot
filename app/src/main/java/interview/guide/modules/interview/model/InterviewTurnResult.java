package interview.guide.modules.interview.model;

/**
 * 一轮推进落库后的权威结果（P4-9a / P4-1）。
 *
 * @param turnVersion   推进后的会话版本（提交方下次提交要带回来的预期版本）
 * @param evaluateEpoch 当前评估代次；本轮结束时用于投递评估消息
 * @param turnOrdinal   本轮真实发生顺序（落库时分配）；本轮没有写答案事实时为 0
 */
public record InterviewTurnResult(int turnVersion, long evaluateEpoch, int turnOrdinal) {

    /** 兼容构造点：不关心发生顺序的调用方 */
    public InterviewTurnResult(int turnVersion, long evaluateEpoch) {
        this(turnVersion, evaluateEpoch, 0);
    }
}
