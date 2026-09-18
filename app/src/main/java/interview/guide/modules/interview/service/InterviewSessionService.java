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
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnRequestEntity;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluationRequest;
import interview.guide.modules.interview.policy.AdaptiveInterviewPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** 「这个请求正在处理中」的占位键前缀（P4-9a），按会话 + 请求标识隔离 */
    private static final String TURN_INFLIGHT_PREFIX = "interview:turn:inflight:";

    /**
     * 占位存活时间：够覆盖一次实时模型调用即可。进程异常时按 TTL 自动过期，
     * 不会永久挡住用户对同一标识的重试。
     */
    private static final Duration TURN_INFLIGHT_TTL = Duration.ofSeconds(120);

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
    private final interview.guide.modules.profile.service.SkillProfileQueryService skillProfileQueryService;

    /** 逐轮基线里最多列几个相关技能（逐轮上下文吃 P95 预算，基线只是参照） */
    private static final int MAX_BASELINE_SKILLS = 3;

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        String requestId = normalizeRequestId(request.requestId(), "requestId");
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

    /**
     * 请求标识格式校验（创建面试的幂等键与逐轮提交的请求标识共用同一规则）。
     *
     * @param fieldLabel 出错时说明是哪个字段，便于前端与日志定位
     */
    private String normalizeRequestId(String requestId, String fieldLabel) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String normalized = requestId.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, fieldLabel + " 格式不正确");
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

    /** 补齐评估状态字段（缓存未命中路径：已经读过数据库，顺手带上版本与评估状态） */
    private InterviewSessionDTO withReportState(InterviewSessionDTO dto, String sessionId) {
        return persistenceService.findBySessionId(sessionId)
            .map(entity -> new InterviewSessionDTO(
                dto.sessionId(), dto.resumeText(), dto.totalQuestions(), dto.currentQuestionIndex(),
                dto.questions(), dto.status(), dto.knowledgeBaseId(), dto.interviewCategory(),
                dto.adaptive(), entity.getEvaluateStatus(), entity.getEvaluateError(),
                dto.resumeSource(), dto.resumeVersion(), entity.getTurnVersion()))
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

            // 版本补进缓存：restore 是权威重建路径，少了它前端会拿到过期的 expectedVersion，
            // 下一次提交会被判成过期（P4-9a）
            sessionCache.updateTurnVersion(entity.getSessionId(), entity.getTurnVersion());

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
        return recordTurn(request.sessionId(), request.questionIndex(), request.answer(), null,
            request.requestId(), request.expectedVersion());
    }

    /**
     * 跳过当前题（P4Q-5 一等动作）。
     *
     * <p>跳过的语义：**不调模型、不追问、不计分、不产生画像证据**。它和「答错」是两件事——
     * 答错是有作答内容但质量差（正常计分），跳过是没有作答（只记录发生过）。
     * 自适应会话按「答不上来」处理：中断当前追问组，切下一主问题。
     */
    public SubmitAnswerResponse skipQuestion(String sessionId, int questionIndex) {
        return skipQuestion(sessionId, questionIndex, null, null);
    }

    /**
     * 跳过当前题（带逐轮提交一致性控制，P4-9a）。
     *
     * <p>跳过与提交答案共用同一条推进链路，因此共享同一套并发边界：请求标识、预期版本、
     * 待答题校验、单事务落库、缓存跟随提交。
     */
    public SubmitAnswerResponse skipQuestion(String sessionId, int questionIndex, String requestId,
                                             Integer expectedVersion) {
        return recordTurn(sessionId, questionIndex, null,
            InterviewAnswerEntity.AnswerState.SKIPPED, requestId, expectedVersion);
    }

    /**
     * 记录一轮（作答或跳过）并推进到下一题（P4-9a）。
     *
     * <p>执行顺序刻意分成三段，每段的边界都是可验证的事实：
     * <ol>
     *   <li><b>幂等与占位</b>：已处理过的标识返回原结果；同一标识正在处理中直接拒绝。
     *       两条都不再推进、也不再花一次模型调用。</li>
     *   <li><b>校验与决策</b>：从数据库读权威状态，校验会话状态、待答题与提交方版本；
     *       逐题评估（模型调用）发生在这里，**在任何事务之外**。</li>
     *   <li><b>一次性落库</b>：会话版本 / 索引 / 状态 / 评估请求、答案事实、幂等记录
     *       在同一个短事务里提交。缓存更新与评估入队都在提交之后。</li>
     * </ol>
     *
     * @param answer          作答内容；跳过时为 null
     * @param forcedState     调用方已确定的状态（如显式跳过）；null 表示按作答内容判定
     * @param requestId       请求标识；null 表示调用方未提供（只保留并发闸门，不做重放保护）
     * @param expectedVersion 提交方看到的会话版本；null 表示以数据库当前版本为准
     */
    private SubmitAnswerResponse recordTurn(String sessionId, int index, String answer,
                                            InterviewAnswerEntity.AnswerState forcedState,
                                            String requestId, Integer expectedVersion) {
        String normalizedRequestId = normalizeRequestId(requestId, "requestId");
        String action = forcedState == InterviewAnswerEntity.AnswerState.SKIPPED ? "SKIP" : "ANSWER";
        String payloadHash = turnPayloadHash(action, index, answer);

        Optional<InterviewTurnRequestEntity> handled =
            persistenceService.findTurnRequest(sessionId, normalizedRequestId);
        if (handled.isPresent()) {
            log.info("逐轮提交重放，返回原结果: sessionId={}, requestId={}", sessionId,
                normalizedRequestId);
            return replayTurnResponse(handled.get(), payloadHash);
        }

        String inflightKey = turnInflightKey(sessionId, normalizedRequestId);
        if (inflightKey != null && !redisService.setIfAbsent(inflightKey, "1", TURN_INFLIGHT_TTL)) {
            // 同一标识的请求正在处理中：不允许第二次独立推进
            Optional<InterviewTurnRequestEntity> finished =
                persistenceService.findTurnRequest(sessionId, normalizedRequestId);
            if (finished.isEmpty()) {
                log.warn("同标识请求正在处理中，拒绝重复推进: sessionId={}, requestId={}", sessionId,
                    normalizedRequestId);
                throw new BusinessException(ErrorCode.INTERVIEW_TURN_IN_PROGRESS);
            }
            // 临界情况：刚好处理完成而占位还没释放——按重放返回原结果
            return replayTurnResponse(finished.get(), payloadHash);
        }

        try {
            return commitTurn(sessionId, index, answer, forcedState, normalizedRequestId,
                expectedVersion, action, payloadHash);
        } finally {
            if (inflightKey != null) {
                // 成功时幂等记录已在库，重放走记录；失败时必须释放，否则用户重试会被自己挡住
                redisService.delete(inflightKey);
            }
        }
    }

    /**
     * 校验提交并一次性落库（P4-9a）。
     *
     * <p>权威状态直接读数据库：并发闸门、会话状态、待答题、LLM provider 都用同一份实体，
     * 缓存只是可恢复副本。版本与待答题在**条件更新**里再校验一次，因此即使两个请求同时
     * 通过了这里的前置校验，也只有一个能真正推进。
     */
    private SubmitAnswerResponse commitTurn(String sessionId, int index, String answer,
                                            InterviewAnswerEntity.AnswerState forcedState,
                                            String requestId, Integer expectedVersion,
                                            String action, String payloadHash) {
        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        int baseVersion = assertTurnAcceptable(entity, index, expectedVersion);

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
                evaluation = evaluateTurn(entity, session, questions, index, answer);
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

        // 本轮只保存答案事实；正式评分由异步报告回填，不写可被误提取为证据的占位分
        SubmitAnswerResponse response = new SubmitAnswerResponse(hasNextQuestion, nextQuestion,
            nextIndex, questions.size(), baseVersion + 1);
        InterviewTurnResult result = commitOrFail(InterviewTurnCommit.ofTurn(
            sessionId, requestId, action, payloadHash, baseVersion, index, nextIndex,
            newStatus == SessionStatus.COMPLETED, index, question.question(), question.category(),
            answer, answerState, serializeTurnResponse(response)));

        // 缓存跟随数据库提交：只有落库成功才会走到这里，缓存写失败可由后续读取回源数据库恢复
        updateCacheAfterTurn(sessionId, questions, nextIndex, newStatus, result.turnVersion());

        log.info("会话 {} 记录轮次: 问题{}, state={}, adaptive={}, 剩余{}题",
            sessionId, index, answerState, adaptive,
            adaptive ? (hasNextQuestion ? questions.size() - nextIndex - 1 : 0)
                : questions.size() - nextIndex);

        if (newStatus == SessionStatus.COMPLETED) {
            enqueueEvaluationTask(sessionId, result.evaluateEpoch());
        }

        return new SubmitAnswerResponse(hasNextQuestion, nextQuestion, nextIndex, questions.size(),
            result.turnVersion());
    }

    /**
     * 逐轮提交的前置校验（P4-9a）：会话状态、提交方版本、待答题。
     *
     * <p>版本或待答题对不上时，先用数据库实体重建一次缓存再报错——用户看到的是「已同步最新进度」，
     * 而不是被一个陈旧副本反复拒绝。自愈只发生在被拒绝的路径上，正常提交不额外打库。
     *
     * @return 本次提交应据以更新的会话版本
     */
    private int assertTurnAcceptable(InterviewSessionEntity entity, int index, Integer expectedVersion) {
        if (entity.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
            || entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
            // 已结束的会话不得再产出「下一题」，也不得被写回进行中（晚到的提交走这里）
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        int baseVersion = assertVersionAcceptable(entity, expectedVersion);

        Integer currentIndex = entity.getCurrentQuestionIndex();
        if (currentIndex == null || currentIndex != index) {
            log.info("逐轮提交的题号不是当前待答题: sessionId={}, submitted={}, current={}",
                entity.getSessionId(), index, currentIndex);
            restoreSessionFromEntity(entity);
            throw new BusinessException(ErrorCode.INTERVIEW_TURN_INDEX_MISMATCH);
        }

        return baseVersion;
    }

    /**
     * 校验提交方看到的版本（P4-9a）。版本对不上时先用数据库实体重建缓存再报错——
     * 用户看到的是「已同步最新进度」，而不是被一个陈旧副本反复拒绝。
     *
     * @return 本次提交应据以更新的会话版本
     */
    private int assertVersionAcceptable(InterviewSessionEntity entity, Integer expectedVersion) {
        int baseVersion = entity.getTurnVersion() != null ? entity.getTurnVersion() : 0;
        if (expectedVersion != null && expectedVersion != baseVersion) {
            log.info("逐轮提交版本过期，按数据库自愈缓存: sessionId={}, expected={}, actual={}",
                entity.getSessionId(), expectedVersion, baseVersion);
            restoreSessionFromEntity(entity);
            throw new BusinessException(ErrorCode.INTERVIEW_TURN_STALE);
        }
        return baseVersion;
    }

    /**
     * 缓存跟随提交（P4-9a）：题目列表、索引、状态、版本一次写齐。
     *
     * <p>四个字段分开写会出现「索引更新了但版本还是旧的」这种中间态，而版本是提交方的
     * 判据——中间态会让下一次提交被误判为过期。
     */
    private void updateCacheAfterTurn(String sessionId, List<InterviewQuestionDTO> questions,
                                      int nextIndex, SessionStatus status, int turnVersion) {
        sessionCache.updateQuestions(sessionId, questions);
        sessionCache.updateCurrentIndex(sessionId, nextIndex);
        if (status == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
        }
        sessionCache.updateTurnVersion(sessionId, turnVersion);
    }

    /** 已处理请求的原结果（重放）：载荷不一致说明是「同一标识不同载荷」，明确拒绝 */
    private SubmitAnswerResponse replayTurnResponse(InterviewTurnRequestEntity record,
                                                    String payloadHash) {
        assertSamePayload(record, payloadHash);
        try {
            return objectMapper.readValue(record.getResponseJson(), SubmitAnswerResponse.class);
        } catch (JacksonException e) {
            log.error("读取已处理提交的原结果失败: sessionId={}, requestId={}",
                record.getSessionId(), record.getRequestId(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上次提交结果失败");
        }
    }

    /**
     * 原子提交本轮；落库失败一律转成可见的业务失败。
     *
     * <p>事务已经在持久化层回滚，这里只负责让失败以「可重试的业务错误」而不是 500 冒出去——
     * 用户需要的是一次干净重试，而不是一个看起来像服务端崩溃的响应。
     */
    private InterviewTurnResult commitOrFail(InterviewTurnCommit commit) {
        try {
            return persistenceService.applyTurn(commit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("逐轮提交落库失败已回滚: sessionId={}, action={}, index={}",
                commit.sessionId(), commit.action(), commit.questionIndex(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED, "保存本轮进度失败，请稍后重试", e);
        }
    }

    private static void assertSamePayload(InterviewTurnRequestEntity record, String payloadHash) {
        if (!record.getPayloadHash().equals(payloadHash)) {
            throw new BusinessException(ErrorCode.INTERVIEW_TURN_REQUEST_CONFLICT);
        }
    }

    private String serializeTurnResponse(SubmitAnswerResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            // 序列化失败发生在事务之前：本轮不推进，客户端可原样重试
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化本轮结果失败");
        }
    }

    /** 占位键按「会话 + 请求标识」隔离：挡住的只有同一次提交的重复，不影响同一会话的其他操作 */
    private String turnInflightKey(String sessionId, String requestId) {
        return requestId == null ? null : TURN_INFLIGHT_PREFIX + sessionId + ":" + requestId;
    }

    /**
     * 载荷指纹（动作 + 题号 + 作答内容）。
     *
     * <p>用于把「重放同一次提交」与「同一个标识换了内容」分开：前者返回原结果，
     * 后者必须拒绝——否则标识就成了可随意改写历史的开关。
     *
     * <p>包级可见是为了让同包测试能构造出「与本次提交完全一致的记录」，
     * 而不是把算法复制一份到测试里（复制品会随实现漂移而失去意义）。
     */
    static String turnPayloadHash(String action, int index, String answer) {
        String raw = action + "|" + index + "|" + (answer == null ? "" : answer);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算请求指纹失败");
        }
    }

    /**
     * 逐题评估结果 → 落库的答案状态（P4Q-5）。
     *
     * <p>三种情况语义不同：明确要求跳过（SKIPPED，一等动作）、答不上来/明确不会
     * （DECLINED，只作诊断保留）、有实质作答（ANSWERED，参与评分与画像证据）。
     */
    private static InterviewAnswerEntity.AnswerState answerStateOf(TurnEvaluation evaluation) {
        if (evaluation == null) {
            // 评估缺失不等于用户未作答；保留原文，供后续正式报告独立评估
            return InterviewAnswerEntity.AnswerState.ANSWERED;
        }
        if (evaluation.skipRequested()) {
            return InterviewAnswerEntity.AnswerState.SKIPPED;
        }
        if (evaluation.answerState() == TurnEvaluation.AnswerState.NO_ANSWER) {
            return InterviewAnswerEntity.AnswerState.DECLINED;
        }
        return InterviewAnswerEntity.AnswerState.ANSWERED;
    }

    /**
     * 逐题轻量评估（同步、低延迟）。评估失败时返回未知质量，
     * 由决策引擎保守推进，保证答题流程不断。
     *
     * <p>provider 与简历文本都来自调用方已经读到的实体/缓存，不再自己回查一次数据库。
     */
    private TurnEvaluation evaluateTurn(InterviewSessionEntity entity, CachedSession session,
                                        List<InterviewQuestionDTO> questions,
                                        int index, String answer) {
        String sessionId = session.getSessionId();
        InterviewQuestionDTO question = questions.get(index);
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(entity.getLlmProvider());
        // P4Q-1：喂进三类参照物——只看一道孤立的题，模型无法判断「他说的是不是自己简历里的事」
        // 「这轮有没有新信息」「这次表现是否超出长期水平」
        return turnEvaluationService.evaluateTurn(chatClient, new TurnEvaluationRequest(
            question,
            answer,
            TurnEvaluationService.resumeSnippetFor(session.getResumeText(), question.category()),
            TurnEvaluationService.recentTurnsFor(questions, index, question.category()),
            profileBaselineFor(question.category())));
    }

    /**
     * 当前题的画像基线（如「MySQL 44 分（2 条证据）」）；无匹配技能时返回 null。
     *
     * <p>用轻量画像查询（只查画像表、不带证据明细）：逐轮评估本身要等模型，
     * 多一次无关联查询不是瓶颈；反过来，为省这一次查询去引入会话级缓存，
     * 换来的是又一层需要同步的状态。
     */
    private String profileBaselineFor(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String needle = category.strip().toLowerCase(java.util.Locale.ROOT);
        List<String> matched = skillProfileQueryService.listProfiles().stream()
            .filter(profile -> profile.skill() != null)
            .filter(profile -> {
                String skill = profile.skill().strip().toLowerCase(java.util.Locale.ROOT);
                return skill.contains(needle) || needle.contains(skill);
            })
            .limit(MAX_BASELINE_SKILLS)
            .map(profile -> profile.skill() + " " + profile.score() + " 分（"
                + profile.evidenceCount() + " 条证据）")
            .toList();
        return matched.isEmpty() ? null : String.join("；", matched);
    }

    /**
     * 触发评估（P4-9a）：评估状态与代次已在本轮事务里写好，这里只负责投递。
     *
     * <p>投递失败由生产者把评估状态标成 FAILED，前端有重试入口；代次保证重复触发不会
     * 产出第二份报告与第二批画像证据。
     */
    private void enqueueEvaluationTask(String sessionId, long evaluateEpoch) {
        evaluateStreamProducer.sendEvaluateTask(sessionId, evaluateEpoch);
        log.info("会话 {} 已完成所有问题，评估任务已入队（epoch={}）", sessionId, evaluateEpoch);
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
        completeInterview(sessionId, null, null);
    }

    /**
     * 提前交卷（带逐轮提交一致性控制，P4-9a）。
     *
     * <p>结束与逐轮推进共用同一并发边界：请求标识、预期版本、以及「只允许进行中的会话被置为
     * COMPLETED」的条件更新。因此「用户点了结束」和「某次提交的晚到模型结果」之间没有竞态——
     * 先落库的赢，另一个只会拿到可见的过期原因，不会把已结束的会话写回进行中。
     */
    public void completeInterview(String sessionId, String requestId, Integer expectedVersion) {
        String normalizedRequestId = normalizeRequestId(requestId, "requestId");
        String payloadHash = turnPayloadHash(InterviewTurnCommit.ACTION_COMPLETE, -1, null);

        Optional<InterviewTurnRequestEntity> handled =
            persistenceService.findTurnRequest(sessionId, normalizedRequestId);
        if (handled.isPresent()) {
            assertSamePayload(handled.get(), payloadHash);
            log.info("提前交卷重放，忽略重复请求: sessionId={}, requestId={}", sessionId,
                normalizedRequestId);
            return;
        }

        String inflightKey = turnInflightKey(sessionId, normalizedRequestId);
        if (inflightKey != null && !redisService.setIfAbsent(inflightKey, "1", TURN_INFLIGHT_TTL)) {
            Optional<InterviewTurnRequestEntity> finished =
                persistenceService.findTurnRequest(sessionId, normalizedRequestId);
            if (finished.isEmpty()) {
                throw new BusinessException(ErrorCode.INTERVIEW_TURN_IN_PROGRESS);
            }
            assertSamePayload(finished.get(), payloadHash);
            return;
        }

        try {
            InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
            if (entity.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
                || entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
                throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
            }
            int baseVersion = assertVersionAcceptable(entity, expectedVersion);
            int currentIndex = entity.getCurrentQuestionIndex() != null
                ? entity.getCurrentQuestionIndex() : 0;

            InterviewTurnResult result = commitOrFail(InterviewTurnCommit.ofFinish(
                sessionId, normalizedRequestId, payloadHash, baseVersion, currentIndex, "{}"));

            // 状态与版本在提交成功之后才写缓存（缓存跟随数据库提交）
            sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
            sessionCache.updateTurnVersion(sessionId, result.turnVersion());
            enqueueEvaluationTask(sessionId, result.evaluateEpoch());

            log.info("会话 {} 提前交卷，评估任务已入队（version={}）", sessionId, result.turnVersion());
        } finally {
            if (inflightKey != null) {
                redisService.delete(inflightKey);
            }
        }
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
            // 代次 +1 并置回 PENDING（P4-9a）：否则消费端的「已完成则跳过」或「代次落后」判据
            // 会让重试静默无效，也挡不住上一轮尚未跑完的旧任务
            Optional<Long> epoch = persistenceService.requestEvaluation(sessionId);
            if (epoch.isPresent()) {
                enqueueEvaluationTask(sessionId, epoch.get());
                log.info("评估任务已重新入队: sessionId={}, 上次状态={}, epoch={}",
                    sessionId, entity.getEvaluateStatus(), epoch.get());
            } else {
                log.warn("会话状态不允许请求评估，忽略重试: sessionId={}, status={}",
                    sessionId, entity.getStatus());
            }
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
