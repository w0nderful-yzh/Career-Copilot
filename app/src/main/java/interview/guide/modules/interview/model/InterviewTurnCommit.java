package interview.guide.modules.interview.model;

/**
 * 一轮推进的提交命令（P4-1 / P4-9a）。
 *
 * <p>它是「已经算好的决定」——下一题、答案状态、结束与否都在模型调用与选题策略完成后确定，
 * 本对象只负责把这次推进**一次性、原子地**写进数据库：
 * 会话（版本 / 当前题标识 / 状态 / 结束原因 / 评估请求）+ 答案事实 + 幂等记录同属一个短事务。
 * 因此模型调用必然在事务之外，而部分写入不可能留下半状态。
 *
 * <p>P4-1 起，闸门与定位都用**题目标识**（{@link #expectedQuestionId} / {@link #newQuestionId}），
 * {@link #newIndex} 只是候选池内的展示顺序——顺序变化不再影响推进与恢复。
 *
 * @param requestId          请求标识；null 表示调用方未提供（兼容旧的逐轮调用，退化为「仅并发闸门」）
 * @param payloadHash        载荷指纹；配合 requestId 判「同一标识不同载荷」
 * @param expectedQuestionId 提交方看到的当前待答题标识；与版本一起构成并发闸门
 * @param newQuestionId      推进后的当前题标识；候选耗尽或交卷时为 null
 * @param questionId         本轮答案行对应的题目标识（身份，不是下标）
 * @param turnOrdinal        真实发生顺序；null 表示由落库方在事务内按 max+1 分配
 * @param decidedAction      本轮最终决定（见 {@link InterviewTurnDTO} 的 ACTION_* 常量）
 * @param completing         是否收束到评估（最后一轮作答与提前交卷都属于此）
 * @param questionIndex      答案行保留的展示顺序（历史兼容用）
 */
public record InterviewTurnCommit(
    String sessionId,
    String requestId,
    String action,
    String payloadHash,
    int expectedVersion,
    String expectedQuestionId,
    int newIndex,
    String newQuestionId,
    String questionId,
    Integer turnOrdinal,
    String decidedAction,
    boolean completing,
    Integer questionIndex,
    String question,
    String category,
    String answer,
    InterviewAnswerEntity.AnswerState answerState,
    String responseJson
) {

    /** 提前交卷的动作名（与会话推进动作区分：它不改当前题，只把会话收束到评估） */
    public static final String ACTION_COMPLETE = "COMPLETE";

    /** 作答 / 跳过：推进到下一题（或收束） */
    public static InterviewTurnCommit ofTurn(String sessionId, String requestId, String action,
                                             String payloadHash, int expectedVersion,
                                             String expectedQuestionId, int newIndex,
                                             String newQuestionId, String questionId,
                                             String decidedAction, boolean completing,
                                             int questionIndex, String question, String category,
                                             String answer,
                                             InterviewAnswerEntity.AnswerState answerState,
                                             String responseJson) {
        return new InterviewTurnCommit(sessionId, requestId, action, payloadHash, expectedVersion,
            expectedQuestionId, newIndex, newQuestionId, questionId, null, decidedAction, completing,
            questionIndex, question, category, answer, answerState, responseJson);
    }

    /**
     * 用户提前交卷：不写答案行，当前题不动，但同样要让会话进入 COMPLETED 并请求评估。
     *
     * <p>{@code completing = true} 是刻意的：判断「要不要收束到评估」看的是这个标记，
     * 而「怎么推进」由 {@code action} 决定——两者混为一谈会让交卷走成一次普通作答，
     * 把已结束的会话写回进行中（真实链路集成测试抓到过）。
     */
    public static InterviewTurnCommit ofFinish(String sessionId, String requestId, String payloadHash,
                                               int expectedVersion, String expectedQuestionId,
                                               int currentIndex, String responseJson) {
        return new InterviewTurnCommit(sessionId, requestId, ACTION_COMPLETE, payloadHash,
            expectedVersion, expectedQuestionId, currentIndex, expectedQuestionId, null, null,
            InterviewTurnDTO.ACTION_FINISH_USER, true, null, null, null, null, null, responseJson);
    }

    /** 是否为「提前交卷」（不动当前题，只收束会话） */
    public boolean finishing() {
        return ACTION_COMPLETE.equals(action);
    }

    /** 本轮是否要写入答案事实（结束动作只改会话状态） */
    public boolean writesAnswer() {
        return questionId != null && answerState != null;
    }
}
