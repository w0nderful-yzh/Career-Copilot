package interview.guide.modules.interview.model;

import interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实际轮次（P4-1）：这一轮**真的问过**，以及用户实际怎么答的。
 *
 * <p>与候选素材（{@link InterviewQuestionDTO}）分开的理由：
 * 候选是「可以问什么」的素材池，实际轮次是面试轨迹的唯一事实。轨迹一旦写进候选池的数组，
 * 顺序调整、候选择问与真实轮次就再也分不开了。
 *
 * <p>落库对应 {@code interview_answers} 一行：{@code question_id} 是身份，
 * {@code turn_ordinal} 是真实发生顺序（为空表示报告补写的未考察项，不属于轨迹），
 * {@code decided_action} 是这一轮的最终决定（决策引擎给出并被 Java 接纳的那个动作）。
 *
 * @param questionId     题目稳定标识（候选池内唯一）
 * @param ordinal        真实发生顺序（1 起）；报告补写的未考察项为 null
 * @param questionIndex  候选池内顺序（展示/兼容用）
 * @param question       提问原文（冻结当时文本：素材后续变化不影响历史）
 * @param category       考察技能名；话题类轮次为 null
 * @param topic          交流话题，如「项目经历」；与技能分开表达
 * @param userAnswer     用户作答原文；跳过/未作答为 null
 * @param answerState    ANSWERED / SKIPPED / DECLINED / UNANSWERED
 * @param score          正式报告回填的评分；报告生成前为 null
 * @param feedback       正式报告回填的反馈
 * @param decidedAction  本轮最终决定（FOLLOW_UP_MAIN / NEXT_MAIN / FINISH_* 等，见常量）
 * @param referenceAnswer 参考答案（报告回填）
 * @param keyPoints      关键点（报告回填）
 * @param occurredAt     发生时间
 * @param decidedNextQuestionId Java 最终选择的下一题标识；收束时为空
 * @param decisionReason Java 最终决定依据
 * @param transitionMessage 实际展示的简短承接语
 */
public record InterviewTurnDTO(
    String questionId,
    Integer ordinal,
    int questionIndex,
    String question,
    String category,
    String topic,
    String userAnswer,
    AnswerState answerState,
    Integer score,
    String feedback,
    String decidedAction,
    String referenceAnswer,
    List<String> keyPoints,
    LocalDateTime occurredAt,
    String decidedNextQuestionId,
    String decisionReason,
    String transitionMessage
) {

    /** 兼容既有构造点：P4Q-3b 之前没有持久化决策依据与承接语 */
    public InterviewTurnDTO(String questionId, Integer ordinal, int questionIndex,
                            String question, String category, String topic,
                            String userAnswer, AnswerState answerState, Integer score,
                            String feedback, String decidedAction, String referenceAnswer,
                            List<String> keyPoints, LocalDateTime occurredAt) {
        this(questionId, ordinal, questionIndex, question, category, topic, userAnswer,
            answerState, score, feedback, decidedAction, referenceAnswer, keyPoints, occurredAt,
            null, null, null);
    }

    /** 决定：继续深挖当前主问题的追问 */
    public static final String ACTION_FOLLOW_UP = "FOLLOW_UP";
    /** 决定：转入下一个主问题 */
    public static final String ACTION_NEXT_MAIN = "NEXT_MAIN";
    /** 决定：候选素材已耗尽，面试结束 */
    public static final String ACTION_FINISH_EXHAUSTED = "FINISH_EXHAUSTED";
    /** 决定：必要覆盖已完成（P4Q-2） */
    public static final String ACTION_FINISH_COVERAGE = "FINISH_COVERAGE";
    /** 决定：时间预算用尽（P4Q-2），只统计用户答题时间 */
    public static final String ACTION_FINISH_BUDGET = "FINISH_BUDGET";
    /** 决定：用户主动结束 */
    public static final String ACTION_FINISH_USER = "FINISH_USER";

    /**
     * 从答案实体映射（P4-1）：落库形态就是实际轮次的权威形态。
     *
     * @param keyPoints 已解析的关键点（JSON 解析留在调用方，避免 DTO 依赖 ObjectMapper）
     */
    public static InterviewTurnDTO from(InterviewAnswerEntity answer, List<String> keyPoints) {
        return new InterviewTurnDTO(
            answer.getQuestionId(),
            answer.getTurnOrdinal(),
            answer.getQuestionIndex() == null ? 0 : answer.getQuestionIndex(),
            answer.getQuestion(),
            answer.getCategory(),
            null,
            answer.getUserAnswer(),
            answer.getAnswerState(),
            answer.getScore(),
            answer.getFeedback(),
            answer.getDecidedAction(),
            answer.getReferenceAnswer(),
            keyPoints,
            answer.getAnsweredAt(),
            answer.getDecidedNextQuestionId(),
            answer.getDecisionReason(),
            answer.getTransitionMessage());
    }

    /** 是否为「用户答过/跳过的真实轮次」（报告补写的未考察项不算） */
    public boolean isTurn() {
        return ordinal != null;
    }

    /** 这一轮是否计入评分与画像证据：只有真实作答算（与实体口径一致） */
    public boolean countsAsAnswer() {
        return answerState == AnswerState.ANSWERED;
    }

    /** 展示用：题号（真实发生顺序优先，缺失时用候选池顺序） */
    public int displayOrdinal() {
        return ordinal != null ? ordinal : questionIndex + 1;
    }
}
