package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试进展视图（P4-10）：给 Agent 按需读取「现在考到哪、已经发生了什么」。
 *
 * <p>为什么要有它：面试进行中，用户会问「现在考到哪了」「还剩什么要考」。
 * 此前 Agent 只有「结束后的报告」与「历史列表」两条读路径，中途的场次对它是不透明的，
 * 只能等面试结束才说得上话。这里复用会话读取（P4-1 候选/轨迹、P4Q-2 计划与预算）
 * 与逐轮评估同一套推导，**不新增第二份事实来源**。
 *
 * <p>覆盖与预算以文本摘要形式给出（{@code coverageSummary} / {@code budgetSummary}）：
 * 与喂给逐轮评估的上下文同源同措辞，因此 Agent 说「还差什么」与 Java 是否按覆盖收束不会分叉。
 *
 * @param sessionId              会话 ID
 * @param status                 CREATED / IN_PROGRESS / COMPLETED / EVALUATED
 * @param endReason              结束原因（P4-1/P4Q-2 四值）；未结束为 null
 * @param plannedDurationMinutes 预计时长（分钟）；null = 旧会话未记录计划
 * @param consumedSeconds        用户答题累计耗时（秒）：不含模型等待与暂停
 * @param remainingSeconds       剩余预算（秒）；无计划为 null
 * @param requiredTopics         必要覆盖话题；空 = 未声明
 * @param coverageSummary        覆盖摘要（多行）：必要覆盖逐项状态 / 已问话题 / 尚未问的话题
 * @param budgetSummary          预算摘要（多行）：剩余时间与当前话题组剩余追问额度
 * @param legalCandidates        下一步策略**实际可能选中**的题（带稳定标识，已格式化）
 * @param currentQuestion        当前待答题；已结束或候选耗尽时为 null
 * @param turns                  已发生轮次摘要（按发生顺序；回答文本已裁剪）
 * @param turnDetail             指定 questionId 时的完整轮次详情；未指定或找不到为 null
 * @param askedTurnCount         已发生轮次数
 * @param satisfiedRequiredTopicCount 已满足的必要覆盖话题数（judged by 实际轨迹）
 */
public record InterviewProgressDTO(
    String sessionId,
    String status,
    String endReason,
    Integer plannedDurationMinutes,
    int consumedSeconds,
    Integer remainingSeconds,
    List<String> requiredTopics,
    String coverageSummary,
    String budgetSummary,
    List<String> legalCandidates,
    ProgressQuestion currentQuestion,
    List<ProgressTurn> turns,
    ProgressTurn turnDetail,
    int askedTurnCount,
    int satisfiedRequiredTopicCount
) {

    public InterviewProgressDTO {
        requiredTopics = requiredTopics == null ? List.of() : List.copyOf(requiredTopics);
        legalCandidates = legalCandidates == null ? List.of() : List.copyOf(legalCandidates);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** 当前待答题：只给 Agent 判断「在考什么」需要的字段，不下发整条候选素材 */
    public record ProgressQuestion(
        String questionId,
        String question,
        String topic,
        String category,
        boolean isFollowUp,
        Integer followUpIndex,
        Integer difficulty,
        List<String> expectedPoints
    ) {}

    /**
     * 一轮的实际内容。
     *
     * <p>{@code userAnswer} 在列表里是**裁剪过的**（长回答只留开头），
     * 单轮详情（{@link InterviewProgressDTO#turnDetail()}）才给全文——
     * 让 Agent 能解释「他当时说了什么」，又不至于把整场对话塞进上下文。
     */
    public record ProgressTurn(
        Integer ordinal,
        String questionId,
        String question,
        String topic,
        String category,
        String answerState,
        String userAnswer,
        Integer score,
        String feedback,
        String decidedAction,
        String referenceAnswer,
        List<String> keyPoints,
        LocalDateTime occurredAt
    ) {}
}
