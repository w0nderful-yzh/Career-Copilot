package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试会话实体
 */
@Entity
@Table(name = "interview_sessions", indexes = {
    @Index(name = "idx_interview_session_resume_created", columnList = "resume_id,created_at"),
    @Index(name = "idx_interview_session_resume_status_created", columnList = "resume_id,status,created_at"),
    @Index(name = "idx_interview_session_skill_created", columnList = "skillId,createdAt")
})
public class InterviewSessionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 会话ID (UUID)
    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;

    // 创建请求幂等键（仅文本面试创建链路使用）
    @Column(name = "request_id", unique = true, length = 64)
    private String requestId;
    
    // 面试主题
    @Column(length = 64)
    private String skillId = "java-backend";

    // 难度级别 (junior / mid / senior)
    @Column(length = 16)
    private String difficulty = "mid";

    // 简历ID（直接映射FK列，避免LAZY加载触发额外查询）
    @Column(name = "resume_id", insertable = false, updatable = false)
    private Long resumeId;

    // 关联的简历（可选，支持无简历通用面试）
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id")
    private ResumeEntity resume;
    
    // 问题总数
    private Integer totalQuestions;
    
    // 当前问题索引
    private Integer currentQuestionIndex = 0;
    
    // 会话状态
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.CREATED;
    
    // 问题列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String questionsJson;
    
    // 总分 (0-100)
    private Integer overallScore;
    
    // 总体评价
    @Column(columnDefinition = "TEXT")
    private String overallFeedback;
    
    // 优势 (JSON)
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;
    
    // 改进建议 (JSON)
    @Column(columnDefinition = "TEXT")
    private String improvementsJson;
    
    // 参考答案 (JSON)
    @Column(columnDefinition = "TEXT")
    private String referenceAnswersJson;

    /**
     * P4-5 完整报告快照。
     *
     * <p>报告含规则版本、真实轮次、覆盖与聚合输入；读取时返回同一快照，不再次调用模型，
     * 从而保证展示、导出与后续复盘基于同一份事实。
     */
    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;
    
    // 面试答案记录
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InterviewAnswerEntity> answers = new ArrayList<>();
    
    // 创建时间
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    // 完成时间
    private LocalDateTime completedAt;

    // 评估状态（异步评估）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus evaluateStatus;

    // 评估错误信息
    @Column(length = 500)
    private String evaluateError;

    // LLM提供商
    @Column(length = 50)
    private String llmProvider = "dashscope";

    // 会话来源：NORMAL / KNOWLEDGE_BASE
    @Column(length = 32)
    private String sourceType = "NORMAL";

    // 知识库面试来源知识库 ID
    private Long knowledgeBaseId;

    // 知识库面试方向（来自题库 category，普通面试为 null）
    @Column(length = 64)
    private String interviewCategory;

    // 是否自适应面试（P4-3：逐题轻量评估 + 决策选下一题；普通/知识库面试保持顺序题单）
    @Column(nullable = false)
    private Boolean adaptive = false;

    // 简历来源（P4Q-1）：RESUME_VERSION / RESUME_TEXT / EXPLICIT_TEXT / NONE
    @Column(name = "resume_source", length = 24)
    private String resumeSource;

    // 出题使用的简历版本号（来源为 RESUME_VERSION 时有值）
    @Column(name = "resume_version")
    private Integer resumeVersion;

    // 出题实际使用的简历上下文文本快照（简历后续被修改/删除也能追溯当时依据）
    @Column(name = "resume_context_text", columnDefinition = "TEXT")
    private String resumeContextText;

    // 会话推进版本（P4-9a）：作答 / 跳过 / 结束各 +1，逐轮提交用它做乐观并发控制。
    // 只由「推进会话」的路径递增，报告回填与评估状态更新不动它——否则异步任务会把
    // 用户正在进行的提交判成过期。
    @Column(name = "turn_version", nullable = false)
    private Integer turnVersion = 0;

    // 评估任务代次（P4-9a）：每次请求评估 +1，随 Stream 消息投递，消费端据此丢弃过期触发
    @Column(name = "evaluate_epoch", nullable = false)
    private Long evaluateEpoch = 0L;

    /**
     * 结束原因（P4-1）：候选素材耗尽与用户主动结束必须能分开表达。
     *
     * <p>此前结束只有 status=COMPLETED 一种说法，「候选问完了」「用户不想聊了」「预算到了」
     * 在数据上无法区分，报告与复盘也就说不出「为什么这场结束了」。覆盖与预算原因由 P4Q-2 扩展。
     */
    @Column(name = "end_reason", length = 32)
    private String endReason;

    /** 候选素材已耗尽（不是「考察完成」——覆盖是否充分是另一件事） */
    public static final String END_CANDIDATES_EXHAUSTED = "CANDIDATES_EXHAUSTED";
    /** 用户主动结束（提前交卷 / 自然语言要求结束） */
    public static final String END_USER_FINISHED = "USER_FINISHED";
    /** 必要覆盖已完成（P4Q-2）：与「候选耗尽」分开——前者是目标达成，后者是素材用完 */
    public static final String END_COVERAGE_SATISFIED = "COVERAGE_SATISFIED";
    /** 时间预算用尽（P4Q-2）：只统计用户答题时间，模型等待不算 */
    public static final String END_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    /**
     * 预计时长（分钟，P4Q-2）：规模与收束判据都由它推导；NULL 表示旧会话未记录计划。
     */
    @Column(name = "planned_duration_minutes")
    private Integer plannedDurationMinutes;

    /**
     * 必要覆盖的话题列表（P4Q-2，JSON 数组）。
     *
     * <p>覆盖**状态**不落库：候选池与实际轨迹足以还原「每个话题问没问、怎么答的」，
     * 再存一份快照只会与轨迹漂移。这里只存「计划要求覆盖哪些话题」。
     */
    @Column(name = "required_topics_json", columnDefinition = "TEXT")
    private String requiredTopicsJson;

    /**
     * 用户答题累计耗时（秒，P4Q-2）。
     *
     * <p>只记「题目展示 → 本轮提交」的墙钟并扣除本轮模型评估耗时——
     * 模型/网络等待与暂停都不扣用户预算，否则「模型慢」会变成「用户超时」。
     */
    @Column(name = "consumed_seconds", nullable = false)
    private Integer consumedSeconds = 0;

    /**
     * 当前题展示时刻（P4Q-2）：时间记账起点，提交时结算本轮耗时。
     */
    @Column(name = "question_presented_at")
    private LocalDateTime questionPresentedAt;

    /**
     * 当前待答题的稳定标识（P4-1）。
     *
     * <p>推进闸门与「当前题」定位都基于它；{@link #currentQuestionIndex} 退化为展示顺序
     * 与旧数据兼容。旧会话由迁移按 {@code legacy-<index>} 回填。
     */
    @Column(name = "current_question_id", length = 64)
    private String currentQuestionId;

    /**
     * 本场难度偏好（P4Q-3c）：显式节奏指令当轮生效，只影响本场后续选题与生成的难度基线，
     * 不写长期画像。junior/mid/senior；NULL 表示未调整。
     */
    @Column(name = "difficulty_preference", length = 16)
    private String difficultyPreference;

    /**
     * 候选代次（P4-4b）：受限生成与后台预备候选都带上当时的代次；
     * 后台异步预备回写前比对代次，晚到/过期的结果不驱动状态。
     */
    @Column(name = "candidate_version", nullable = false)
    private Integer candidateVersion = 0;

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public Long getResumeId() {
        return resumeId;
    }

    public ResumeEntity getResume() {
        return resume;
    }

    public void setResume(ResumeEntity resume) {
        this.resume = resume;
    }
    
    public Integer getTotalQuestions() {
        return totalQuestions;
    }
    
    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }
    
    public Integer getCurrentQuestionIndex() {
        return currentQuestionIndex;
    }
    
    public void setCurrentQuestionIndex(Integer currentQuestionIndex) {
        this.currentQuestionIndex = currentQuestionIndex;
    }
    
    public SessionStatus getStatus() {
        return status;
    }
    
    public void setStatus(SessionStatus status) {
        this.status = status;
    }
    
    public String getQuestionsJson() {
        return questionsJson;
    }
    
    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }
    
    public Integer getOverallScore() {
        return overallScore;
    }
    
    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }
    
    public String getOverallFeedback() {
        return overallFeedback;
    }
    
    public void setOverallFeedback(String overallFeedback) {
        this.overallFeedback = overallFeedback;
    }
    
    public String getStrengthsJson() {
        return strengthsJson;
    }
    
    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }
    
    public String getImprovementsJson() {
        return improvementsJson;
    }
    
    public void setImprovementsJson(String improvementsJson) {
        this.improvementsJson = improvementsJson;
    }
    
    public String getReferenceAnswersJson() {
        return referenceAnswersJson;
    }
    
    public void setReferenceAnswersJson(String referenceAnswersJson) {
        this.referenceAnswersJson = referenceAnswersJson;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }
    
    public List<InterviewAnswerEntity> getAnswers() {
        return answers;
    }
    
    public void setAnswers(List<InterviewAnswerEntity> answers) {
        this.answers = answers;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public AsyncTaskStatus getEvaluateStatus() {
        return evaluateStatus;
    }

    public void setEvaluateStatus(AsyncTaskStatus evaluateStatus) {
        this.evaluateStatus = evaluateStatus;
    }

    public String getEvaluateError() {
        return evaluateError;
    }

    public void setEvaluateError(String evaluateError) {
        this.evaluateError = evaluateError;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getInterviewCategory() {
        return interviewCategory;
    }

    public void setInterviewCategory(String interviewCategory) {
        this.interviewCategory = interviewCategory;
    }

    public Boolean getAdaptive() {
        return adaptive;
    }

    public void setAdaptive(Boolean adaptive) {
        this.adaptive = adaptive;
    }

    public String getResumeSource() {
        return resumeSource;
    }

    public void setResumeSource(String resumeSource) {
        this.resumeSource = resumeSource;
    }

    public Integer getResumeVersion() {
        return resumeVersion;
    }

    public void setResumeVersion(Integer resumeVersion) {
        this.resumeVersion = resumeVersion;
    }

    public String getResumeContextText() {
        return resumeContextText;
    }

    public void setResumeContextText(String resumeContextText) {
        this.resumeContextText = resumeContextText;
    }

    public Integer getTurnVersion() {
        return turnVersion;
    }

    public void setTurnVersion(Integer turnVersion) {
        this.turnVersion = turnVersion;
    }

    public Long getEvaluateEpoch() {
        return evaluateEpoch;
    }

    public void setEvaluateEpoch(Long evaluateEpoch) {
        this.evaluateEpoch = evaluateEpoch;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }

    public Integer getPlannedDurationMinutes() {
        return plannedDurationMinutes;
    }

    public void setPlannedDurationMinutes(Integer plannedDurationMinutes) {
        this.plannedDurationMinutes = plannedDurationMinutes;
    }

    public String getRequiredTopicsJson() {
        return requiredTopicsJson;
    }

    public void setRequiredTopicsJson(String requiredTopicsJson) {
        this.requiredTopicsJson = requiredTopicsJson;
    }

    public Integer getConsumedSeconds() {
        return consumedSeconds;
    }

    public void setConsumedSeconds(Integer consumedSeconds) {
        this.consumedSeconds = consumedSeconds != null ? consumedSeconds : 0;
    }

    public LocalDateTime getQuestionPresentedAt() {
        return questionPresentedAt;
    }

    public void setQuestionPresentedAt(LocalDateTime questionPresentedAt) {
        this.questionPresentedAt = questionPresentedAt;
    }

    public String getCurrentQuestionId() {
        return currentQuestionId;
    }

    public void setCurrentQuestionId(String currentQuestionId) {
        this.currentQuestionId = currentQuestionId;
    }

    public String getDifficultyPreference() {
        return difficultyPreference;
    }

    public void setDifficultyPreference(String difficultyPreference) {
        this.difficultyPreference = difficultyPreference;
    }

    public Integer getCandidateVersion() {
        return candidateVersion;
    }

    public void setCandidateVersion(Integer candidateVersion) {
        this.candidateVersion = candidateVersion != null ? candidateVersion : 0;
    }

    public void addAnswer(InterviewAnswerEntity answer) {
        answers.add(answer);
        answer.setSession(this);
    }
}
