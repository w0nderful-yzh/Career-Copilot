package interview.guide.modules.interview.model;

/**
 * 提交答案响应
 *
 * @param turnVersion 推进后的会话版本（P4-9a）：下一次提交要把它作为 expectedVersion 回传，
 *                    服务端据此拒绝过期请求，并保证重复提交只推进一次
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions,
    int turnVersion
) {

    /** 兼容不带版本的构造点（知识库面试等不参与逐轮一致性控制的链路） */
    public SubmitAnswerResponse(boolean hasNextQuestion, InterviewQuestionDTO nextQuestion,
                                int currentIndex, int totalQuestions) {
        this(hasNextQuestion, nextQuestion, currentIndex, totalQuestions, 0);
    }
}
