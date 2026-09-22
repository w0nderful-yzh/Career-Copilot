package interview.guide.modules.interview.model;

/**
 * 提交答案响应
 *
 * @param turnVersion      推进后的会话版本（P4-9a）：下一次提交要把它作为 expectedVersion 回传，
 *                         服务端据此拒绝过期请求，并保证重复提交只推进一次
 * @param consumedSeconds  用户答题累计耗时（秒，P4Q-2）：随载荷带回，前端不再为刷新顶栏多发一次请求
 * @param remainingSeconds 剩余预算（秒，P4Q-2）；无计划时为 null
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions,
    int turnVersion,
    int consumedSeconds,
    Integer remainingSeconds,
    /** 本轮实际展示的承接语；模型建议被 Java 否决或无必要时为空 */
    String transitionMessage
) {

    /** 兼容 P4Q-3b 之前不带承接语的构造点 */
    public SubmitAnswerResponse(boolean hasNextQuestion, InterviewQuestionDTO nextQuestion,
                                int currentIndex, int totalQuestions, int turnVersion,
                                int consumedSeconds, Integer remainingSeconds) {
        this(hasNextQuestion, nextQuestion, currentIndex, totalQuestions, turnVersion,
            consumedSeconds, remainingSeconds, null);
    }

    /** 兼容不带版本的构造点（知识库面试等不参与逐轮一致性控制的链路） */
    public SubmitAnswerResponse(boolean hasNextQuestion, InterviewQuestionDTO nextQuestion,
                                int currentIndex, int totalQuestions) {
        this(hasNextQuestion, nextQuestion, currentIndex, totalQuestions, 0, 0, null, null);
    }
}
