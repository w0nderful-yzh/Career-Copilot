package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试评估报告
 */
public record InterviewReportDTO(
    String sessionId,
    int totalQuestions,
    Integer overallScore,                      // 总分 (0-100)；证据不足时为 null
    List<CategoryScore> categoryScores,        // 各类别得分
    List<QuestionEvaluation> questionDetails,  // 每题详情
    String overallFeedback,                    // 总体评价
    List<String> strengths,                    // 优势
    List<String> improvements,                 // 改进建议
    List<ReferenceAnswer> referenceAnswers,    // 参考答案
    String scoringRuleVersion,                 // 可重算的确定性聚合规则版本
    String aggregationMethod,                  // 面向用户的聚合口径
    List<TopicCoverage> coverage,               // 覆盖 / 未考察 / 证据不足
    List<String> unassessedTopics,
    List<String> skippedQuestionIds,
    List<String> insufficientEvidenceQuestionIds
) {
    /**
     * 类别得分
     */
    public record CategoryScore(
        String category,
        Integer score,
        int questionCount,
        int mainGroupCount,
        int evaluatedMainGroupCount,
        String aggregationNote
    ) {}
    
    /**
     * 问题评估详情
     */
    public record QuestionEvaluation(
        int questionIndex,
        String questionId,
        Integer turnOrdinal,
        String question,
        String category,
        String topic,
        boolean followUp,
        String parentQuestionId,
        Integer difficulty,
        List<String> expectedPoints,
        String userAnswer,
        String answerState,
        Integer score,
        String feedback,
        String realtimeDecision,
        String decisionReason,
        String decisionComparison
    ) {}

    /** 报告覆盖口径：未考察、跳过与正式评估不可用必须显式区分。 */
    public record TopicCoverage(
        String topic,
        boolean required,
        String status,
        int actualTurnCount,
        int answeredTurnCount,
        int skippedTurnCount,
        int evaluatedMainGroupCount,
        List<String> evidenceQuestionIds,
        List<String> expectedPoints
    ) {}
    
    /**
     * 参考答案
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
