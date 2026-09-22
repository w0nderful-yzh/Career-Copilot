package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到 Redis Stream
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<EvaluateStreamProducer.EvaluateTask> {

    /**
     * 评估任务载荷（P4-9a）。
     *
     * <p>带上评估代次：消息可能因为重试、重复投递或用户重试而出现多条，消费端据此
     * 判定「这条触发是不是已经过期」，避免同一场面试产出两份报告与两批画像证据。
     */
    public record EvaluateTask(String sessionId, long epoch) {}

    private final InterviewSessionRepository sessionRepository;
    private final TransactionalExecutor transactionalExecutor;

    public EvaluateStreamProducer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * @param sessionId 面试会话ID
     * @param epoch     评估代次（与数据库中的 evaluate_epoch 对应）
     */
    public void sendEvaluateTask(String sessionId, long epoch) {
        sendTask(new EvaluateTask(sessionId, epoch));
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(EvaluateTask task) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, task.sessionId(),
            AsyncTaskStreamConstants.FIELD_EVALUATE_EPOCH, String.valueOf(task.epoch()),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(EvaluateTask task) {
        return "sessionId=" + task.sessionId() + ", epoch=" + task.epoch();
    }

    @Override
    protected void onSendFailed(EvaluateTask task, String error) {
        transactionalExecutor.runRequiresNew(
            () -> updateEvaluateStatus(task.sessionId(), AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            sessionRepository.save(session);
        });
    }
}
