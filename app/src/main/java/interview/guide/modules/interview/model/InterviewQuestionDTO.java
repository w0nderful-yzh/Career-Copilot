package interview.guide.modules.interview.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 面试候选素材（P4-1）。
 *
 * <p><b>本类型只描述「可以问什么」，不描述「实际发生了什么」。</b>
 * 作答、状态、评分、最终决定都属于实际轮次，见 {@link InterviewTurnDTO} 与
 * {@code interview_answers} 表。此前两者同处一个 record，靠
 * {@code withAnswer/withAnswerState} 原地写回同一数组，于是「候选择问」与「真实轮次」
 * 只能靠「有没有 answerState」区分——这次把两者彻底分开。
 *
 * <p><b>身份用 {@link #questionId}，不再用数组下标。</b>
 * {@link #questionIndex} 只是候选池内的顺序（供展示与历史兼容）；排序、合并、追问归属
 * 都不再依赖它。旧会话的题目没有 id，读取时按 {@link #legacyIdFor(int)} 派生，
 * 与实际轮次里回填的 {@code legacy-<questionIndex>} 对齐。
 *
 * @param questionId        稳定标识（P4-1）；候选池内唯一，跨排序不变
 * @param questionIndex     候选池内顺序（0 起）；仅用于展示与旧数据兼容
 * @param type              Skill category key，如 "MYSQL"、"CSS"、"DP"
 * @param category          考察技能名，如 "MySQL"、"Java"；话题类候选为 null（见 topic）
 * @param topic             交流话题，如「项目经历」；与「考察技能」分开表达（P4-1）
 * @param topicSummary      知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
 * @param isFollowUp        是否为追问
 * @param parentQuestionId  追问所属主问题的稳定标识；主问题为 null
 * @param difficulty        数值难度 1-5（低→高），null 表示未标注（知识库题等来源）
 * @param followUpType      追问语义类型（DEPTH/SCENARIO/WHY/...），主问题为 null
 * @param expectedPoints    期望答出的要点（供评估与决策参考），可为 null
 * @param followUpIndex     追问序号：同一主问题下的第几条追问，主问题为 null
 * @param candidateSource   候选来源（P4-4b）：PRE_GENERATED / MODEL_GENERATED / BACKGROUND
 * @param candidateVersion  候选所属的会话代次（P4-4b）：受限生成与后台预备带上当时的 candidate_version，
 *                          后台结果据此判过期失效；预生成候选为 null
 */
public record InterviewQuestionDTO(
    String questionId,
    int questionIndex,
    String question,
    String type,
    String category,
    String topic,
    String topicSummary,
    boolean isFollowUp,
    String parentQuestionId,
    /** 只用于读取 P4-1 之前的 questions_json；新响应不再暴露旧父索引 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    Integer parentQuestionIndex,
    String referenceAnswer,
    List<String> keyPoints,
    String scoringRubric,
    String sourceContext,
    Integer difficulty,
    String followUpType,
    List<String> expectedPoints,
    Integer followUpIndex,
    String candidateSource,
    Integer candidateVersion
) {
    /** 追问类型：继续深挖原理（默认） */
    public static final String FOLLOW_UP_DEPTH = "DEPTH";
    /** 追问类型：换场景考察应用 */
    public static final String FOLLOW_UP_SCENARIO = "SCENARIO";
    /** 追问类型：追问原理/为什么 */
    public static final String FOLLOW_UP_WHY = "WHY";
    /** 追问类型：澄清回答 */
    public static final String FOLLOW_UP_CLARIFICATION = "CLARIFICATION";
    /** 追问类型：技术取舍（为什么选 A 不选 B） */
    public static final String FOLLOW_UP_TRADEOFF = "TRADEOFF";
    /** 追问类型：线上故障定位与修复 */
    public static final String FOLLOW_UP_FAILURE = "FAILURE";
    /** 追问类型：个人贡献澄清（团队里你做了什么） */
    public static final String FOLLOW_UP_CONTRIBUTION = "CONTRIBUTION";

    /** 候选来源：创建时一次性预生成（默认） */
    public static final String CANDIDATE_SOURCE_PRE_GENERATED = "PRE_GENERATED";
    /** 候选来源：无合适候选时同轮受限生成（P4-4b） */
    public static final String CANDIDATE_SOURCE_MODEL_GENERATED = "MODEL_GENERATED";
    /** 候选来源：后台异步预备（P4-4b） */
    public static final String CANDIDATE_SOURCE_BACKGROUND = "BACKGROUND";

    /** 旧数据（P4-1 之前的 questions_json 与实际轮次）按此规则派生题目标识 */
    public static final String LEGACY_ID_PREFIX = "legacy-";

    /** 新题目的稳定标识（不依赖下标：排序、合并都不影响它） */
    public static String newId() {
        return "q" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 旧题目的派生标识：与迁移里回填的 {@code interview_answers.question_id} 同一规则 */
    public static String legacyIdFor(int questionIndex) {
        return LEGACY_ID_PREFIX + questionIndex;
    }

    /** 是否为旧数据派生出来的标识（意味着「顺序变化后不再稳定」，仅用于兼容与排查） */
    public boolean legacyIdentity() {
        return questionId != null && questionId.startsWith(LEGACY_ID_PREFIX);
    }

    // ===== 工厂 =====

    /** 主问题/基础题工厂（无追问语义与难度标注，知识库等旧来源保持默认） */
    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(newId(), index, question, type, category, null, null, false, null,
            null, null, null, null, null, null, null, null, null, CANDIDATE_SOURCE_PRE_GENERATED, null);
    }

    /** 顺序题单工厂（现 /interview 页沿用线性语义） */
    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                              String topicSummary, boolean isFollowUp,
                                              String parentQuestionId) {
        return new InterviewQuestionDTO(newId(), index, question, type, category, null, topicSummary,
            isFollowUp, parentQuestionId, null, null, null, null, null, null, null, null, null,
            CANDIDATE_SOURCE_PRE_GENERATED, null);
    }

    /** 主问题工厂：带数值难度与考察要点（供自适应决策/评估） */
    public static InterviewQuestionDTO createMain(int index, String question, String type, String category,
                                                  String topicSummary, Integer difficulty,
                                                  List<String> expectedPoints) {
        return new InterviewQuestionDTO(newId(), index, question, type, category, null, topicSummary,
            false, null, null, null, null, null, null, difficulty, null, expectedPoints, null,
            CANDIDATE_SOURCE_PRE_GENERATED, null);
    }

    /**
     * 追问工厂：挂到父主问题（用父的稳定标识），带追问语义类型与**追问序号**。
     *
     * <p>category 传主问题的稳定技能名（不要自己拼「（追问N）」）——追问身份由
     * followUpIndex 独立表达，拼进技能名会污染画像证据（见 P4Q-6）。
     */
    public static InterviewQuestionDTO createFollowUp(int index, String question, String type,
                                                      String category, String parentQuestionId,
                                                      int followUpIndex, String followUpType,
                                                      List<String> expectedPoints) {
        return new InterviewQuestionDTO(newId(), index, question, type, category, null, null, true,
            parentQuestionId, null, null, null, null, null, null, followUpType, expectedPoints,
            followUpIndex > 0 ? followUpIndex : null, CANDIDATE_SOURCE_PRE_GENERATED, null);
    }

    /** 题库来源工厂（知识库等：无 P4-1 数值难度，difficulty 留空由决策期默认） */
    public static InterviewQuestionDTO fromQuestionBank(int index, String question, String type,
                                                        String category, String topicSummary,
                                                        String referenceAnswer, List<String> keyPoints,
                                                        String scoringRubric, String sourceContext) {
        return new InterviewQuestionDTO(newId(), index, question, type, category, null, topicSummary,
            false, null, null, referenceAnswer, keyPoints, scoringRubric, sourceContext, null, null,
            null, null, CANDIDATE_SOURCE_PRE_GENERATED, null);
    }

    /** 题库追问工厂（知识库等来源的追问：带参考答案，但无 P4-1 followUpType 标注） */
    public static InterviewQuestionDTO fromQuestionBankFollowUp(int index, String question, String type,
                                                                String category, String parentQuestionId,
                                                                String referenceAnswer,
                                                                List<String> keyPoints,
                                                                String scoringRubric,
                                                                String sourceContext) {
        return new InterviewQuestionDTO(newId(), index, question, type, category, null, null, true,
            parentQuestionId, null, referenceAnswer, keyPoints, scoringRubric, sourceContext, null,
            null, null, null, CANDIDATE_SOURCE_PRE_GENERATED, null);
    }

    // ===== 派生（只改素材自身的字段，绝不承载实际轮次） =====

    /** 重新编号（合并两批题单时用）：只改展示顺序，标识与父链保持不变 */
    public InterviewQuestionDTO withIndex(int newIndex) {
        return new InterviewQuestionDTO(questionId, newIndex, question, type, category, topic,
            topicSummary, isFollowUp, parentQuestionId, parentQuestionIndex, referenceAnswer,
            keyPoints, scoringRubric, sourceContext, difficulty, followUpType, expectedPoints,
            followUpIndex, candidateSource, candidateVersion);
    }

    /** 补上稳定标识（旧数据读取路径 / 合并后统一发号） */
    public InterviewQuestionDTO withQuestionId(String newQuestionId) {
        return new InterviewQuestionDTO(newQuestionId, questionIndex, question, type, category, topic,
            topicSummary, isFollowUp, parentQuestionId, parentQuestionIndex, referenceAnswer,
            keyPoints, scoringRubric, sourceContext, difficulty, followUpType, expectedPoints,
            followUpIndex, candidateSource, candidateVersion);
    }

    /** 补上话题 / 技能归属（P4-1：出题侧决定，读取路径不猜） */
    public InterviewQuestionDTO withFocus(String newCategory, String newTopic) {
        return new InterviewQuestionDTO(questionId, questionIndex, question, type, newCategory, newTopic,
            topicSummary, isFollowUp, parentQuestionId, parentQuestionIndex, referenceAnswer,
            keyPoints, scoringRubric, sourceContext, difficulty, followUpType, expectedPoints,
            followUpIndex, candidateSource, candidateVersion);
    }

    /** 旧 questions_json 的 parentQuestionIndex → 新稳定父标识。 */
    public InterviewQuestionDTO withParentQuestionId(String newParentQuestionId) {
        return new InterviewQuestionDTO(questionId, questionIndex, question, type, category, topic,
            topicSummary, isFollowUp, newParentQuestionId, parentQuestionIndex, referenceAnswer,
            keyPoints, scoringRubric, sourceContext, difficulty, followUpType, expectedPoints,
            followUpIndex, candidateSource, candidateVersion);
    }

    /**
     * 标记候选来源与会话代次（P4-4b）：受限生成写 MODEL_GENERATED、后台预备写 BACKGROUND，
     * 都带上当时的 candidateVersion 以便过期结果据版本失效。
     */
    public InterviewQuestionDTO withCandidateSource(String newSource, Integer newVersion) {
        return new InterviewQuestionDTO(questionId, questionIndex, question, type, category, topic,
            topicSummary, isFollowUp, parentQuestionId, parentQuestionIndex, referenceAnswer,
            keyPoints, scoringRubric, sourceContext, difficulty, followUpType, expectedPoints,
            followUpIndex, newSource, newVersion);
    }

    /** 是否为运行期动态产生的候选（同轮生成或后台预备），用于展示与审计区分 */
    public boolean isDynamicallyGenerated() {
        return CANDIDATE_SOURCE_MODEL_GENERATED.equals(candidateSource)
            || CANDIDATE_SOURCE_BACKGROUND.equals(candidateSource);
    }

    /** 该题是否属于追问组（供决策与展示分组） */
    public boolean isMain() {
        return !isFollowUp;
    }

    /**
     * 该题是否属于某话题（P4Q-2）：topic 优先（P4-1），旧数据退回 category。
     *
     * <p>计划从 Agent / 前端传入的通常是分类 key（如 PROJECT），而题目同时保存
     * type=key、category/topic=展示名；三者都要参与匹配，否则「项目经历」会被误删成不存在。
     * 话题匹配是「必要覆盖是否完成」与「覆盖摘要」共用的判据：抽到 DTO 上避免两处各写一份，
     * 否则硬边界与喂给模型的覆盖状态会悄悄分叉。
     */
    public boolean matchesTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        return topicMatches(this.topic, topic)
            || topicMatches(this.category, topic)
            || topicMatches(this.type, topic);
    }

    private static boolean topicMatches(String value, String expected) {
        return value != null && !value.isBlank() && (value.equalsIgnoreCase(expected)
            || value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)));
    }
}
