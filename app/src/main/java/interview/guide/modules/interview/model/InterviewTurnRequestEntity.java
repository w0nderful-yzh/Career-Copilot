package interview.guide.modules.interview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 逐轮提交幂等记录（P4-9a）。
 *
 * <p>一行代表「某个请求标识已经处理过，结果是这个」。重放同一标识时直接返回
 * {@code responseJson}，既不再次推进会话，也不再花一次模型调用；同一标识若换了载荷
 * （题目索引 / 作答内容 / 动作任一不同），用 {@code payloadHash} 判定为冲突并拒绝。
 *
 * <p>唯一索引 {@code (session_id, request_id)} 同时是并发重复提交的闸门：两个请求同时
 * 提交同一标识时，只有先提交的那个能落库。
 */
@Entity
@Table(name = "interview_turn_requests")
public class InterviewTurnRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 动作：ANSWER / SKIP / COMPLETE */
    @Column(nullable = false, length = 16)
    private String action;

    /** 载荷指纹（动作 + 题号 + 作答内容的 SHA-256），用于识别「同一标识不同载荷」 */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    /** 该请求基于的会话版本 */
    @Column(name = "base_version", nullable = false)
    private Integer baseVersion;

    /** 处理完成后的会话版本（= baseVersion + 1） */
    @Column(name = "result_version", nullable = false)
    private Integer resultVersion;

    /** 原结果（SubmitAnswerResponse 等响应的序列化），重放时原样返回 */
    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public Integer getResultVersion() {
        return resultVersion;
    }

    public void setResultVersion(Integer resultVersion) {
        this.resultVersion = resultVersion;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
