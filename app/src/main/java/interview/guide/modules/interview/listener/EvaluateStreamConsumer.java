package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.profile.service.InterviewEvidenceExtractor;
import interview.guide.modules.profile.service.SkillProfileAggregator;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {

    private final InterviewSessionRepository sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final InterviewEvidenceExtractor evidenceExtractor;
    private final SkillProfileAggregator profileAggregator;

    public EvaluateStreamConsumer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        InterviewEvidenceExtractor evidenceExtractor,
        SkillProfileAggregator profileAggregator
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.evidenceExtractor = evidenceExtractor;
        this.profileAggregator = profileAggregator;
    }

    /**
     * 评估任务载荷（P4-9a）。
     *
     * @param epoch 触发这次评估时的评估代次；代次低于会话当前值时说明这条触发已被更新的
     *              一次请求取代（例如用户点了「重试报告生成」），应当丢弃而不是再评一遍
     */
    record EvaluatePayload(String sessionId, long epoch) {}

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "evaluate-consumer";
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        long epoch = parseEpoch(data.get(AsyncTaskStreamConstants.FIELD_EVALUATE_EPOCH));
        return new EvaluatePayload(sessionId, epoch);
    }

    /** 旧格式消息（P4-9a 之前入队、尚无代次字段）按 0 处理，行为与改造前一致 */
    private static long parseEpoch(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId() + ", epoch=" + payload.epoch();
    }

    @Override
    protected boolean shouldSkip(EvaluatePayload payload) {
        return sessionRepository.findBySessionId(payload.sessionId())
            .map(session -> session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED
                // 代次落后 = 这条触发已被更新的一次请求取代（P4-9a）
                || (session.getEvaluateEpoch() != null
                    && session.getEvaluateEpoch() > payload.epoch()))
            .orElse(true);
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 原子领取（P4-9a）：只有「代次与消息一致且尚未完成」时才能领到。
     *
     * <p>重复投递的同一代次消息里只有一个会真正执行，因此不会产出两份报告与两批画像证据。
     */
    @Override
    protected boolean tryMarkProcessing(EvaluatePayload payload) {
        return persistenceService.claimEvaluation(payload.sessionId(), payload.epoch());
    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionIdWithResume(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }

        InterviewSessionEntity session = sessionOpt.get();
        List<InterviewQuestionDTO> questions = objectMapper.readValue(
            session.getQuestionsJson(),
            new TypeReference<>() {}
        );

        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 获取 LLM 客户端
        String provider = session.getLlmProvider();
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(chatClient, sessionId, resumeText, questions);
        persistenceService.saveReport(sessionId, report);

        // 评估完成 → 提取逐题评分写入技能画像（失败不影响评估结果本身）
        try {
            List<SkillEvidenceEntity> evidences = evidenceExtractor.extract(sessionId);
            profileAggregator.applyEvidence(evidences);
        } catch (Exception e) {
            log.error("画像证据应用失败（不影响评估结果）: sessionId={}", sessionId, e);
        }
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        String sessionId = payload.sessionId();
        try {
            // 重试必须先把评估状态置回 PENDING：否则原子领取的「尚未完成」判据（此时是
            // PROCESSING）会让重试静默无效，用户只能看到一直停在「评估中」
            updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);

            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_EVALUATE_EPOCH, String.valueOf(payload.epoch()),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("评估任务已重新入队: sessionId={}, epoch={}, retryCount={}", sessionId,
                payload.epoch(), retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("更新评估状态失败: sessionId={}, status={}, error={}", sessionId, status, e.getMessage(), e);
        }
    }

}
