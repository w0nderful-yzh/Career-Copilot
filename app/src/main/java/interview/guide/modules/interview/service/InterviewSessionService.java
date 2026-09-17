package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.policy.AdaptiveInterviewPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private static final String CREATE_LOCK_PREFIX = "interview:create:";
    private static final String CREATE_RESULT_PREFIX = "interview:create:result:";
    private static final Duration CREATE_RESULT_TTL = Duration.ofDays(1);

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final RedisService redisService;
    private final TurnEvaluationService turnEvaluationService;
    private final InterviewResumeContextResolver resumeContextResolver;

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        String requestId = normalizeRequestId(request.requestId());
        if (requestId == null) {
            return createSessionInternal(request);
        }

        return redisService.executeWithLock(
            CREATE_LOCK_PREFIX + requestId,
            185,
            600,
            TimeUnit.SECONDS,
            () -> createIdempotentSession(request, requestId)
        );
    }

    private InterviewSessionDTO createIdempotentSession(CreateInterviewRequest request, String requestId) {
        String resultKey = CREATE_RESULT_PREFIX + requestId;
        String cachedSessionId = redisService.get(resultKey);
        if (cachedSessionId != null) {
            log.info("复用缓存中的幂等创建请求: requestId={}, sessionId={}", requestId, cachedSessionId);
            return getSession(cachedSessionId);
        }

        Optional<InterviewSessionEntity> existing = persistenceService.findByRequestId(requestId);
        if (existing.isPresent()) {
            String existingSessionId = existing.get().getSessionId();
            log.info("从数据库恢复幂等创建请求: requestId={}, sessionId={}",
                requestId, existingSessionId);
            redisService.set(resultKey, existingSessionId, CREATE_RESULT_TTL);
            return getSession(existingSessionId);
        }

        InterviewSessionDTO created = createSessionInternal(request, requestId);
        redisService.set(resultKey, created.sessionId(), CREATE_RESULT_TTL);
        return created;
    }

    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request) {
        return createSessionInternal(request, null);
    }

    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request, String requestId) {
        boolean adaptive = Boolean.TRUE.equals(request.adaptive());
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        List<HistoricalQuestion> historicalQuestions =
            persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // P4Q-1：简历上下文由 Java 统一解析（明确指定版本 → ACTIVE 结构化版本 → 原文 → 无），
        // 出题与快照用同一份文本。调用方传的 resumeText 只在**没有 resumeId** 时作为显式文本通道生效——
        // 此前 Java 只认调用方传的文本，而 Agent 侧恒传 null，简历题分支从未生效。
        InterviewResumeContext resumeContext = resumeContextResolver.resolve(
            request.resumeId(), null, request.resumeText());

        // 基于 Skill 生成面试问题（focusCategories 为提案依据画像给出的重点考察方向）
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            request.llmProvider(),
            skillId,
            difficulty,
            resumeContext.text(),
            request.questionCount(),
            historicalQuestions,
            request.customCategories(),
            request.jdText(),
            request.focusCategories()
        );

        if (requestId != null) {
            try {
                persistenceService.saveIdempotentSession(
                    sessionId,
                    request.resumeId(),
                    questions.size(),
                    questions,
                    request.llmProvider(),
                    skillId,
                    difficulty,
                    requestId,
                    adaptive,
                    resumeContext
                );
            } catch (Exception e) {
                Optional<InterviewSessionEntity> concurrentlyCreated =
                    persistenceService.findByRequestId(requestId);
                if (concurrentlyCreated.isPresent()) {
                    return getSession(concurrentlyCreated.get().getSessionId());
                }
                log.error("持久化幂等面试会话失败: requestId={}", requestId, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建面试会话失败，请重试");
            }
        } else {
            try {
                persistenceService.saveSession(sessionId, request.resumeId(),
                    questions.size(), questions, request.llmProvider(), skillId, difficulty,
                    adaptive, resumeContext);
            } catch (Exception e) {
                log.warn("保存面试会话到数据库失败: {}", e.getMessage());
            }
        }

        // 幂等请求必须先成功落库，再写入易失缓存，保证进程异常后可从数据库恢复。
        // 缓存里存「出题实际依据的文本」而非调用方入参：读取路径（DTO / 恢复）看到的应与出题一致
        String contextText = resumeContext.text() != null ? resumeContext.text() : "";
        sessionCache.saveSession(
            sessionId,
            contextText,
            request.resumeId(),
            null,
            null,
            questions,
            0,
            SessionStatus.CREATED,
            adaptive,
            resumeContext.source().name(),
            resumeContext.version()
        );

        return new InterviewSessionDTO(
            sessionId,
            contextText,
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED,
            null,
            null,
            adaptive,
            null,
            null,
            resumeContext.source().name(),
            resumeContext.version()
        );
    }

    public InterviewSessionDTO createSessionFromQuestions(List<InterviewQuestionDTO> questions,
                                                          String llmProvider,
                                                          String skillId,
                                                          String difficulty,
                                                          Long knowledgeBaseId,
                                                          String interviewCategory) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "面试题目不能为空");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        persistenceService.saveSession(
            sessionId, null, questions.size(), questions, llmProvider, skillId, difficulty,
            "KNOWLEDGE_BASE", knowledgeBaseId, interviewCategory);
        sessionCache.saveSession(sessionId, "", null, knowledgeBaseId, interviewCategory,
            questions, 0, SessionStatus.CREATED);

        return new InterviewSessionDTO(
            sessionId,
            "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED,
            knowledgeBaseId,
            interviewCategory
        );
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String normalized = requestId.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId 格式不正确");
        }
        return normalized;
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            CachedSession cached = cachedOpt.get();
            // 面试已完成 → 报告由异步任务生成，缓存状态可能落后于数据库。
            // 这是唯一会漂移的窗口（进行中会话的状态与问题都由同一写入方同步更新），
            // 故只在此处回源一次数据库；其余读取路径完全走缓存，不额外打库。
            if (cached.getStatus() == SessionStatus.COMPLETED) {
                return syncReportStateFromDatabase(sessionId, cached);
            }
            return toDTO(cached);
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return withReportState(toDTO(restoredSession), sessionId);
    }

    /**
     * 用数据库校正「已完成」会话的缓存状态（P4Q-4）。
     *
     * <p>异步评估链路只写数据库，缓存里的 COMPLETED 不会自己变成 EVALUATED；不校正的话
     * 前端会一直显示「评估中」（缓存 TTL 24 小时）。这里把数据库当权威做一次读取自愈，
     * 而不是把 DB + Redis 双写当成原子操作。
     */
    private InterviewSessionDTO syncReportStateFromDatabase(String sessionId, CachedSession cached) {
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isEmpty()) {
            return toDTO(cached);
        }
        InterviewSessionEntity entity = entityOpt.get();
        SessionStatus authoritative = convertStatus(entity.getStatus());
        if (authoritative != cached.getStatus()) {
            log.info("会话状态与数据库不一致，按数据库自愈: sessionId={}, cache={}, db={}",
                sessionId, cached.getStatus(), authoritative);
            sessionCache.updateSessionStatus(sessionId, authoritative);
            cached.setStatus(authoritative);
        }
        return toDTO(cached, entity.getEvaluateStatus(), entity.getEvaluateError());
    }

    /** 补齐评估状态字段（缓存未命中路径：已经读过数据库，顺手带上） */
    private InterviewSessionDTO withReportState(InterviewSessionDTO dto, String sessionId) {
        return persistenceService.findBySessionId(sessionId)
            .map(entity -> new InterviewSessionDTO(
                dto.sessionId(), dto.resumeText(), dto.totalQuestions(), dto.currentQuestionIndex(),
                dto.questions(), dto.status(), dto.knowledgeBaseId(), dto.interviewCategory(),
                dto.adaptive(), entity.getEvaluateStatus(), entity.getEvaluateError(),
                dto.resumeSource(), dto.resumeVersion()))
            .orElse(dto);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 恢复已保存的答案
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    // 带上作答状态：跳过时答案为空，只靠答案文本会把已跳过的题从轨迹里丢掉
                    questions.set(index, question.withAnswer(answer.getUserAnswer())
                        .withAnswerState(answer.getAnswerState()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 保存到 Redis 缓存（DB 是 adaptive 权威，恢复时落缓存保持逐题评估语义）
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                entity.getKnowledgeBaseId(),
                entity.getInterviewCategory(),
                questions,
                entity.getCurrentQuestionIndex(),
                status,
                Boolean.TRUE.equals(entity.getAdaptive())
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        return recordTurn(request.sessionId(), request.questionIndex(), request.answer(), null);
    }

    /**
     * 跳过当前题（P4Q-5 一等动作）。
     *
     * <p>跳过的语义：**不调模型、不追问、不计分、不产生画像证据**。它和「答错」是两件事——
     * 答错是有作答内容但质量差（正常计分），跳过是没有作答（只记录发生过）。
     * 自适应会话按「答不上来」处理：中断当前追问组，切下一主问题。
     */
    public SubmitAnswerResponse skipQuestion(String sessionId, int questionIndex) {
        return recordTurn(sessionId, questionIndex, null,
            InterviewAnswerEntity.AnswerState.SKIPPED);
    }

    /**
     * 记录一轮（作答或跳过）并推进到下一题。
     *
     * @param answer      作答内容；跳过时为 null
     * @param forcedState 调用方已确定的状态（如显式跳过）；null 表示按作答内容判定
     */
    private SubmitAnswerResponse recordTurn(String sessionId, int index, String answer,
                                            InterviewAnswerEntity.AnswerState forcedState) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        InterviewQuestionDTO question = questions.get(index);
        InterviewAnswerEntity.AnswerState answerState = forcedState;

        boolean adaptive = Boolean.TRUE.equals(session.getAdaptive());

        // 只对「可能有真实作答」的内容才花模型调用；跳过与空作答直接短路
        if (answerState == null) {
            if (answer == null || answer.isBlank()) {
                answerState = InterviewAnswerEntity.AnswerState.UNANSWERED;
            } else {
                // 精确匹配的「跳过 / 不会」对**所有会话**生效（不依赖自适应会话的逐题评估）
                TurnEvaluation shortCircuit = TurnEvaluationService.shortCircuit(answer);
                answerState = shortCircuit != null
                    ? answerStateOf(shortCircuit)
                    : InterviewAnswerEntity.AnswerState.ANSWERED;
            }
        }

        int nextIndex;
        InterviewQuestionDTO nextQuestion;
        boolean hasNextQuestion;
        if (adaptive) {
            TurnEvaluation evaluation;
            if (answerState == InterviewAnswerEntity.AnswerState.ANSWERED) {
                evaluation = evaluateTurn(sessionId, question, answer);
                // 语义判定优先：模型识别出「要求跳过」时改判，本轮不计分也不追问
                answerState = answerStateOf(evaluation);
            } else {
                evaluation = answerState == InterviewAnswerEntity.AnswerState.SKIPPED
                    ? TurnEvaluation.skipped()
                    : TurnEvaluation.noAnswer();
            }
            nextQuestion = AdaptiveInterviewPolicy.selectNext(questions, index, evaluation);
            hasNextQuestion = nextQuestion != null;
            nextIndex = hasNextQuestion ? nextQuestion.questionIndex() : questions.size();
        } else {
            nextIndex = index + 1;
            hasNextQuestion = nextIndex < questions.size();
            nextQuestion = hasNextQuestion ? questions.get(nextIndex) : null;
        }

        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        // 答案与状态一起写回题目列表：跳过时答案为空，只靠答案文本前端无法还原「这题发生过」；
        // 这里写入的内容会经缓存 / 数据库进入刷新恢复路径
        questions.set(index, question.withAnswer(answer).withAnswerState(answerState));

        // 只有真实作答才写分数：跳过/未作答留空，避免被判成 0 分并进入画像证据
        persistSubmittedAnswer(sessionId, index, question, answer, answerState, nextIndex, newStatus);

        // 更新 Redis 缓存。DB 已经持久化成功，缓存失败时可由后续读取从数据库恢复。
        sessionCache.updateQuestions(sessionId, questions);
        sessionCache.updateCurrentIndex(sessionId, nextIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
            enqueueEvaluationTask(sessionId);
        }

        log.info("会话 {} 记录轮次: 问题{}, state={}, adaptive={}, 剩余{}题",
            sessionId, index, answerState, adaptive,
            adaptive ? (hasNextQuestion ? questions.size() - nextIndex - 1 : 0)
                : questions.size() - nextIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            nextIndex,
            questions.size()
        );
    }

    /**
     * 逐题评估结果 → 落库的答案状态（P4Q-5）。
     *
     * <p>三种情况语义不同：明确要求跳过（SKIPPED，一等动作）、答不上来/明确不会
     * （DECLINED，只作诊断保留）、有实质作答（ANSWERED，参与评分与画像证据）。
     */
    private static InterviewAnswerEntity.AnswerState answerStateOf(TurnEvaluation evaluation) {
        if (evaluation.skipRequested()) {
            return InterviewAnswerEntity.AnswerState.SKIPPED;
        }
        if (evaluation.answerState() == TurnEvaluation.AnswerState.NO_ANSWER) {
            return InterviewAnswerEntity.AnswerState.DECLINED;
        }
        return InterviewAnswerEntity.AnswerState.ANSWERED;
    }

    /**
     * 逐题轻量评估（同步、低延迟）。评估永不抛出（内部已回落），失败时退化为中性结果，
     * 由决策引擎保守推进，保证答题流程不断。
     */
    private TurnEvaluation evaluateTurn(String sessionId, InterviewQuestionDTO question, String answer) {
        String provider = null;
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            if (entityOpt.isPresent()) {
                provider = entityOpt.get().getLlmProvider();
            }
        } catch (Exception e) {
            log.warn("读取会话 provider 失败，使用默认模型评估: sessionId={}", sessionId);
        }
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);
        return turnEvaluationService.evaluateTurn(chatClient, question, answer);
    }

    /**
     * 落库这一轮的事实。
     *
     * <p>P4Q-5：分数只在**真实作答**时预留（由报告回填），跳过/未作答/明确不会一律留空——
     * 写 0 分会让报告把它显示成「答错」，也会以 0 分进入画像证据（缺陷期间真实发生过）。
     */
    private void persistSubmittedAnswer(String sessionId, int index,
                                        InterviewQuestionDTO question, String answer,
                                        InterviewAnswerEntity.AnswerState answerState,
                                        int newIndex, SessionStatus newStatus) {
        try {
            boolean counts = answerState == InterviewAnswerEntity.AnswerState.ANSWERED;
            persistenceService.saveAnswer(
                sessionId, index,
                question.question(), question.category(),
                answer, counts ? 0 : null, null, answerState
            );
            persistenceService.updateCurrentQuestionIndex(sessionId, newIndex);
            persistenceService.updateSessionStatus(sessionId,
                newStatus == SessionStatus.COMPLETED
                    ? InterviewSessionEntity.SessionStatus.COMPLETED
                    : InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}, questionIndex={}", sessionId, index, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "保存答案失败，请稍后重试");
        }
    }

    private void enqueueEvaluationTask(String sessionId) {
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId);
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        // 发送评估任务到 Redis Stream
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    /**
     * 重新入队评估任务（P4Q-4 的前端重试入口）。
     *
     * <p>只对「已完成但报告尚未成功生成」的会话开放；已有报告的会话直接返回当前状态，
     * 不重复评分也不重复写入证据（评估消费端另有幂等跳过）。
     */
    public InterviewSessionDTO retryEvaluation(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        if (session.getStatus() == SessionStatus.CREATED
            || session.getStatus() == SessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED,
                "面试尚未完成，无法重试评估");
        }

        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        boolean reportReady = entity.getEvaluateStatus() == AsyncTaskStatus.COMPLETED
            && entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED;
        if (reportReady) {
            log.info("报告已存在，忽略重复的评估重试: sessionId={}", sessionId);
        } else {
            // enqueueEvaluationTask 会把状态重置为 PENDING，让消费端的「已完成则跳过」判据不生效
            enqueueEvaluationTask(sessionId);
            log.info("评估任务已重新入队: sessionId={}, 上次状态={}",
                sessionId, entity.getEvaluateStatus());
        }
        return getSession(sessionId);
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatClient,
            sessionId,
            session.getResumeText(),
            questions
        );

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        return toDTO(session, null, null);
    }

    /** 带评估状态的转换（仅「已完成」会话需要，评估状态不在缓存里，数据库才是权威） */
    private InterviewSessionDTO toDTO(CachedSession session, AsyncTaskStatus evaluateStatus,
                                      String evaluateError) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus(),
            session.getKnowledgeBaseId(),
            session.getInterviewCategory(),
            Boolean.TRUE.equals(session.getAdaptive()),
            evaluateStatus,
            evaluateError,
            session.getResumeSource(),
            session.getResumeVersion()
        );
    }
}
