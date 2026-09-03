package interview.guide.modules.interview.model;

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
    boolean adaptive
) {
    /** 兼容旧构造点（无 adaptive 的调用），默认非自适应 */
    public InterviewSessionDTO {
        // 空
    }

    public InterviewSessionDTO(
        String sessionId, String resumeText, int totalQuestions, int currentQuestionIndex,
        List<InterviewQuestionDTO> questions, SessionStatus status,
        Long knowledgeBaseId, String interviewCategory) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions,
            status, knowledgeBaseId, interviewCategory, false);
    }

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
