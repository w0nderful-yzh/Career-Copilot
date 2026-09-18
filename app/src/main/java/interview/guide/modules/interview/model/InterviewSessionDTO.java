package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import java.util.List;

/**
 * 面试会话DTO
 */
public record InterviewSessionDTO(
    String sessionId,
    String resumeText,
    int totalQuestions,
    int currentQuestionIndex,
    List<InterviewQuestionDTO> questions,
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
    /**
     * 会话推进版本（P4-9a）：作答 / 跳过 / 结束各 +1。
     *
     * <p>前端提交下一轮时把它当作 expectedVersion 回传；服务端据此拒绝过期请求
     * （例如另一个标签页已经答完这题、或用户已经结束面试）。版本对不上时服务端会先
     * 同步最新进度再返回可见原因，不会静默改写历史。
     */
    Integer turnVersion
) {

    /** 兼容早期构造点（无简历来源/版本，也未带评估状态） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status,
            knowledgeBaseId, interviewCategory, adaptive, null, null, null, null, 0);
    }

    /** 兼容构造点（带评估状态但未带简历来源：评估链路只关心进度与失败原因） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive,
        AsyncTaskStatus evaluateStatus, String evaluateError) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status,
            knowledgeBaseId, interviewCategory, adaptive, evaluateStatus, evaluateError, null, null, 0);
    }

    /** 兼容旧构造点（无 adaptive 与评估状态），默认非自适应 */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions,
            status, knowledgeBaseId, interviewCategory, false, null, null, null, null, 0);
    }

    /** 兼容构造点（带简历来源与版本，对话版本由上游补） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive,
        AsyncTaskStatus evaluateStatus, String evaluateError,
        String resumeSource, Integer resumeVersion) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status,
            knowledgeBaseId, interviewCategory, adaptive, evaluateStatus, evaluateError,
            resumeSource, resumeVersion, 0);
    }

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
