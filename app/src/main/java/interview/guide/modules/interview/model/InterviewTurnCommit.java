package interview.guide.modules.interview.model;

/**
 * 一轮推进的提交命令（P4-9a）。
 *
 * <p>它是「已经算好的决定」——下一题、答案状态、结束与否都在模型调用与选题策略完成后确定，
 * 本对象只负责把这次推进**一次性、原子地**写进数据库：
 * 会话（版本 / 索引 / 状态 / 评估请求）+ 答案事实 + 幂等记录同属一个短事务。
 * 因此模型调用必然在事务之外，而部分写入不可能留下半状态。
 *
 * @param requestId  请求标识；null 表示调用方未提供（兼容旧的逐轮调用，退化为「仅并发闸门」）
 * @param payloadHash 载荷指纹；配合 requestId 判「同一标识不同载荷」
 * @param completing  true 表示这一轮之后会话进入 COMPLETED 并请求评估——
 *                    最后一轮作答与提前交卷都属于这种情况；动作本身（ANSWER / SKIP / COMPLETE）
 *                    决定推进方式：COMPLETE 不动索引，作答/跳过要移到下一题
 * @param questionIndex 答案行的题号；结束动作不写答案行时为 null
 */
public record InterviewTurnCommit(
    String sessionId,
    String requestId,
    String action,
    String payloadHash,
    int expectedVersion,
    int expectedIndex,
    int newIndex,
    boolean completing,
    Integer questionIndex,
    String question,
    String category,
    String answer,
    InterviewAnswerEntity.AnswerState answerState,
    String responseJson
) {

    /** 提前交卷的动作名（与会话推进动作区分：它不改索引，只把会话收束到评估） */
    public static final String ACTION_COMPLETE = "COMPLETE";

    /** 作答 / 跳过：推进到下一题（或结束） */
    public static InterviewTurnCommit ofTurn(String sessionId, String requestId, String action,
                                             String payloadHash, int expectedVersion,
                                             int expectedIndex, int newIndex, boolean completing,
                                             int questionIndex, String question, String category,
                                             String answer,
                                             InterviewAnswerEntity.AnswerState answerState,
                                             String responseJson) {
        return new InterviewTurnCommit(sessionId, requestId, action, payloadHash, expectedVersion,
            expectedIndex, newIndex, completing, questionIndex, question, category, answer,
            answerState, responseJson);
    }

    /**
     * 用户提前交卷：不写答案行，索引不动，但同样要让会话进入 COMPLETED 并请求评估。
     *
     * <p>{@code completing = true} 是刻意的：判断「要不要收束到评估」看的是这个标记，
     * 而「怎么推进」由 {@code action} 决定——两者混为一谈会让交卷走成一次普通作答，
     * 把已结束的会话写回进行中（真实链路集成测试抓到过）。
     */
    public static InterviewTurnCommit ofFinish(String sessionId, String requestId, String payloadHash,
                                               int expectedVersion, int expectedIndex,
                                               String responseJson) {
        return new InterviewTurnCommit(sessionId, requestId, ACTION_COMPLETE, payloadHash,
            expectedVersion, expectedIndex, expectedIndex, true, null, null, null, null, null,
            responseJson);
    }

    /** 是否为「提前交卷」（不动索引，只收束会话） */
    public boolean finishing() {
        return ACTION_COMPLETE.equals(action);
    }

    /** 本轮是否要写入答案事实（结束动作只改会话状态） */
    public boolean writesAnswer() {
        return questionIndex != null && answerState != null;
    }
}
