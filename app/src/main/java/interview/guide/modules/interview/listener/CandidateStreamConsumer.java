package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import interview.guide.modules.interview.service.InterviewQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 面试候选预备任务消费者（P4-4b）。
 *
 * <p>在**事务外**为某个主问题补生成追问候选，回写前用乐观代次闸门（candidate_version）确认
 * 用户仍停在投递时的候选代次上；代次落后（已推进 / 换话题 / 已结束）则丢弃过期结果，不覆盖会话。
 * 预备失败不影响实时面试循环。
 */
@Slf4j
@Component
public class CandidateStreamConsumer
        extends AbstractStreamConsumer<CandidateStreamConsumer.CandidatePayload> {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewQuestionService questionService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final CandidateStreamProducer producer;
    private final InterviewQuestionProperties properties;
    private final ObjectMapper objectMapper;

    public CandidateStreamConsumer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        InterviewQuestionService questionService,
        InterviewPersistenceService persistenceService,
        InterviewSessionCache sessionCache,
        CandidateStreamProducer producer,
        InterviewQuestionProperties properties,
        ObjectMapper objectMapper
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.questionService = questionService;
        this.persistenceService = persistenceService;
        this.sessionCache = sessionCache;
        this.producer = producer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    record CandidatePayload(String sessionId, String mainQuestionId, long candidateVersion) {}

    @Override
    protected String taskDisplayName() {
        return "候选预备";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_CANDIDATE_PREP_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_CANDIDATE_PREP_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_CANDIDATE_PREP_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "candidate-prep-consumer";
    }

    @Override
    protected CandidatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        String mainQuestionId = data.get(AsyncTaskStreamConstants.FIELD_MAIN_QUESTION_ID);
        if (sessionId == null || mainQuestionId == null) {
            log.warn("候选预备消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        long version = parseLong(data.get(AsyncTaskStreamConstants.FIELD_CANDIDATE_VERSION));
        return new CandidatePayload(sessionId, mainQuestionId, version);
    }

    private static long parseLong(String raw) {
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
    protected String payloadIdentifier(CandidatePayload payload) {
        return "sessionId=" + payload.sessionId() + ", main=" + payload.mainQuestionId()
            + ", version=" + payload.candidateVersion();
    }

    /** 会话不存在 / 已结束 / 代次落后 → 跳过（过期结果不驱动状态） */
    @Override
    protected boolean shouldSkip(CandidatePayload payload) {
        return readCurrent(payload).isEmpty();
    }

    @Override
    protected void markProcessing(CandidatePayload payload) {
        // 预备任务无独立处理状态，靠回写时的代次闸门保证一次性
    }

    /** 回读当前权威实体；仅当会话进行中且代次与投递一致时返回，否则 empty（判过期/结束） */
    private Optional<InterviewSessionEntity> readCurrent(CandidatePayload payload) {
        return sessionRepository.findBySessionId(payload.sessionId())
            .filter(session -> session.getStatus() == InterviewSessionEntity.SessionStatus.CREATED
                || session.getStatus() == InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            .filter(session -> (session.getCandidateVersion() == null
                ? 0L : session.getCandidateVersion()) == payload.candidateVersion());
    }

    @Override
    protected void processBusiness(CandidatePayload payload) {
        Optional<InterviewSessionEntity> current = readCurrent(payload);
        if (current.isEmpty()) {
            log.info("候选预备任务已过期或会话已结束，丢弃: {}", payloadIdentifier(payload));
            return;
        }
        InterviewSessionEntity entity = current.get();
        try {
            List<InterviewQuestionDTO> candidates = InterviewQuestionIdentity.withDerivedIds(
                objectMapper.readValue(entity.getQuestionsJson(), new TypeReference<>() {}));
            InterviewQuestionDTO main = InterviewQuestionIdentity
                .byId(candidates, payload.mainQuestionId()).orElse(null);
            if (main == null || main.isFollowUp()) {
                return;
            }
            List<InterviewQuestionDTO> group = candidates.stream()
                .filter(InterviewQuestionDTO::isFollowUp)
                .filter(question -> main.questionId().equals(question.parentQuestionId()))
                .toList();
            int capacity = properties.getFollowUpCandidateCount();
            int toGenerate = capacity - group.size();
            if (toGenerate <= 0) {
                return;
            }
            List<InterviewQuestionDTO> produced = questionService.generateFollowUpCandidatesForMain(
                entity.getLlmProvider(), main, entity.getResumeContextText(), toGenerate);
            long newVersion = payload.candidateVersion + 1;
            List<InterviewQuestionDTO> merged = new ArrayList<>(candidates);
            int added = 0;
            for (InterviewQuestionDTO followUp : produced) {
                if (added >= toGenerate || isDuplicate(followUp.question(), merged)) {
                    continue;
                }
                merged.add(followUp.withCandidateSource(
                    InterviewQuestionDTO.CANDIDATE_SOURCE_BACKGROUND, (int) newVersion));
                added++;
            }
            if (added == 0) {
                return;
            }
            List<InterviewQuestionDTO> renumbered = InterviewQuestionIdentity.renumber(merged);
            String json = objectMapper.writeValueAsString(renumbered);
            boolean written = persistenceService.appendBackgroundCandidates(
                payload.sessionId(), payload.candidateVersion(), json, newVersion);
            if (written) {
                sessionCache.updateQuestions(payload.sessionId(), renumbered);
                log.info("后台预备候选已回写: sessionId={}, main={}, 追加={}, 新代次={}",
                    payload.sessionId(), payload.mainQuestionId(), added, newVersion);
            } else {
                log.info("后台预备候选回写被代次闸门拒绝（会话已推进）: {}", payloadIdentifier(payload));
            }
        } catch (Exception e) {
            log.warn("后台预备候选失败（忽略，实时生成兜底）: {}", payloadIdentifier(payload), e);
        }
    }

    /** 题干归一化去重：与已有候选（含本组预置追问）文本相同的生成题不再追加 */
    private static boolean isDuplicate(String question, List<InterviewQuestionDTO> existing) {
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return true;
        }
        return existing.stream().anyMatch(item -> normalize(item.question()).equals(normalized));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    @Override
    protected void markCompleted(CandidatePayload payload) {
        // 无持久完成状态；回写成功即完成
    }

    @Override
    protected void markFailed(CandidatePayload payload, String error) {
        log.warn("后台预备候选最终失败（忽略）: {}, error={}", payloadIdentifier(payload), error);
    }

    @Override
    protected void retryMessage(CandidatePayload payload, int retryCount) {
        producer.sendPrepTask(payload.sessionId(), payload.mainQuestionId(), payload.candidateVersion());
    }
}
