package interview.guide.modules.interview.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 面试答案实体
 */
@Entity
@Table(name = "interview_answers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_interview_answer_session_question", columnNames = {"session_id", "question_index"})
    },
    indexes = {
        @Index(name = "idx_interview_answer_session_question", columnList = "session_id,question_index")
    })
public class InterviewAnswerEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 关联的会话
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSessionEntity session;
    
    // 问题索引
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
     * 未考察不落库：没有答案行即为未考察。
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
