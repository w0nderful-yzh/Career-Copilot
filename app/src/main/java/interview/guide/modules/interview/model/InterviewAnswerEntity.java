package interview.guide.modules.interview.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 面试答案实体
 */
@Entity
@Table(name = "interview_answers",
    uniqueConstraints = {
        // P4-1：身份从「数组下标」迁到「题目标识」——排序与合并都不再影响它
        @UniqueConstraint(name = "uk_interview_answer_session_question_id",
            columnNames = {"session_id", "question_id"})
    },
    indexes = {
        @Index(name = "idx_interview_answer_session_question", columnList = "session_id,question_index"),
        @Index(name = "idx_interview_answer_session_ordinal", columnList = "session_id,turn_ordinal")
    })
public class InterviewAnswerEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 关联的会话
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSessionEntity session;

    /**
     * 题目稳定标识（P4-1）：候选池内唯一，跨排序不变。
     *
     * <p>旧数据由迁移按 {@code legacy-<questionIndex>} 回填，读取端用同一规则派生。
     */
    @Column(name = "question_id", nullable = false, length = 64)
    private String questionId;

    /**
     * 真实发生顺序（1 起，P4-1）。
     *
     * <p>为空 = 报告补写的「未考察」行：它不属于面试轨迹，前端不会把它当成问过的题。
     */
    @Column(name = "turn_ordinal")
    private Integer turnOrdinal;

    /**
     * 本轮最终决定（P4-1）：跟进追问 / 转下一主问题 / 候选耗尽结束 / 用户结束。
     *
     * <p>记的是**被 Java 接纳后真正执行**的那个动作，不是模型的建议。
     */
    @Column(name = "decided_action", length = 32)
    private String decidedAction;

    // 问题索引（候选池内顺序；展示与旧数据兼容用，不再作身份）
    @Column(name = "question_index")
    private Integer questionIndex;
    
    // 问题内容
    @Column(columnDefinition = "TEXT")
    private String question;
    
    // 问题类别
    private String category;
    
    // 用户答案
    @Column(columnDefinition = "TEXT")
    private String userAnswer;
    
    // 得分 (0-100)
    private Integer score;
    
    // 反馈
    @Column(columnDefinition = "TEXT")
    private String feedback;
    
    // 参考答案
    @Column(columnDefinition = "TEXT")
    private String referenceAnswer;
    
    // 关键点 (JSON)
    @Column(columnDefinition = "TEXT")
    private String keyPointsJson;
    
    // 回答时间
    @Column(nullable = false)
    private LocalDateTime answeredAt;

    /**
     * 答案状态（P4Q-5）：区分真实作答与跳过/未作答/明确不会。
     *
     * <p>只有 {@link AnswerState#ANSWERED} 参与报告评分与画像证据——否则「跳过」会被
     * 当成技术答案打 0 分并污染画像（缺陷期间真实发生过）。
     * 未考察不落库：没有答案行即为未考察（报告补写的行 {@code turnOrdinal} 为空，不属于轨迹）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_state", nullable = false, length = 16)
    private AnswerState answerState = AnswerState.ANSWERED;

    public enum AnswerState {
        /** 真实作答（含「答案 + 换话题指令」的混合消息，有效答案部分保留） */
        ANSWERED,
        /** 用户主动跳过（按钮或明确的跳过指令）：不调模型、不追问、不计分 */
        SKIPPED,
        /** 明确表示不会/不记得：作为诊断信息保留，不作为技术评分 */
        DECLINED,
        /** 已提问但没有任何作答内容 */
        UNANSWERED
    }

    /** 是否计入评分与画像证据 */
    public boolean countsAsAnswer() {
        return answerState == null || answerState == AnswerState.ANSWERED;
    }

    @PrePersist
    protected void onCreate() {
        // 兼容仍按候选顺序构造答案实体的内部调用：数据库身份始终非空。
        if ((questionId == null || questionId.isBlank()) && questionIndex != null) {
            questionId = InterviewQuestionDTO.legacyIdFor(questionIndex);
        }
        answeredAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public InterviewSessionEntity getSession() {
        return session;
    }
    
    public void setSession(InterviewSessionEntity session) {
        this.session = session;
    }
    
    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public Integer getTurnOrdinal() {
        return turnOrdinal;
    }

    public void setTurnOrdinal(Integer turnOrdinal) {
        this.turnOrdinal = turnOrdinal;
    }

    public String getDecidedAction() {
        return decidedAction;
    }

    public void setDecidedAction(String decidedAction) {
        this.decidedAction = decidedAction;
    }

    public Integer getQuestionIndex() {
        return questionIndex;
    }
    
    public void setQuestionIndex(Integer questionIndex) {
        this.questionIndex = questionIndex;
    }
    
    public String getQuestion() {
        return question;
    }
    
    public void setQuestion(String question) {
        this.question = question;
    }
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    public String getUserAnswer() {
        return userAnswer;
    }
    
    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
    }
    
    public Integer getScore() {
        return score;
    }
    
    public void setScore(Integer score) {
        this.score = score;
    }
    
    public String getFeedback() {
        return feedback;
    }
    
    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }
    
    public String getReferenceAnswer() {
        return referenceAnswer;
    }
    
    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }
    
    public String getKeyPointsJson() {
        return keyPointsJson;
    }
    
    public void setKeyPointsJson(String keyPointsJson) {
        this.keyPointsJson = keyPointsJson;
    }
    
    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }
    
    public void setAnsweredAt(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }

    public AnswerState getAnswerState() {
        return answerState;
    }

    public void setAnswerState(AnswerState answerState) {
        this.answerState = answerState;
    }
}
