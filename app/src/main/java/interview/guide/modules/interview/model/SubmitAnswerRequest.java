package interview.guide.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交答案请求
 *
 * @param questionId      待答题的**稳定标识**（P4-1）：提交与推进都基于它，而不是数组下标
 * @param requestId       请求标识（P4-9a）：同一次提交重试/重发必须复用同一个标识，
 *                        服务端据此返回原结果而不是再次推进。缺省（null）表示调用方未提供，
 *                        此时退化为「只做并发闸门」，不做重放保护。
 * @param expectedVersion 预期会话版本（P4-9a）：来自上一次响应或会话读取的 turnVersion。
 *                        缺省时服务端用数据库里的当前版本，仍然保留索引与状态的并发闸门。
 */
public record SubmitAnswerRequest(
    @NotBlank(message = "会话ID不能为空")
    String sessionId,

    /**
     * 题目标识（P4-1）：来自会话读取的 currentQuestionId。
     *
     * <p>为 null 时退回 {@link #questionIndex}（旧调用方兼容；新前端只传标识）。
     */
    String questionId,

    /** 候选池内顺序：仅作旧调用方兼容与展示，不再决定路径 */
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,

    @NotBlank(message = "答案不能为空")
    String answer,

    /** 请求标识（8-64 位字母数字/下划线/中划线），与创建面试的标识规则一致 */
    String requestId,

    @Min(value = 0, message = "会话版本无效")
    Integer expectedVersion
) {

    /** 兼容不带请求标识与版本的调用点（历史调用与单元测试） */
    public SubmitAnswerRequest(String sessionId, Integer questionIndex, String answer) {
        this(sessionId, null, questionIndex, answer, null, null);
    }

    /** 兼容按题目标识提交的调用点 */
    public SubmitAnswerRequest(String sessionId, String questionId, String answer) {
        this(sessionId, questionId, null, answer, null, null);
    }
}
