package interview.guide.modules.interview.model;

/**
 * 一轮推进落库后的权威结果（P4-9a）。
 *
 * @param turnVersion   推进后的会话版本（提交方下次提交要带回来的预期版本）
 * @param evaluateEpoch 当前评估代次；本轮结束时用于投递评估消息
 */
public record InterviewTurnResult(int turnVersion, long evaluateEpoch) {}
