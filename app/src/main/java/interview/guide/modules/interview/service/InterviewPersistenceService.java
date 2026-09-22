package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewTurnRequestEntity;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.repository.InterviewTurnRequestRepository;
import interview.guide.modules.profile.service.SkillProfileAggregator;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final InterviewTurnRequestRepository turnRequestRepository;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;
    private final SkillProfileAggregator profileAggregator;
    /** 删除会话时必须同步失效 Redis 缓存，否则缓存残留形成「幽灵会话」（DB 无、UI 可进） */
    private final InterviewSessionCache sessionCache;
    
    /**
     * 保存新的面试会话（支持可选简历）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, null, false, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              String sourceType,
                                              Long knowledgeBaseId,
                                              String interviewCategory) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, sourceType, knowledgeBaseId, interviewCategory, null, false, null);
    }

    /** P4-3：带 adaptive 标记保存（自适应面试会话） */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              boolean adaptive) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, null, adaptive, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, requestId, false, null);
    }

    /** P4-3：带 adaptive 标记的幂等保存 */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId,
                                                        boolean adaptive) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, requestId, adaptive, null);
    }

    /** P4Q-1：带简历上下文快照的保存（普通面试创建链路） */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              boolean adaptive,
                                              InterviewResumeContext resumeContext) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, null, adaptive, resumeContext);
    }

    /** P4Q-1：带简历上下文快照的幂等保存 */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId,
                                                        boolean adaptive,
                                                        InterviewResumeContext resumeContext) {
        return saveIdempotentSession(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, requestId, adaptive, resumeContext, null);
    }

    /**
     * P4Q-2：带面试计划的幂等保存——创建链路的标准入口。
     *
     * <p>计划（时长 + 必要覆盖）与首题展示时刻在这里一起写入：时间记账的起点是
     * 「第一题摆到用户面前」的那一刻，不是会话创建那一刻。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId,
                                                        boolean adaptive,
                                                        InterviewResumeContext resumeContext,
                                                        InterviewPlan plan) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, requestId, adaptive, resumeContext, plan);
    }

    /** P4Q-2：带面试计划的保存（非幂等链路，如知识库面试） */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              boolean adaptive,
                                              InterviewResumeContext resumeContext,
                                              InterviewPlan plan) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, null, adaptive, resumeContext, plan);
    }

    private InterviewSessionEntity saveSessionInternal(String sessionId, Long resumeId,
                                                       int totalQuestions,
                                                       List<InterviewQuestionDTO> questions,
                                                       String llmProvider,
                                                       String skillId,
                                                       String difficulty,
                                                       String sourceType,
                                                       Long knowledgeBaseId,
                                                       String interviewCategory,
                                                       String requestId,
                                                       boolean adaptive,
                                                       InterviewResumeContext resumeContext) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, sourceType, knowledgeBaseId, interviewCategory, requestId,
            adaptive, resumeContext, null);
    }

    private InterviewSessionEntity saveSessionInternal(String sessionId, Long resumeId,
                                                       int totalQuestions,
                                                       List<InterviewQuestionDTO> questions,
                                                       String llmProvider,
                                                       String skillId,
                                                       String difficulty,
                                                       String sourceType,
                                                       Long knowledgeBaseId,
                                                       String interviewCategory,
                                                       String requestId,
                                                       boolean adaptive,
                                                       InterviewResumeContext resumeContext,
                                                       InterviewPlan plan) {
        try {
            InterviewSessionEntity session = new InterviewSessionEntity();
            session.setSessionId(sessionId);
            session.setRequestId(requestId);
            session.setTotalQuestions(totalQuestions);
            session.setCurrentQuestionIndex(0);
            session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
            session.setQuestionsJson(objectMapper.writeValueAsString(questions));
            // P4-1：新会话的当前题标识 = 首个候选；没有候选的会话无从推进（保持 null）
            if (questions != null && !questions.isEmpty()) {
                session.setCurrentQuestionId(questions.get(0).questionId());
            }
            // P4Q-2：计划（时长 + 必要覆盖）与首题展示时刻一起写入——
            // 时间记账的起点是「第一题摆到用户面前」，不是会话创建那一刻
            if (plan != null) {
                session.setPlannedDurationMinutes(plan.plannedDurationMinutes());
                session.setRequiredTopicsJson(objectMapper.writeValueAsString(plan.requiredTopics()));
                // P5-2：重点分类一并落库，闭环审计才有据可依
                session.setFocusCategoriesJson(
                    objectMapper.writeValueAsString(plan.focusCategories()));
            }
            if (questions != null && !questions.isEmpty()) {
                session.setQuestionPresentedAt(LocalDateTime.now());
            }
            session.setLlmProvider(llmProvider != null ? llmProvider : "default");
            session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
            session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
            session.setSourceType(sourceType != null ? sourceType : "NORMAL");
            session.setKnowledgeBaseId(knowledgeBaseId);
            session.setInterviewCategory(interviewCategory);
            session.setAdaptive(adaptive);
            // P4Q-1：简历上下文快照——报告与复盘要能说清「这场基于哪份简历的哪个版本」
            if (resumeContext != null) {
                session.setResumeSource(resumeContext.source().name());
                session.setResumeVersion(resumeContext.version());
                session.setResumeContextText(resumeContext.text());
            }

            // 简历可选：有 resumeId 则关联简历
            if (resumeId != null) {
                Optional<ResumeEntity> resumeOpt = resumeRepository.findById(resumeId);
                resumeOpt.ifPresent(session::setResume);
            }

            InterviewSessionEntity saved = sessionRepository.save(session);
            log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}, sourceType={}",
                sessionId, skillId, resumeId, session.getSourceType());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化问题列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
        }
    }
    
    /**
     * 更新会话状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                status == InterviewSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    /**
     * 更新评估状态
     *
     * <p>P4Q-4：评估任务完成时报告已经落库（saveReport 已把会话置为 EVALUATED），
     * 因此顺手把缓存里的会话状态同步过去——否则缓存会长期停留在 COMPLETED，
     * 前端只能显示「评估中」。缓存写失败不影响数据库结果，读取侧还有一次自愈兜底。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }

        if (status == AsyncTaskStatus.COMPLETED) {
            try {
                sessionCache.updateSessionStatus(
                    sessionId, InterviewSessionDTO.SessionStatus.EVALUATED);
            } catch (Exception e) {
                log.warn("评估完成后同步会话缓存状态失败（读取侧会自愈）: sessionId={}, error={}",
                    sessionId, e.getMessage());
            }
        }
    }
    
    /**
     * 更新当前问题索引
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }

    // ==================== 逐轮推进（P4-9a） ====================

    /** 会话可以进行逐轮推进的状态；以此作为条件更新的一部分，已结束的会话不可能被写回进行中 */
    private static final List<InterviewSessionEntity.SessionStatus> ACTIVE_STATUSES =
        List.of(InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS);

    /**
     * 查已处理过的逐轮请求（重放时返回原结果）。
     */
    public Optional<InterviewTurnRequestEntity> findTurnRequest(String sessionId, String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return turnRequestRepository.findBySessionIdAndRequestId(sessionId, requestId);
    }

    /**
     * 一次性提交一轮推进：会话（版本 / 当前题标识 / 状态 / 结束原因 / 评估请求）
     * + 答案事实 + 幂等记录。
     *
     * <p>**这是逐轮提交唯一的写入口，模型调用必须已经在事务之外完成。** 三个写入同生共死：
     * 条件更新影响 0 行（版本过期 / 该题已不是待答题 / 会话已结束）→ 抛
     * {@link ErrorCode#INTERVIEW_TURN_STALE}，事务回滚，不会留下「答案写了但当前题没动」这类半状态。
     *
     * <p>P4-1：闸门用题目标识（不是数组下标），并且把**本轮最终决定**与结束原因一起记下来，
     * 让「候选耗尽」与「用户结束」在数据上可区分。
     *
     * @return 推进后的会话版本与评估代次
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewTurnResult applyTurn(InterviewTurnCommit commit) {
        String sessionId = commit.sessionId();
        LocalDateTime completedAt = commit.completing() ? LocalDateTime.now() : null;

        // 三种推进方式同一套闸门（版本 + 当前题标识 + 进行中状态），区别只在「是否换当前题」：
        // 提前交卷不动当前题，作答/跳过要移到下一题（最后一轮同时请求评估）
        int updated;
        // P4Q-2：时间记账与推进同一次条件更新落地（否则「版本推进了但耗时没记」）；
        // 有下一题才刷新展示时刻，收束后不再有「当前题」
        LocalDateTime presentedAt = commit.newQuestionId() != null ? LocalDateTime.now() : null;
        if (commit.finishing()) {
            updated = sessionRepository.applyFinish(
                sessionId, commit.expectedVersion(),
                InterviewSessionEntity.SessionStatus.COMPLETED, AsyncTaskStatus.PENDING,
                InterviewSessionEntity.END_USER_FINISHED, commit.answerSeconds(),
                completedAt, ACTIVE_STATUSES);
        } else if (commit.completing()) {
            updated = sessionRepository.applyTurnRequestingEvaluation(
                sessionId, commit.expectedVersion(), commit.expectedQuestionId(), commit.newQuestionId(),
                commit.newIndex(), InterviewSessionEntity.SessionStatus.COMPLETED,
                AsyncTaskStatus.PENDING, commit.decidedEndReason(), commit.answerSeconds(),
                completedAt, ACTIVE_STATUSES);
        } else {
            updated = sessionRepository.applyTurn(
                sessionId, commit.expectedVersion(), commit.expectedQuestionId(), commit.newQuestionId(),
                commit.newIndex(), InterviewSessionEntity.SessionStatus.IN_PROGRESS,
                commit.answerSeconds(), presentedAt, completedAt, ACTIVE_STATUSES);
        }

        if (updated == 0) {
            // 版本 / 当前题 / 会话状态三者任一不匹配都在这里收敛成同一个可见结果：
            // 调用方已经拿到权威状态，直接告诉用户「刷新后重试」，绝不默默再推进一次
            log.warn("逐轮提交被拒绝（会话已被其他请求推进或已结束）: sessionId={}, action={}, expectedVersion={}, expectedQuestionId={}",
                sessionId, commit.action(), commit.expectedVersion(), commit.expectedQuestionId());
            throw new BusinessException(ErrorCode.INTERVIEW_TURN_STALE);
        }

        // 条件更新绕过持久化上下文，必须重新加载才能拿到新版本
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        // 先捕获推进后的版本与代次：后续写可能再次清空持久化上下文（P4-4b 追加候选）
        int resultVersion = session.getTurnVersion();
        long resultEpoch = session.getEvaluateEpoch() != null ? session.getEvaluateEpoch() : 0L;

        int writtenOrdinal = 0;
        if (commit.writesAnswer()) {
            // 序号在事务内分配：报告补写的未考察行不占号，并发下也不会重复
            writtenOrdinal = commit.turnOrdinal() != null
                ? commit.turnOrdinal()
                : answerRepository.findMaxTurnOrdinal(sessionId) + 1;
            upsertAnswer(session, new TurnAnswerWrite(
                commit.questionId(), commit.questionIndex(), writtenOrdinal, commit.decidedAction(),
                commit.question(), commit.category(), commit.answer(), null, null,
                commit.answerState(), commit.newQuestionId(), commit.decisionReason(),
                commit.transitionMessage()));
        }

        if (commit.requestId() != null) {
            InterviewTurnRequestEntity record = new InterviewTurnRequestEntity();
            record.setSessionId(sessionId);
            record.setRequestId(commit.requestId());
            record.setAction(commit.action());
            record.setPayloadHash(commit.payloadHash());
            record.setBaseVersion(commit.expectedVersion());
            record.setResultVersion(commit.expectedVersion() + 1);
            record.setResponseJson(commit.responseJson());
            turnRequestRepository.save(record);
        }

        // P4-4b：本轮接纳了受限生成时，在同一短事务内把新候选池写回（版本已由上面推进，据此命中）
        if (commit.newQuestionsJson() != null) {
            sessionRepository.persistGeneratedCandidates(
                sessionId, resultVersion, commit.newQuestionsJson(), commit.newCandidateVersion());
        }

        log.info("逐轮提交已落库: sessionId={}, action={}, question={}→{}, version={}, requestId={}",
            sessionId, commit.action(), commit.expectedQuestionId(), commit.newQuestionId(),
            resultVersion, commit.requestId());

        return new InterviewTurnResult(resultVersion, resultEpoch, writtenOrdinal);
    }

    /**
     * 一条实际轮次的写入内容（P4-1）。
     *
     * <p>把参数收成一个对象：轮次要写的东西比「答案」多——身份、发生顺序、本轮决定。
     * {@code turnOrdinal} 为 null 表示这不是轨迹上的轮次（例如报告补写的未考察项、暂存草稿）。
     */
    record TurnAnswerWrite(
        String questionId,
        Integer questionIndex,
        Integer turnOrdinal,
        String decidedAction,
        String question,
        String category,
        String userAnswer,
        Integer score,
        String feedback,
        InterviewAnswerEntity.AnswerState answerState,
        String decidedNextQuestionId,
        String decisionReason,
        String transitionMessage
    ) {
        /** 兼容报告回填、草稿等不参与逐轮决策的写入 */
        TurnAnswerWrite(String questionId, Integer questionIndex, Integer turnOrdinal,
                        String decidedAction, String question, String category,
                        String userAnswer, Integer score, String feedback,
                        InterviewAnswerEntity.AnswerState answerState) {
            this(questionId, questionIndex, turnOrdinal, decidedAction, question, category,
                userAnswer, score, feedback, answerState, null, null, null);
        }
    }

    /**
     * 重新请求评估（P4-9a）：把评估状态置回 PENDING 并把代次 +1。
     *
     * <p>代次递增是重试能生效的前提——否则消费端会认为「这条触发已经被处理过」而跳过；
     * 同时也让上一次尚未跑完的旧代次任务过期，不会写出第二份报告。
     *
     * @return 新的评估代次；会话不存在或尚未结束评估时返回空
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<Long> requestEvaluation(String sessionId) {
        int updated = sessionRepository.requestEvaluation(sessionId, AsyncTaskStatus.PENDING,
            List.of(InterviewSessionEntity.SessionStatus.COMPLETED,
                InterviewSessionEntity.SessionStatus.EVALUATED));
        if (updated == 0) {
            return Optional.empty();
        }
        return sessionRepository.findEvaluateEpoch(sessionId);
    }

    /**
     * 原子领取评估任务（P4-9a）。
     *
     * <p>只有「代次与消息一致」且「尚未完成」时才能领到：同一代次的重复投递只有一个会执行，
     * 用户重试产生的新代次不受影响。领取失败即代表这次触发应当被丢弃。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean claimEvaluation(String sessionId, long epoch) {
        return sessionRepository.claimEvaluation(sessionId, epoch,
            AsyncTaskStatus.PROCESSING, AsyncTaskStatus.COMPLETED) > 0;
    }

    /** 当前评估代次（消费端据此丢弃过期触发） */
    public Optional<Long> findEvaluateEpoch(String sessionId) {
        return sessionRepository.findEvaluateEpoch(sessionId);
    }

    /**
     * 保存面试答案（默认按「真实作答」写入，兼容旧调用点）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        return saveAnswer(sessionId, questionIndex, question, category, userAnswer, score, feedback,
            InterviewAnswerEntity.AnswerState.ANSWERED);
    }

    /**
     * 保存面试答案（P4Q-5：带答案状态）
     *
     * <p>状态决定这条答案是否进入评分与画像证据：跳跃/未作答/明确不会只作为事实记录，
     * 不参与技术评分。已存在的行按状态覆盖（重新作答 / 状态修正场景）。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, Integer score, String feedback,
                                            InterviewAnswerEntity.AnswerState answerState) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        return upsertAnswer(session, new TurnAnswerWrite(
            null, questionIndex, null, null, question, category, userAnswer, score, feedback,
            answerState));
    }

    /**
     * 写入 / 覆盖一条答案事实（调用方须已在事务内并持有会话实体）。
     *
     * <p>P4-1：优先按**题目标识**定位（身份），旧数据/暂存草稿没有标识时才退回按下标。
     * 已存在的行按状态覆盖（重新作答 / 状态修正场景）；{@code turnOrdinal} 与
     * {@code decidedAction} 只在写入方给出时才覆盖——报告回填不能把「未考察」洗成「问过」。
     */
    private InterviewAnswerEntity upsertAnswer(InterviewSessionEntity session, TurnAnswerWrite write) {
        String sessionId = session.getSessionId();
        String questionId = write.questionId() != null
            ? write.questionId()
            : InterviewQuestionDTO.legacyIdFor(write.questionIndex());
        InterviewAnswerEntity answer = answerRepository
            .findBySession_SessionIdAndQuestionId(sessionId, questionId)
            .orElseGet(() -> {
                InterviewAnswerEntity created = new InterviewAnswerEntity();
                created.setSession(session);
                return created;
            });

        answer.setQuestionId(questionId);
        if (write.questionIndex() != null) {
            answer.setQuestionIndex(write.questionIndex());
        }
        if (write.turnOrdinal() != null) {
            answer.setTurnOrdinal(write.turnOrdinal());
        }
        if (write.decidedAction() != null) {
            answer.setDecidedAction(write.decidedAction());
        }
        if (write.decidedNextQuestionId() != null) {
            answer.setDecidedNextQuestionId(write.decidedNextQuestionId());
        }
        if (write.decisionReason() != null) {
            answer.setDecisionReason(write.decisionReason());
        }
        if (write.transitionMessage() != null) {
            answer.setTransitionMessage(write.transitionMessage());
        }
        answer.setQuestion(write.question());
        answer.setCategory(write.category());
        answer.setUserAnswer(write.userAnswer());
        answer.setScore(write.score());
        answer.setFeedback(write.feedback());
        if (write.answerState() != null) {
            answer.setAnswerState(write.answerState());
        }

        InterviewAnswerEntity saved = answerRepository.save(answer);
        log.info("面试轮次已保存: sessionId={}, questionId={}, ordinal={}, score={}, state={}",
                sessionId, saved.getQuestionId(), saved.getTurnOrdinal(), write.score(),
                saved.getAnswerState());

        return saved;
    }
    
    /**
     * 暂存草稿（P4-1）。
     *
     * <p>草稿写进答案行但**不占发生顺序、不记为已作答**：它不属于面试轨迹
     * （{@code turnOrdinal} 为空），也不会被报告评分（{@code UNANSWERED} 不计分）。
     * 候选素材保持只读——这正是「素材与轨迹分离」的直接体现。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDraft(String sessionId, InterviewQuestionDTO question, String answer) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        upsertAnswer(session, new TurnAnswerWrite(
            question.questionId(), question.questionIndex(), null, null, question.question(),
            question.category(), answer, null, null,
            InterviewAnswerEntity.AnswerState.UNANSWERED));
    }

    /**
     * 调整时间预算（P4Q-2）：planned = 已用 + 用户声明的剩余。
     *
     * @return 调整后的计划时长；会话不存在或已结束返回 empty
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<Integer> updateBudget(String sessionId, int remainingMinutes) {
        List<InterviewSessionEntity.SessionStatus> active =
            List.of(InterviewSessionEntity.SessionStatus.CREATED,
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        int consumed = sessionRepository.findBySessionId(sessionId)
            .map(entity -> entity.getConsumedSeconds() != null ? entity.getConsumedSeconds() : 0)
            .orElse(0);
        int plannedMinutes = (int) Math.round(consumed / 60.0) + Math.max(1, remainingMinutes);
        int updated = sessionRepository.updateBudget(sessionId, plannedMinutes, active);
        return updated > 0 ? Optional.of(plannedMinutes) : Optional.empty();
    }

    /**
     * 显式难度调整（P4Q-3c）：只写本场难度偏好，不推进版本/当前题。
     *
     * @return true = 会话存在且仍在进行中并已更新；false = 会话不存在或已结束
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDifficultyPreference(String sessionId, String difficulty) {
        return sessionRepository.updateDifficultyPreference(sessionId, difficulty, ACTIVE_STATUSES) > 0;
    }

    /**
     * 后台预备候选回写（P4-4b）：乐观代次闸门，版本不符（用户已推进/结束）则不写。
     *
     * @return true = 代次一致且会话进行中，已回写；false = 结果过期，丢弃
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean appendBackgroundCandidates(String sessionId, long expectedVersion,
                                              String questionsJson, long newVersion) {
        return sessionRepository.appendBackgroundCandidates(
            sessionId, expectedVersion, questionsJson, newVersion, ACTIVE_STATUSES) > 0;
    }

    /**
     * 保存面试报告
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
            if (sessionOpt.isEmpty()) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            InterviewSessionEntity session = sessionOpt.get();
            session.setOverallScore(report.overallScore());
            session.setOverallFeedback(report.overallFeedback());
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            session.setReportJson(objectMapper.writeValueAsString(report));
            session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
            session.setCompletedAt(LocalDateTime.now());

            sessionRepository.save(session);

            // 查询已存在的轮次，按**题目标识**建索引（P4-1：身份不是下标）
            List<InterviewAnswerEntity> existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
            java.util.Map<String, InterviewAnswerEntity> answerById = existingAnswers.stream()
                .filter(answer -> answer.getQuestionId() != null)
                .collect(java.util.stream.Collectors.toMap(
                    InterviewAnswerEntity::getQuestionId,
                    a -> a,
                    (a1, a2) -> a1
                ));
            // 「报告序号（真实发生顺序）→ 题目标识」：报告按发生顺序编号（P4-1），
            // 因此对齐链是「序号 → 轨迹 → 标识」，与候选池顺序无关
            java.util.Map<Integer, String> questionIdByReportIndex = new java.util.HashMap<>();
            for (InterviewTurnDTO turn : findTurnsBySessionId(sessionId)) {
                // 报告沿用 0 起 questionIndex；轮次 ordinal 是 1 起发生顺序。
                questionIdByReportIndex.put(turn.displayOrdinal() - 1, turn.questionId());
            }

            // 建立参考答案索引
            java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewReportDTO.ReferenceAnswer::questionIndex,
                    r -> r,
                    (r1, r2) -> r1
                ));

            List<InterviewAnswerEntity> answersToSave = new java.util.ArrayList<>();

            // 遍历所有评估结果，更新或创建答案记录
            for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                String questionId = questionIdByReportIndex.get(eval.questionIndex());
                InterviewAnswerEntity answer = questionId != null
                    ? answerById.get(questionId)
                    : null;

                if (answer == null) {
                    // P4-1 后报告只评估真实轮次。对不上的条目不是「未考察」，而是报告索引异常；
                    // 不能凭空创建答案行，否则候选素材又会反向污染实际轨迹。
                    log.warn("报告条目无法对齐实际轮次，跳过回填: sessionId={}, reportIndex={}",
                        sessionId, eval.questionIndex());
                    continue;
                }

                // P4Q-5：跳过/未作答/明确不会不参与技术评分——报告不得把它们显示成 0 分，
                // 也不能让它们以 0 分进入画像证据（缺陷期间真实发生过）
                if (!answer.countsAsAnswer()) {
                    answersToSave.add(answer);
                    continue;
                }

                // 更新评分和反馈
                answer.setScore(eval.score());
                answer.setFeedback(eval.feedback());

                // 设置参考答案和关键点
                InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
                if (refAns != null) {
                    answer.setReferenceAnswer(refAns.referenceAnswer());
                    if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
                        answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
                    }
                }

                answersToSave.add(answer);
            }

            answerRepository.saveAll(answersToSave);
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
                sessionId, report.overallScore(), answersToSave.size());

        } catch (JacksonException e) {
            log.error("序列化报告失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存面试报告失败");
        }
    }

    /**
     * 读取已经落库的最终报告快照（P4-5）。
     *
     * <p>旧会话没有 report_json 时返回 empty，由上层按兼容路径重新生成一次并补齐快照。
     */
    @Transactional(readOnly = true)
    public Optional<InterviewReportDTO> findSavedReport(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
            .map(InterviewSessionEntity::getReportJson)
            .filter(json -> json != null && !json.isBlank())
            .flatMap(json -> {
                try {
                    return Optional.of(objectMapper.readValue(json, InterviewReportDTO.class));
                } catch (JacksonException e) {
                    log.warn("解析报告快照失败，按旧会话重新生成: sessionId={}, error={}",
                        sessionId, e.getMessage());
                    return Optional.empty();
                }
            });
    }
    
    /**
     * 会话的实际轨迹（P4-1）：只含**真实发生过**的轮次，按发生顺序。
     *
     * <p>报告补写的「未考察」行不在这里——这正是「未问候选不进入实际轨迹」的落点。
     */
    public List<InterviewTurnDTO> findTurnsBySessionId(String sessionId) {
        return answerRepository.findTurnsBySessionId(sessionId).stream()
            .map(this::toTurnDTO)
            .toList();
    }

    /** 实际轨迹的实体形态（按发生顺序）：历史详情、报告回填等需要实体字段的调用方用 */
    public List<InterviewAnswerEntity> findTurnEntitiesBySessionId(String sessionId) {
        return answerRepository.findTurnsBySessionId(sessionId);
    }

    private InterviewTurnDTO toTurnDTO(InterviewAnswerEntity answer) {
        return InterviewTurnDTO.from(answer, parseKeyPoints(answer.getKeyPointsJson()));
    }

    private List<String> parseKeyPoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JacksonException e) {
            return List.of();
        }
    }

    /**
     * 会话的候选素材（P4-1）：读取路径的公共入口，统一补齐缺失的题目标识。
     */
    public List<InterviewQuestionDTO> findCandidatesBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
            .map(this::parseCandidates)
            .map(InterviewQuestionIdentity::withDerivedIds)
            .orElse(List.of());
    }

    /** 会话的候选素材（questions_json）：解析失败时返回空列表，不让报告链路整体失败 */
    private List<InterviewQuestionDTO> parseCandidates(InterviewSessionEntity session) {
        String json = session.getQuestionsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<InterviewQuestionDTO>>() {});
        } catch (JacksonException e) {
            log.warn("解析候选素材失败，报告按空池处理: sessionId={}, error={}",
                session.getSessionId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据会话ID获取会话
     */
    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    public Optional<InterviewSessionEntity> findByRequestId(String requestId) {
        return sessionRepository.findByRequestId(requestId);
    }
    
    /**
     * 获取简历的所有面试记录
     */
    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    /**
     * 获取所有面试记录（按创建时间倒序）
     */
    public List<InterviewSessionEntity> findAll() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }
    
    /**
     * 删除简历的所有面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        if (!sessions.isEmpty()) {
            // 先级联清理技能画像证据（删库后无法定位来源），再删会话
            profileAggregator.removeInterviewSessionEvidence(
                sessions.stream().map(InterviewSessionEntity::getSessionId).toList());
            sessionRepository.deleteAll(sessions);
            // 同步失效 Redis 缓存，防「幽灵会话」残留
            sessions.forEach(session -> sessionCache.deleteSession(session.getSessionId()));
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }

    /**
     * 删除单个面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            // 级联清理该会话的技能画像证据并重聚合受影响技能
            profileAggregator.removeInterviewSessionEvidence(sessionId);
            sessionRepository.delete(sessionOpt.get());
            // 同步失效 Redis 缓存，防「幽灵会话」残留
            sessionCache.deleteSession(sessionId);
            log.info("已删除面试会话: sessionId={}", sessionId);
        } else {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
    }
    
    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     */
    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
            InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        );
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses);
    }
    
    /**
     * 根据会话ID查找所有答案
     */
    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    private static final int MAX_HISTORICAL_QUESTIONS = 60;

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     */
    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        List<InterviewSessionEntity> sessions;
        if (resumeId != null) {
            sessions = sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId);
        } else {
            sessions = sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId);
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<HistoricalQuestion> result = sessions.stream()
            .map(InterviewSessionEntity::getQuestionsJson)
            .filter(json -> json != null && !json.isEmpty())
            .flatMap(json -> {
                try {
                    List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
                        new TypeReference<List<InterviewQuestionDTO>>() {});
                    return questions.stream()
                        .filter(q -> !q.isFollowUp())
                        .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
                } catch (Exception e) {
                    log.error("解析历史问题JSON失败", e);
                    return java.util.stream.Stream.<HistoricalQuestion>empty();
                }
            })
            .filter(hq -> seen.add(hq.question()))
            .limit(MAX_HISTORICAL_QUESTIONS)
            .toList();

        log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
            result.stream().collect(java.util.stream.Collectors.groupingBy(
                hq -> hq.type() != null ? hq.type() : "GENERAL",
                java.util.stream.Collectors.counting())));

        return result;
    }
}
