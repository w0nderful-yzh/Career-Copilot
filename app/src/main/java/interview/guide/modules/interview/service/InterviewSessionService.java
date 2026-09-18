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
import interview.guide.modules.interview.listener.CandidateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.InterviewTurnDTO;
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
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /**
     * 后台候选预备生产者（P4-4b）。可选依赖：用字段注入而不是进构造参数，以免
     * 扰动逐轮提交一致性相关的现有测试构造点；单测（无 Spring 上下文）下为 null，自动跳过预备。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CandidateStreamProducer candidateStreamProducer;

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

        // P4Q-2：计划以「预计时长 + 必要覆盖」表达；规模（主问题数）由时长推导。
        // 旧调用方只传题数 → 按每主问题 4 分钟折算，保持规模意图不丢（P4-8a 再改契约）。
        InterviewPlan requestedPlan = InterviewPlan.of(request.plannedDurationMinutes(),
            request.requiredTopics(), request.questionCount());
        int mainCount = InterviewPlan.mainCountFor(requestedPlan.plannedDurationMinutes());

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, 计划 {} 分钟（约 {} 个主问题）, resumeId: {}",
            sessionId, skillId, difficulty, requestedPlan.plannedDurationMinutes(), mainCount,
            request.resumeId());

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
            mainCount,
            historicalQuestions,
            request.customCategories(),
            request.jdText(),
            request.focusCategories()
        );
        // 必要覆盖只能引用本场真实存在的主问题。简历没有实习经历时，即使上游提案仍带了
        // 「实习经历」，也不能留下一个永远无法完成的覆盖目标。
        InterviewPlan plan = applicablePlan(requestedPlan, questions);

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
                    resumeContext,
                    plan
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
                    adaptive, resumeContext, plan);
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
        sessionCache.applyPlan(sessionId, plan);

        return toDTO(sessionCache.getSession(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "读取新建面试会话失败")));
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
        return toDTO(cached, entity.getEvaluateStatus(), entity.getEvaluateError(),
            entity.getEndReason());
    }

    /** 补齐评估状态字段（缓存未命中路径：已经读过数据库，顺手带上版本与评估状态） */
    private InterviewSessionDTO withReportState(InterviewSessionDTO dto, String sessionId) {
        return persistenceService.findBySessionId(sessionId)
            .map(entity -> new InterviewSessionDTO(
                dto.sessionId(), dto.resumeText(), dto.totalQuestions(), dto.currentQuestionIndex(),
                dto.currentQuestionId(), dto.currentQuestion(), dto.candidates(), dto.turns(),
                dto.status(), dto.knowledgeBaseId(), dto.interviewCategory(),
                dto.adaptive(), entity.getEvaluateStatus(), entity.getEvaluateError(),
                dto.resumeSource(), dto.resumeVersion(), entity.getTurnVersion(),
                entity.getEndReason(),
                dto.plannedDurationMinutes(), dto.consumedSeconds(), dto.remainingSeconds(),
                dto.requiredTopics()))
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

            // P4-1：候选素材补上缺失的标识（旧数据按 legacy-<下标> 派生），但**不把答案写回素材**
            questions = InterviewQuestionIdentity.withDerivedIds(questions);
            // 实际轨迹单独取：只含真实发生过的轮次，与素材互不污染
            List<InterviewTurnDTO> turns = persistenceService.findTurnsBySessionId(entity.getSessionId());

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
            sessionCache.applyTurnState(entity.getSessionId(), questions, turns,
                entity.getCurrentQuestionIndex() != null ? entity.getCurrentQuestionIndex() : 0,
                entity.getCurrentQuestionId(), status, entity.getTurnVersion(),
                entity.getConsumedSeconds());
            InterviewPlan restoredPlan = planOf(entity);
            if (restoredPlan != null) {
                sessionCache.applyPlan(entity.getSessionId(), restoredPlan);
            }

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
     * 获取当前问题（P4-1：按**题目标识**定位，不再用数组下标推算）
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        InterviewQuestionDTO question = currentQuestionOf(session);

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

        return question;
    }

    /**
     * 会话的当前待答题（P4-1）。
     *
     * <p>优先按标识定位；旧缓存 / 旧会话没有标识时按展示顺序兜底，并且接受「已问尽」（返回 null）。
     */
    private InterviewQuestionDTO currentQuestionOf(CachedSession session) {
        if (session.getStatus() == SessionStatus.COMPLETED
            || session.getStatus() == SessionStatus.EVALUATED) {
            return null;
        }
        List<InterviewQuestionDTO> candidates = InterviewQuestionIdentity.withDerivedIds(
            session.getQuestions(objectMapper));
        Optional<InterviewQuestionDTO> byId =
            InterviewQuestionIdentity.byId(candidates, session.getCurrentQuestionId());
        if (byId.isPresent()) {
            return byId.get();
        }
        int index = session.getCurrentIndex();
        return index >= 0 && index < candidates.size() ? candidates.get(index) : null;
    }

    /** 会话的实际轨迹（P4-1）：缓存里有一份；缺失时回源数据库 */
    private List<InterviewTurnDTO> turnsOf(CachedSession session) {
        List<InterviewTurnDTO> cached = session.getTurns(objectMapper);
        if (session.hasTurnsSnapshot()) {
            return cached;
        }
        return persistenceService.findTurnsBySessionId(session.getSessionId());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        return recordTurn(request.sessionId(), request.questionId(), request.questionIndex(),
            request.answer(), null, request.requestId(), request.expectedVersion());
    }

    /**
     * 跳过当前题（P4Q-5 一等动作）。
     *
     * <p>跳过的语义：**不调模型、不追问、不计分、不产生画像证据**。它和「答错」是两件事——
     * 答错是有作答内容但质量差（正常计分），跳过是没有作答（只记录发生过）。
     * 自适应会话按「答不上来」处理：中断当前追问组，切下一主问题。
     */
    public SubmitAnswerResponse skipQuestion(String sessionId, String questionId) {
        return skipQuestion(sessionId, questionId, null, null, null);
    }

    /**
     * 跳过当前题（带逐轮提交一致性控制，P4-9a）。
     *
     * <p>跳过与提交答案共用同一条推进链路，因此共享同一套并发边界：请求标识、预期版本、
     * 待答题校验、单事务落库、缓存跟随提交。
     *
     * @param questionIndex 旧调用方兼容：没有题目标识时按候选池顺序定位（新前端只传标识）
     */
    public SubmitAnswerResponse skipQuestion(String sessionId, String questionId,
                                             Integer questionIndex, String requestId,
                                             Integer expectedVersion) {
        return recordTurn(sessionId, questionId, questionIndex, null,
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
    private SubmitAnswerResponse recordTurn(String sessionId, String questionId, Integer questionIndex,
                                            String answer,
                                            InterviewAnswerEntity.AnswerState forcedState,
                                            String requestId, Integer expectedVersion) {
        String normalizedRequestId = normalizeRequestId(requestId, "requestId");
        String action = forcedState == InterviewAnswerEntity.AnswerState.SKIPPED ? "SKIP" : "ANSWER";
        // 指纹用**题目标识**：旧调用方没有标识时退回下标（只影响兼容期的重放判定）
        String identity = questionId != null && !questionId.isBlank()
            ? questionId
            : "index:" + questionIndex;
        String payloadHash = turnPayloadHash(action, identity, answer);

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
            return commitTurn(sessionId, questionId, questionIndex, answer, forcedState,
                normalizedRequestId, expectedVersion, action, payloadHash);
        } finally {
            if (inflightKey != null) {
                // 成功时幂等记录已在库，重放走记录；失败时必须释放，否则用户重试会被自己挡住
                redisService.delete(inflightKey);
            }
        }
    }

    /**
     * 校验提交并一次性落库（P4-1 / P4-9a）。
     *
     * <p>权威状态直接读数据库：并发闸门、会话状态、当前题、LLM provider 都用同一份实体，
     * 缓存只是可恢复副本。版本与当前题在**条件更新**里再校验一次，因此即使两个请求同时
     * 通过了这里的前置校验，也只有一个能真正推进。
     *
     * <p>P4-1：候选素材只读，本轮事实写进实际轨迹（数据库 + 缓存各一份），
     * 因此「未问候选」永远不会因为作答而被改写。
     */
    private SubmitAnswerResponse commitTurn(String sessionId, String questionId, Integer questionIndex,
                                            String answer,
                                            InterviewAnswerEntity.AnswerState forcedState,
                                            String requestId, Integer expectedVersion,
                                            String action, String payloadHash) {
        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        int baseVersion = assertTurnAcceptable(entity, questionId, questionIndex, expectedVersion);

        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> candidates = InterviewQuestionIdentity.withDerivedIds(
            session.getQuestions(objectMapper));
        List<InterviewTurnDTO> turns = turnsOf(session);

        InterviewQuestionDTO question = resolveCandidate(candidates, questionId, questionIndex);
        if (question == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                "提交的题目不在本场候选内: " + (questionId != null ? questionId : questionIndex));
        }
        String resolvedId = question.questionId();

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

        // 已问过的标识来自实际轨迹：路径不再由下标决定，同一题也不会被问第二遍
        Set<String> askedIds = turns.stream()
            .map(InterviewTurnDTO::questionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        askedIds.add(resolvedId);

        // P4Q-2：计划在评估前就要确定——逐轮上下文的覆盖摘要、剩余时间与追问预算都由它推导
        InterviewPlan plan = planOf(entity);

        // P4Q-2：模型评估耗时单独计时——它是「系统等待」，不扣用户答题预算
        long evaluationStarted = System.nanoTime();
        long evaluationMillis = 0;
        InterviewQuestionDTO nextQuestion;
        AdaptiveInterviewPolicy.Decision decision;
        boolean coverageSatisfied = requiredCoverageSatisfied(candidates, askedIds, plan);
        // P4-4b：本轮是否向候选池追加了生成题（需与推进同事务写回 questions_json）
        boolean candidatesChanged = false;
        String generatedQuestionId = null;
        Integer newCandidateVersion = null;
        if (adaptive) {
            TurnEvaluation evaluation;
            if (answerState == InterviewAnswerEntity.AnswerState.ANSWERED) {
                evaluation = evaluateTurn(entity, session, candidates, turns, question, answer, plan);
                evaluationMillis = (System.nanoTime() - evaluationStarted) / 1_000_000;
                // 语义判定优先：模型识别出「要求跳过」时改判，本轮不计分也不追问
                answerState = answerStateOf(evaluation);
            } else {
                evaluation = answerState == InterviewAnswerEntity.AnswerState.SKIPPED
                    ? TurnEvaluation.skipped()
                    : TurnEvaluation.noAnswer();
            }
            decision = AdaptiveInterviewPolicy.decideNext(candidates, askedIds, question, evaluation,
                coverageSatisfied, questionService.getFollowUpBudget(), entity.getDifficultyPreference());
            nextQuestion = decision.nextQuestion();
            // P4-4b：无合适候选但接纳了受限生成时，把生成题追加进候选池作为正式下一题；
            // 它先只在内存，落库由下面的 commit 携带的 newQuestionsJson 完成，未落库前不对外承诺
            if (decision.generated() != null && nextQuestion == null) {
                InterviewQuestionDTO generated = buildGeneratedFollowUp(
                    question, candidates, decision.generated(), entity.getDifficultyPreference(),
                    entity.getCandidateVersion());
                if (generated != null) {
                    candidates = new ArrayList<>(candidates);
                    candidates.add(generated);
                    nextQuestion = generated;
                    generatedQuestionId = generated.questionId();
                    newCandidateVersion = generated.candidateVersion();
                    candidatesChanged = true;
                }
            }
        } else {
            nextQuestion = nextUnasked(candidates, askedIds);
            decision = new AdaptiveInterviewPolicy.Decision(nextQuestion,
                "非自适应会话按候选顺序推进", "", false, false);
        }

        // P4Q-2：预算与覆盖的**硬边界**（Java 判定，不依赖模型自觉）。
        // 模型可以建议结束，但只有必要覆盖真实完成时才接纳；预算用尽仍由代码强制收束。
        int answerSeconds = answerSecondsOf(entity, evaluationMillis);
        int consumedSeconds = baseConsumedSeconds(entity) + answerSeconds;
        int remainingAfterTurn = plan != null
            ? Math.max(0, plan.budgetSeconds() - consumedSeconds)
            : Integer.MAX_VALUE;

        // 必要话题预留：剩余时间进入「每个未覆盖必要话题约 4 分钟」窗口后，
        // Java 会把下一题校正为尚未覆盖的必要主问题，避免模型把最后预算继续花在可选话题。
        InterviewQuestionDTO reservedRequired = requiredTopicToReserve(
            candidates, askedIds, plan, remainingAfterTurn);
        if (nextQuestion != null && reservedRequired != null
            && !reservedRequired.questionId().equals(nextQuestion.questionId())) {
            nextQuestion = reservedRequired;
            decision = new AdaptiveInterviewPolicy.Decision(nextQuestion,
                "剩余时间已进入必要话题预留窗口，优先补齐必要覆盖", "", false, false);
        }

        String finishReason = null;
        if (decision.finishRecommended() && coverageSatisfied) {
            finishReason = InterviewSessionEntity.END_COVERAGE_SATISFIED;
        } else if (plan != null && consumedSeconds >= plan.budgetSeconds()) {
            finishReason = InterviewSessionEntity.END_BUDGET_EXHAUSTED;
        } else if (nextQuestion == null) {
            finishReason = coverageSatisfied
                ? InterviewSessionEntity.END_COVERAGE_SATISFIED
                : InterviewSessionEntity.END_CANDIDATES_EXHAUSTED;
        }

        String nextQuestionId;
        int nextIndex;
        if (finishReason != null) {
            nextQuestion = null;
            nextQuestionId = null;
            nextIndex = question.questionIndex();
        } else if (nextQuestion != null) {
            nextQuestionId = nextQuestion.questionId();
            nextIndex = nextQuestion.questionIndex();
        } else {
            nextQuestionId = null;
            nextIndex = candidates.size();
        }

        boolean hasNextQuestion = nextQuestion != null;
        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;
        String decidedAction = decidedActionOf(question, nextQuestion, finishReason);
        String decisionReason = finishReason != null
            ? finishReasonText(finishReason)
            : decision.reason();
        // 承接语只跟随被接纳的模型决定；预算等硬边界改写动作时必须丢弃。
        // 语义 FINISH 本身被接纳时仍保留自然收束语，而不是突然只剩「评估中」。
        String transitionMessage = decision.recommendationAccepted()
            && (finishReason == null || decision.finishRecommended())
            ? emptyToNull(decision.transitionMessage())
            : null;

        // 本轮只保存答案事实；正式评分由异步报告回填，不写可被误提取为证据的占位分。
        // 预算随载荷带回（P4Q-2）：前端刷新顶栏不必再发一次会话请求
        Integer remainingSeconds = plan != null
            ? Math.max(0, plan.budgetSeconds() - consumedSeconds)
            : null;
        SubmitAnswerResponse response = new SubmitAnswerResponse(hasNextQuestion, nextQuestion,
            nextIndex, candidates.size(), baseVersion + 1, consumedSeconds, remainingSeconds,
            transitionMessage);
        InterviewTurnCommit commit = InterviewTurnCommit.ofTurn(
            sessionId, requestId, action, payloadHash, baseVersion,
            resolvedId, nextIndex, nextQuestionId, resolvedId,
            answerSeconds, decidedAction, decisionReason, transitionMessage,
            newStatus == SessionStatus.COMPLETED, question.questionIndex(), question.question(),
            question.category(), answer, answerState, serializeTurnResponse(response));
        // P4-4b：接纳生成题时把追加后的候选池一并写回（与推进同一个短事务）
        if (candidatesChanged) {
            commit = commit.withGenerated(serializeCandidates(candidates), generatedQuestionId,
                newCandidateVersion);
        }
        InterviewTurnResult result = commitOrFail(commit);

        // 缓存跟随数据库提交：候选保持只读，轨迹追加本轮（序号用落库分配的那个）
        List<InterviewTurnDTO> updatedTurns = new ArrayList<>(turns);
        updatedTurns.add(new InterviewTurnDTO(resolvedId, result.turnOrdinal(),
            question.questionIndex(), question.question(), question.category(), question.topic(),
            answer, answerState, null, null, decidedAction, null, null, LocalDateTime.now(),
            nextQuestionId, decisionReason, transitionMessage));
        sessionCache.applyTurnState(sessionId, candidates, updatedTurns, nextIndex, nextQuestionId,
            newStatus, result.turnVersion(), consumedSeconds);

        // P4-4b：进入新的主问题且本组追问素材偏低时，按需投递后台预备（有界、非阻塞、代次闸门保护）
        maybeEnqueueBackgroundPrep(sessionId, candidates, askedIds, nextQuestion,
            candidatesChanged ? newCandidateVersion : entity.getCandidateVersion());

        log.info("会话 {} 记录轮次: 题目{}, state={}, adaptive={}, 决定={}, 已问候选 {}/{}",
            sessionId, resolvedId, answerState, adaptive, decidedAction,
            askedIds.size(), candidates.size());

        if (newStatus == SessionStatus.COMPLETED) {
            enqueueEvaluationTask(sessionId, result.evaluateEpoch());
        }

        return new SubmitAnswerResponse(hasNextQuestion, nextQuestion, nextIndex, candidates.size(),
            result.turnVersion(), consumedSeconds, remainingSeconds, transitionMessage);
    }

    /** 按标识（优先）或候选池顺序（旧调用方）定位题目 */
    private static InterviewQuestionDTO resolveCandidate(List<InterviewQuestionDTO> candidates,
                                                        String questionId, Integer questionIndex) {
        if (questionId != null && !questionId.isBlank()) {
            return InterviewQuestionIdentity.byId(candidates, questionId).orElse(null);
        }
        if (questionIndex != null && questionIndex >= 0 && questionIndex < candidates.size()) {
            return candidates.get(questionIndex);
        }
        return null;
    }

    /** 顺序题单的下一题：池内第一个还没问过的候选 */
    private static InterviewQuestionDTO nextUnasked(List<InterviewQuestionDTO> candidates,
                                                    Set<String> askedIds) {
        return candidates.stream()
            .filter(question -> !askedIds.contains(question.questionId()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 本轮最终决定（P4-1 / P4Q-2）：记 Java **真正执行**的那个动作，不是模型的建议。
     *
     * <p>收束原因（候选耗尽 / 覆盖完成 / 预算用尽 / 用户结束）由此可区分——
     * 复盘要说得出「这场为什么结束」。
     */
    private static String decidedActionOf(InterviewQuestionDTO answered, InterviewQuestionDTO next,
                                          String finishReason) {
        if (finishReason != null) {
            return switch (finishReason) {
                case InterviewSessionEntity.END_CANDIDATES_EXHAUSTED ->
                    InterviewTurnDTO.ACTION_FINISH_EXHAUSTED;
                case InterviewSessionEntity.END_COVERAGE_SATISFIED ->
                    InterviewTurnDTO.ACTION_FINISH_COVERAGE;
                case InterviewSessionEntity.END_BUDGET_EXHAUSTED ->
                    InterviewTurnDTO.ACTION_FINISH_BUDGET;
                default -> InterviewTurnDTO.ACTION_FINISH_USER;
            };
        }
        boolean sameGroup = next.isFollowUp()
            && answered.parentQuestionId() != null
            && answered.parentQuestionId().equals(next.parentQuestionId());
        boolean enteringAnsweredGroup = next.isFollowUp()
            && answered.questionId() != null
            && answered.questionId().equals(next.parentQuestionId());
        return sameGroup || enteringAnsweredGroup
            ? InterviewTurnDTO.ACTION_FOLLOW_UP
            : InterviewTurnDTO.ACTION_NEXT_MAIN;
    }

    /**
     * 会话计划（P4Q-2）：以数据库实体为准。
     *
     * <p>旧会话没有计划（返回 null）——此时不做预算/覆盖收束，
     * 面试按候选自然推进，不编造一个默认计划去约束已经进行到一半的场次。
     */
    private InterviewPlan planOf(InterviewSessionEntity entity) {
        if (entity.getPlannedDurationMinutes() == null) {
            return null;
        }
        List<String> required = List.of();
        String json = entity.getRequiredTopicsJson();
        if (json != null && !json.isBlank()) {
            try {
                required = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            } catch (JacksonException e) {
                log.warn("解析必要覆盖失败，按未声明覆盖处理: sessionId={}", entity.getSessionId(), e);
            }
        }
        return new InterviewPlan(entity.getPlannedDurationMinutes(), required);
    }

    /**
     * 只保留候选池里真实存在的必要话题。
     *
     * <p>上游提案可能要求「实习经历」，但简历没有实习、出题池也就没有对应主问题；
     * 这时继续保存该目标会让覆盖永远无法完成，只能等预算强制结束。
     */
    private static InterviewPlan applicablePlan(InterviewPlan requested,
                                                List<InterviewQuestionDTO> candidates) {
        if (requested == null || requested.requiredTopics().isEmpty()) {
            return requested;
        }
        List<String> available = requested.requiredTopics().stream()
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(topic -> !topic.isEmpty())
            .filter(topic -> candidates.stream()
                .filter(InterviewQuestionDTO::isMain)
                .anyMatch(question -> question.matchesTopic(topic)))
            .distinct()
            .toList();
        return new InterviewPlan(requested.plannedDurationMinutes(), available);
    }

    private static int baseConsumedSeconds(InterviewSessionEntity entity) {
        return entity.getConsumedSeconds() != null ? entity.getConsumedSeconds() : 0;
    }

    /**
     * 本轮用户答题耗时（秒，P4Q-2）：展示时刻 → 提交时刻，扣除本轮模型评估耗时。
     *
     * <p>没有记账起点（旧会话）时返回 0——宁可不扣，也不编一个数。
     */
    private static int answerSecondsOf(InterviewSessionEntity entity, long evaluationMillis) {
        if (entity.getQuestionPresentedAt() == null) {
            return 0;
        }
        long wallMillis = java.time.Duration.between(entity.getQuestionPresentedAt(),
            LocalDateTime.now()).toMillis();
        return (int) Math.max(0, (wallMillis - evaluationMillis) / 1000);
    }

    /**
     * 必要覆盖是否已完成（P4Q-2）：判据来自**实际轨迹**（askedIds），不是模型自述的「已完成」。
     *
     * <p>话题按「topic 优先、category 兜底」与主问题匹配；未声明必要覆盖时恒为 false——
     * 默认口径下覆盖完成不作为收束条件（否则主问题问完就会掐掉还有价值的追问深挖）。
     */
    private static boolean requiredCoverageSatisfied(List<InterviewQuestionDTO> candidates,
                                                     Set<String> askedIds, InterviewPlan plan) {
        if (plan == null || plan.requiredTopics().isEmpty()) {
            return false;
        }
        for (String topic : plan.requiredTopics()) {
            boolean satisfied = candidates.stream()
                .filter(InterviewQuestionDTO::isMain)
                .filter(question -> askedIds.contains(question.questionId()))
                .anyMatch(question -> question.matchesTopic(topic));
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    /**
     * 剩余预算进入必要话题预留窗口时，返回下一条必须优先展示的主问题。
     *
     * <p>每个未覆盖必要话题按 {@link InterviewPlan#MINUTES_PER_MAIN_QUESTION} 分钟预留；
     * 只使用池内真实存在且尚未问过的主问题，不动态生成、不跨越候选边界。
     */
    private static InterviewQuestionDTO requiredTopicToReserve(
            List<InterviewQuestionDTO> candidates, Set<String> askedIds,
            InterviewPlan plan, int remainingSeconds) {
        if (plan == null || plan.requiredTopics().isEmpty() || remainingSeconds <= 0) {
            return null;
        }
        List<String> pending = plan.requiredTopics().stream()
            .filter(topic -> candidates.stream()
                .filter(InterviewQuestionDTO::isMain)
                .filter(question -> question.matchesTopic(topic))
                .noneMatch(question -> askedIds.contains(question.questionId())))
            .toList();
        int reserveSeconds = pending.size()
            * InterviewPlan.MINUTES_PER_MAIN_QUESTION * 60;
        if (pending.isEmpty() || remainingSeconds > reserveSeconds) {
            return null;
        }
        for (String topic : pending) {
            Optional<InterviewQuestionDTO> candidate = candidates.stream()
                .filter(InterviewQuestionDTO::isMain)
                .filter(question -> !askedIds.contains(question.questionId()))
                .filter(question -> question.matchesTopic(topic))
                .findFirst();
            if (candidate.isPresent()) {
                return candidate.get();
            }
        }
        return null;
    }

    private static String finishReasonText(String finishReason) {
        return switch (finishReason) {
            case InterviewSessionEntity.END_COVERAGE_SATISFIED ->
                "必要覆盖已完成，且本轮语义判断没有继续提问的收益";
            case InterviewSessionEntity.END_BUDGET_EXHAUSTED ->
                "用户答题时间已达到本场预算";
            case InterviewSessionEntity.END_USER_FINISHED ->
                "用户主动结束面试";
            default -> "没有剩余的合法候选问题";
        };
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 逐轮提交的前置校验（P4-9a）：会话状态、提交方版本、待答题。
     *
     * <p>版本或待答题对不上时，先用数据库实体重建一次缓存再报错——用户看到的是「已同步最新进度」，
     * 而不是被一个陈旧副本反复拒绝。自愈只发生在被拒绝的路径上，正常提交不额外打库。
     *
     * @return 本次提交应据以更新的会话版本
     */
    private int assertTurnAcceptable(InterviewSessionEntity entity, String questionId,
                                     Integer questionIndex, Integer expectedVersion) {
        if (entity.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
            || entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
            // 已结束的会话不得再产出「下一题」，也不得被写回进行中（晚到的提交走这里）
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        int baseVersion = assertVersionAcceptable(entity, expectedVersion);

        if (!matchesCurrentQuestion(entity, questionId, questionIndex)) {
            log.info("逐轮提交的题目不是当前待答题: sessionId={}, submitted={}, current={}",
                entity.getSessionId(), questionId, entity.getCurrentQuestionId());
            restoreSessionFromEntity(entity);
            throw new BusinessException(ErrorCode.INTERVIEW_TURN_INDEX_MISMATCH);
        }

        return baseVersion;
    }

    /**
     * 提交的是不是当前待答题（P4-1）。
     *
     * <p>标识优先；旧会话（会话行没有标识）退回按下标比较——兼容期两条路都必须能走通。
     */
    private static boolean matchesCurrentQuestion(InterviewSessionEntity entity, String questionId,
                                                  Integer questionIndex) {
        String currentId = entity.getCurrentQuestionId();
        if (currentId != null) {
            if (questionId != null && !questionId.isBlank()) {
                return currentId.equals(questionId);
            }
            // 兼容迁移前客户端：它只知道候选池顺序，不知道新生成的稳定标识。
            Integer currentIndex = entity.getCurrentQuestionIndex();
            return currentIndex != null && questionIndex != null && currentIndex.equals(questionIndex);
        }
        Integer currentIndex = entity.getCurrentQuestionIndex();
        return currentIndex != null && questionIndex != null && currentIndex.equals(questionIndex);
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

    /** 序列化追加生成题后的候选池（P4-4b）：与推进同一个短事务写回 questions_json */
    private String serializeCandidates(List<InterviewQuestionDTO> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化候选池失败");
        }
    }

    /**
     * 按需投递后台预备候选任务（P4-4b）。
     *
     * <p>只在「刚进入一个新主问题」且该组未问追问低于阈值时投递；携带当前候选代次，
     * 供消费端回写前判过期。预备失败/丢失都不影响实时面试（同轮生成兑底）。
     * 后台预备生产者为可选依赖：未注入（单测）或阈值为 0 时不预备。
     */
    private void maybeEnqueueBackgroundPrep(String sessionId, List<InterviewQuestionDTO> candidates,
                                            Set<String> askedIds, InterviewQuestionDTO nextQuestion,
                                            Integer currentCandidateVersion) {
        if (candidateStreamProducer == null || nextQuestion == null || !nextQuestion.isMain()) {
            return;
        }
        int threshold = questionService.getBackgroundPrepThreshold();
        if (threshold <= 0) {
            return;
        }
        long unaskedFollowUps = candidates.stream()
            .filter(InterviewQuestionDTO::isFollowUp)
            .filter(question -> nextQuestion.questionId().equals(question.parentQuestionId()))
            .filter(question -> !askedIds.contains(question.questionId()))
            .count();
        if (unaskedFollowUps < threshold) {
            long version = currentCandidateVersion == null ? 0L : currentCandidateVersion;
            candidateStreamProducer.sendPrepTask(sessionId, nextQuestion.questionId(), version);
        }
    }

    /**
     * 把被接纳的受限生成追问构造成正式候选（P4-4b）。
     *
     * <p>挂到当前话题组（parentQuestionId = 所属主问题），追问序号 = 本组已有追问数 + 1，
     * category / topic 继承当前题（不拼「（追问N）」伪技能，P4Q-6），来源标 MODEL_GENERATED
     * 并盖上新的候选代次，便于后台预备结果据代次判过期。未落库前不对外展示。
     */
    private InterviewQuestionDTO buildGeneratedFollowUp(InterviewQuestionDTO answered,
                                                        List<InterviewQuestionDTO> candidates,
                                                        AdaptiveInterviewPolicy.GeneratedFollowUp generated,
                                                        String difficultyPreference,
                                                        Integer currentVersion) {
        String groupId = answered.isFollowUp() ? answered.parentQuestionId() : answered.questionId();
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        int existingInGroup = (int) candidates.stream()
            .filter(InterviewQuestionDTO::isFollowUp)
            .filter(question -> groupId.equals(question.parentQuestionId()))
            .count();
        int newVersion = (currentVersion == null ? 0 : currentVersion) + 1;
        List<String> expectedPoints = generated.expectedPoint() == null
            || generated.expectedPoint().isBlank()
            ? List.of() : List.of(generated.expectedPoint());
        InterviewQuestionDTO built = InterviewQuestionDTO.createFollowUp(
            candidates.size(), generated.question(), answered.type(), answered.category(),
            groupId, existingInGroup + 1, InterviewQuestionDTO.FOLLOW_UP_DEPTH, expectedPoints);
        // 保留当前题的话题归属，使顶栏覆盖与报告按同一话题统计
        built = built.withFocus(answered.category(), answered.topic());
        return built.withCandidateSource(InterviewQuestionDTO.CANDIDATE_SOURCE_MODEL_GENERATED,
            newVersion);
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
    static String turnPayloadHash(String action, String identity, String answer) {
        String raw = action + "|" + identity + "|" + (answer == null ? "" : answer);
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
                                        List<InterviewQuestionDTO> candidates,
                                        List<InterviewTurnDTO> turns,
                                        InterviewQuestionDTO question, String answer,
                                        InterviewPlan plan) {
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(entity.getLlmProvider());
        // P4Q-1：喂进三类参照物——只看一道孤立的题，模型无法判断「他说的是不是自己简历里的事」
        // 「这轮有没有新信息」「这次表现是否超出长期水平」
        // P4-1：历史问答取自**实际轨迹**（turns 已排除当前轮），未问过的候选不会被当成历史
        // P4Q-2 批 2b：覆盖摘要 / 剩余时间与追问预算 / 本轮合法候选都从计划与实际轨迹推导，
        // 与 Java 的硬边界同源；没有计划或候选时留空，由提示词给可读说明而不是编造数据
        return turnEvaluationService.evaluateTurn(chatClient, new TurnEvaluationRequest(
            question,
            answer,
            TurnEvaluationService.resumeSnippetFor(session.getResumeText(), question.category()),
            TurnEvaluationService.recentTurnsFor(turns, question.category()),
            profileBaselineFor(question.category()),
            TurnEvaluationService.coverageSummaryFor(candidates, turns, question, plan),
            TurnEvaluationService.budgetSummaryFor(plan, evaluationRemainingSeconds(entity, plan),
                TurnEvaluationService.remainingFollowUpsFor(candidates, turns, question,
                    questionService.getFollowUpBudget())),
            TurnEvaluationService.legalCandidatesFor(candidates, turns, question)));
    }

    /**
     * 评估时刻的剩余预算（P4Q-2 批 2b）：计划 − 已提交用时 − 当前题已展示的墙钟。
     *
     * <p>此刻本轮模型评估尚未结束，无法预知还要多久；模型等待不扣预算，因此这里**不**把评估耗时
     * 预估进去——喂给模型的剩余时间只会略保守，不会虚高。旧会话没有计划时返回 null，
     * 不编造一个默认预算去影响模型判断。
     */
    private static Integer evaluationRemainingSeconds(InterviewSessionEntity entity, InterviewPlan plan) {
        if (plan == null) {
            return null;
        }
        long elapsedMillis = entity.getQuestionPresentedAt() != null
            ? Duration.between(entity.getQuestionPresentedAt(), LocalDateTime.now()).toMillis()
            : 0;
        long remaining = plan.budgetSeconds() - baseConsumedSeconds(entity) - elapsedMillis / 1000;
        return (int) Math.max(0, remaining);
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
     * 用户调整剩余时间预算（P4Q-2）：当轮生效。
     *
     * <p>调整写进原计划（planned = 已用 + 剩余），数据库与缓存一起更新；
     * 下一轮提交的收束判定立即按新预算执行。
     */
    public void updateBudget(String sessionId, int remainingMinutes) {
        Optional<Integer> planned = persistenceService.updateBudget(sessionId, remainingMinutes);
        if (planned.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
        sessionCache.applyBudgetOverride(sessionId, planned.get(),
            baseConsumedSeconds(persistenceService.findBySessionId(sessionId).orElseThrow()));
    }

    /** 难度枚举合法值（P4Q-3c）：与会话整体难度口径一致 */
    private static final Set<String> VALID_DIFFICULTY = Set.of("junior", "mid", "senior");

    /**
     * 显式难度调整（P4Q-3c）：写本场难度偏好，下一轮选题/生成立即生效，不调模型。
     *
     * <p>只影响本场（不写长期画像）；非法难度直接拒绝，不静默忽略用户指令。
     */
    public void updatePace(String sessionId, String difficulty) {
        String normalized = difficulty == null ? "" : difficulty.trim().toLowerCase();
        if (!VALID_DIFFICULTY.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "难度只能是 junior/mid/senior");
        }
        if (!persistenceService.updateDifficultyPreference(sessionId, normalized)) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
        log.info("会话 {} 调整本场难度为 {}", sessionId, normalized);
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> candidates = InterviewQuestionIdentity.withDerivedIds(
            session.getQuestions(objectMapper));

        InterviewQuestionDTO question = resolveCandidate(candidates, request.questionId(),
            request.questionIndex());
        if (question == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                "暂存的题目不在本场候选内: " + request.questionId());
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // P4-1：草稿写进答案行但**不占发生顺序、不记为已作答**——
        // 它既不属于实际轨迹，也不会被报告评分（候选素材保持只读，不再回写答案）
        try {
            persistenceService.saveDraft(request.sessionId(), question, request.answer());
            persistenceService.updateSessionStatus(request.sessionId(),
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存草稿: 题目{}", request.sessionId(), question.questionId());
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
        String payloadHash = turnPayloadHash(InterviewTurnCommit.ACTION_COMPLETE, "complete", null);

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
                sessionId, normalizedRequestId, payloadHash, baseVersion,
                entity.getCurrentQuestionId(), currentIndex, "{}"));

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
            InterviewQuestionIdentity.withDerivedIds(questions),
            turnsOf(session)
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
        return toDTO(session, evaluateStatus, evaluateError, null);
    }

    /** 带结束原因的转换：结束原因是数据库权威事实，缓存里没有 */
    private InterviewSessionDTO toDTO(CachedSession session, AsyncTaskStatus evaluateStatus,
                                      String evaluateError, String endReason) {
        List<InterviewQuestionDTO> candidates = InterviewQuestionIdentity.withDerivedIds(
            session.getQuestions(objectMapper));
        // P4-1：轨迹是独立字段；只有「从未写过轨迹快照」的缓存（旧缓存 / 刚创建）才回源数据库
        List<InterviewTurnDTO> turns = session.getTurns(objectMapper);
        if (!session.hasTurnsSnapshot()) {
            turns = turnsOf(session);
        }
        boolean active = session.getStatus() == SessionStatus.CREATED
            || session.getStatus() == SessionStatus.IN_PROGRESS;
        // P4Q-2：剩余预算 = 计划时长 − 已用；无计划的旧会话不给假数字
        Integer planned = session.getPlannedDurationMinutes();
        int consumed = session.getConsumedSeconds() != null ? session.getConsumedSeconds() : 0;
        Integer remainingSeconds = planned != null
            ? Math.max(0, planned * 60 - consumed)
            : null;
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            candidates.size(),
            session.getCurrentIndex(),
            active ? session.getCurrentQuestionId() : null,
            currentQuestionOf(session),
            candidates,
            turns,
            session.getStatus(),
            session.getKnowledgeBaseId(),
            session.getInterviewCategory(),
            Boolean.TRUE.equals(session.getAdaptive()),
            evaluateStatus,
            evaluateError,
            session.getResumeSource(),
            session.getResumeVersion(),
            session.getTurnVersion(),
            endReason,
            planned,
            consumed,
            remainingSeconds,
            requiredTopicsOf(session)
        );
    }

    /** 必要覆盖话题（P4Q-2）：缓存里存的是 JSON 数组；解析失败按未声明处理 */
    private List<String> requiredTopicsOf(CachedSession session) {
        String json = session.getRequiredTopicsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JacksonException e) {
            return List.of();
        }
    }
}
