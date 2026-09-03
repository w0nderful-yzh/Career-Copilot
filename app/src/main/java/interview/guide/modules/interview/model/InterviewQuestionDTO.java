package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试问题DTO
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 *
 * P4-1 题库结构化：追加 difficulty（数值难度）/ followUpType（追问语义类型）/
 * expectedPoints（考察要点）。生成时写入，评估与决策可消费；旧 questionsJson
 * 缺这些字段时 Jackson 反序列化为 null，向后兼容。
 */
public record InterviewQuestionDTO(
    int questionIndex,
    String question,
    String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
    String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    String userAnswer,
    Integer score,
    String feedback,
    boolean isFollowUp,
    Integer parentQuestionIndex,
    String referenceAnswer,
    List<String> keyPoints,
    String scoringRubric,
    String sourceContext,
    Integer difficulty,    // 数值难度 1-5（低→高），null 表示未标注（知识库题等来源）
    String followUpType,   // 追问语义类型（DEPTH/SCENARIO/WHY/...），主问题为 null
    List<String> expectedPoints  // 期望答出的要点（供评估与决策参考），可为 null
) {
    /** 追问类型：继续深挖原理（默认） */
    public static final String FOLLOW_UP_DEPTH = "DEPTH";
    /** 追问类型：换场景考察应用 */
    public static final String FOLLOW_UP_SCENARIO = "SCENARIO";
    /** 追问类型：追问原理/为什么 */
    public static final String FOLLOW_UP_WHY = "WHY";
    /** 追问类型：澄清回答 */
    public static final String FOLLOW_UP_CLARIFICATION = "CLARIFICATION";

    /** 主问题/基础题工厂（无追问语义与难度标注，知识库等旧来源保持默认） */
    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(
            index, question, type, category, null, null, null, null, false, null, null, null, null, null,
            null, null, null);
    }

    /** 顺序题单工厂（现 /interview 页沿用线性语义；P4-1 生成的题也走此工厂） */
    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex,
            null, null, null, null, null, null, null);
    }

    /** P4-1 主问题工厂：带数值难度与考察要点（供自适应决策/评估） */
    public static InterviewQuestionDTO createMain(int index, String question, String type, String category,
                                                   String topicSummary, Integer difficulty,
                                                   List<String> expectedPoints) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, false, null,
            null, null, null, null, difficulty, null, expectedPoints);
    }

    /** P4-1 追问工厂：挂到父主问题，带追问语义类型 */
    public static InterviewQuestionDTO createFollowUp(int index, String question, String type, String category,
                                                       int mainIndex, String followUpType,
                                                       List<String> expectedPoints) {
        return new InterviewQuestionDTO(
            index, question, type, category, null, null, null, null, true, mainIndex,
            null, null, null, null, null, followUpType, expectedPoints);
    }

    /** 题库来源工厂（知识库等：无 P4-1 数值难度，difficulty 留空由决策期默认） */
    public static InterviewQuestionDTO fromQuestionBank(int index, String question, String type,
                                                        String category, String topicSummary,
                                                        String referenceAnswer, List<String> keyPoints,
                                                        String scoringRubric, String sourceContext) {
        return new InterviewQuestionDTO(
            index, question, type, category, topicSummary, null, null, null, false, null,
            referenceAnswer, keyPoints, scoringRubric, sourceContext, null, null, null);
    }

    /** 题库追问工厂（知识库等来源的追问：带参考答案，但无 P4-1 followUpType 标注） */
    public static InterviewQuestionDTO fromQuestionBankFollowUp(int index, String question, String type,
                                                                String category, int mainIndex,
                                                                String referenceAnswer, List<String> keyPoints,
                                                                String scoringRubric, String sourceContext) {
        return new InterviewQuestionDTO(
            index, question, type, category, null, null, null, null, true, mainIndex,
            referenceAnswer, keyPoints, scoringRubric, sourceContext, null, null, null);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, answer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext,
            difficulty, followUpType, expectedPoints);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
            isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext,
            difficulty, followUpType, expectedPoints);
    }
}
