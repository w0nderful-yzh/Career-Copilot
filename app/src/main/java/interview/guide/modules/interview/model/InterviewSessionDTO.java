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
    String evaluateError
) {

    /** 兼容旧构造点（无评估状态：进行中会话与内部恢复路径） */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory, boolean adaptive) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status,
            knowledgeBaseId, interviewCategory, adaptive, null, null);
    }

    /** 兼容旧构造点（无 adaptive 与评估状态），默认非自适应 */
    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions,
            status, knowledgeBaseId, interviewCategory, false, null, null);
    }

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
