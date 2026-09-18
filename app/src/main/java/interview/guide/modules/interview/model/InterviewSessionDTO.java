package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import java.util.List;

/**
 * 面试会话 DTO（P4-1 起：候选素材与实际轮次分开表达）。
 *
 * <p>三样东西各自独立，不要互相推导：
 * <ul>
 *   <li>{@link #candidates}：**可以问什么**（素材池，只读；未问过的候选择问也在里面）</li>
 *   <li>{@link #turns}：**实际发生了什么**（作答 / 跳过 / 明确不会，按发生顺序）</li>
 *   <li>{@link #currentQuestion}：下一步要回答的那一题（服务端按标识定位好，前端不必再按下标推）</li>
 * </ul>
 *
 * @param totalQuestions    候选池规模；**不是进度分母**（自适应会话会跳过候选追问）
 * @param currentQuestionId 当前待答题标识（P4-1：推进闸门与定位都用它）
 * @param candidates        候选素材（只读）
 * @param turns             实际轨迹（唯一事实；未考察项不在这里）
 * @param endReason         结束原因：CANDIDATES_EXHAUSTED / USER_FINISHED；null = 未结束或历史场次
 */
public record InterviewSessionDTO(
    String sessionId,
    String resumeText,
    int totalQuestions,
    /** 候选池内顺序（展示/旧数据兼容用；判断路径请用 currentQuestionId） */
    int currentQuestionIndex,
    String currentQuestionId,
    InterviewQuestionDTO currentQuestion,
    List<InterviewQuestionDTO> candidates,
    List<InterviewTurnDTO> turns,
    SessionStatus status,
    Long knowledgeBaseId,
    String interviewCategory,
    boolean adaptive,
    /**
     * 报告异步任务状态（P4Q-4）：null = 尚未进入评估（会话进行中）。
     *
     * <p>前端据它区分「评估中」（PENDING/PROCESSING）与「评估失败」（FAILED）——
     * 只看 {@code status} 时两者都是 COMPLETED，失败会被一直显示成「评估中」。
     */
    AsyncTaskStatus evaluateStatus,
    /** 评估失败原因（evaluateStatus = FAILED 时用于展示与排查） */
    String evaluateError,
    /**
     * 简历来源（P4Q-1）：RESUME_VERSION / RESUME_TEXT / EXPLICIT_TEXT / NONE。
     *
     * <p>null 表示「迁移前的旧会话未记录来源」，前端按「未记录」展示，不要当成通用面试。
     */
    String resumeSource,
    /** 出题使用的简历版本号（来源为 RESUME_VERSION 时有值） */
    Integer resumeVersion,
    /** 会话推进版本（P4-9a）：下一次提交把它作为 expectedVersion 回传 */
    Integer turnVersion,
    /** 结束原因（P4-1） */
    String endReason,
    /** 预计时长（分钟，P4Q-2）；null = 旧会话未记录计划 */
    Integer plannedDurationMinutes,
    /** 用户答题累计耗时（秒，P4Q-2）：不含模型等待与暂停 */
    int consumedSeconds,
    /** 剩余预算（秒，P4Q-2）；无计划时为 null（前端不显示假数字） */
    Integer remainingSeconds,
    /** 必要覆盖话题（P4Q-2）；空 = 未声明（覆盖状态由前端按 candidates×turns 推导展示） */
    List<String> requiredTopics
) {

    /** 兼容构造点：只有素材、没有轨迹（知识库面试等尚未接入 P4-1 的链路） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> candidates, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex,
            currentQuestionId(candidates, currentQuestionIndex, status),
            currentQuestion(candidates, currentQuestionIndex, status), candidates,
            List.of(), status, knowledgeBaseId, interviewCategory, adaptive, null, null, null, null,
            0, null, null, 0, null, List.of());
    }

    /** 兼容构造点（带评估状态但未带简历来源） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> candidates, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive,
        AsyncTaskStatus evaluateStatus, String evaluateError) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex,
            currentQuestionId(candidates, currentQuestionIndex, status),
            currentQuestion(candidates, currentQuestionIndex, status), candidates,
            List.of(), status, knowledgeBaseId, interviewCategory, adaptive, evaluateStatus,
            evaluateError, null, null, 0, null, null, 0, null, List.of());
    }

    /** 兼容构造点：带简历来源与版本（创建 / 报告链路不关心轨迹，轨迹由会话读取补齐） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> candidates, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive,
        AsyncTaskStatus evaluateStatus, String evaluateError,
        String resumeSource, Integer resumeVersion) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex,
            currentQuestionId(candidates, currentQuestionIndex, status),
            currentQuestion(candidates, currentQuestionIndex, status), candidates,
            List.of(), status, knowledgeBaseId, interviewCategory, adaptive, evaluateStatus,
            evaluateError, resumeSource, resumeVersion, 0, null, null, 0, null, List.of());
    }

    /** 兼容旧构造点（无 adaptive 与评估状态），默认非自适应 */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> candidates, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex,
            currentQuestionId(candidates, currentQuestionIndex, status),
            currentQuestion(candidates, currentQuestionIndex, status), candidates,
            List.of(), status, knowledgeBaseId, interviewCategory, false, null, null, null, null,
            0, null, null, 0, null, List.of());
    }

    private static String currentQuestionId(List<InterviewQuestionDTO> candidates, int currentIndex,
                                            SessionStatus status) {
        InterviewQuestionDTO question = currentQuestion(candidates, currentIndex, status);
        return question != null ? question.questionId() : null;
    }

    private static InterviewQuestionDTO currentQuestion(List<InterviewQuestionDTO> candidates,
                                                        int currentIndex, SessionStatus status) {
        if (status == SessionStatus.COMPLETED || status == SessionStatus.EVALUATED
            || candidates == null || currentIndex < 0 || currentIndex >= candidates.size()) {
            return null;
        }
        return candidates.get(currentIndex);
    }

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
