package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 面试候选预备任务生产者（P4-4b）。
 *
 * <p>「为某个主问题预备更多追问候选」的后台任务：投递时携带当时的 candidate_version，
 * 消费回写前比对代次，用户已换话题/结束/已推进时旧任务直接失效。预备是尽力而为，
 * 投递与执行失败都不影响实时面试循环（实时路径由同轮受限生成兜底）。
 */
@Slf4j
@Component
public class CandidateStreamProducer
        extends AbstractStreamProducer<CandidateStreamProducer.CandidateTask> {

    /**
     * @param mainQuestionId 目标主问题标识（生成追问挂到它的话题组）
     * @param candidateVersion 投递时的候选代次；消费据此判过期
     */
    public record CandidateTask(String sessionId, String mainQuestionId, long candidateVersion) {}

    public CandidateStreamProducer(RedisService redisService) {
        super(redisService);
    }

    /** 投递一个预备候选任务；调用方已确定主问题与当代次 */
    public void sendPrepTask(String sessionId, String mainQuestionId, long candidateVersion) {
        sendTask(new CandidateTask(sessionId, mainQuestionId, candidateVersion));
    }

    @Override
    protected String taskDisplayName() {
        return "候选预备";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_CANDIDATE_PREP_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(CandidateTask task) {
        Map<String, String> fields = new HashMap<>();
        fields.put(AsyncTaskStreamConstants.FIELD_SESSION_ID, task.sessionId());
        fields.put(AsyncTaskStreamConstants.FIELD_MAIN_QUESTION_ID, task.mainQuestionId());
        fields.put(AsyncTaskStreamConstants.FIELD_CANDIDATE_VERSION,
            String.valueOf(task.candidateVersion()));
        fields.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
        return fields;
    }

    @Override
    protected String payloadIdentifier(CandidateTask task) {
        return "sessionId=" + task.sessionId() + ", main=" + task.mainQuestionId()
            + ", version=" + task.candidateVersion();
    }

    @Override
    protected void onSendFailed(CandidateTask task, String error) {
        // 预备任务丢失不影响业务：下一轮若仍需要，实时受限生成会兜底；仅记录
        log.warn("候选预备任务入队失败（忽略，实时生成兜底）: {}", payloadIdentifier(task));
    }
}
