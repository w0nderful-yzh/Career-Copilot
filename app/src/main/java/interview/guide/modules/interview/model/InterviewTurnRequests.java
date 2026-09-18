package interview.guide.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 逐轮提交的 HTTP 入参（P4-9a）。
 *
 * <p>此前这三个接口的 body 是裸 {@code Map<String, Object>}：只有写进代码的字段会被读取，
 * 其余字段（包括要新增的请求标识与预期版本）都会被**静默丢弃**——调用方以为发了，
 * 服务端其实没收到。改成类型化记录后，字段名写错会在编译期/校验期暴露，而不是变成
 * 「幂等看起来没生效」的排查现场。
 */
public final class InterviewTurnRequests {

    private InterviewTurnRequests() {
    }

    /**
     * 提交答案。
     *
     * @param expectedVersion 提交方看到的会话版本（上一次响应或会话读取里的 turnVersion），
     *                        用来拒绝过期请求；为 null 时服务端以数据库当前版本为准
     */
    public record SubmitAnswerBody(
        @NotNull(message = "问题索引不能为空")
        @Min(value = 0, message = "问题索引无效")
        Integer questionIndex,
        String answer,
        String requestId,
        @Min(value = 0, message = "会话版本无效")
        Integer expectedVersion
    ) {}

    /** 跳过当前题（一等动作，与提交共用同一条推进链路） */
    public record SkipBody(
        @NotNull(message = "问题索引不能为空")
        @Min(value = 0, message = "问题索引无效")
        Integer questionIndex,
        String requestId,
        @Min(value = 0, message = "会话版本无效")
        Integer expectedVersion
    ) {}

    /** 提前交卷（结束面试），与逐轮推进遵循同一并发边界 */
    public record CompleteBody(
        String requestId,
        @Min(value = 0, message = "会话版本无效")
        Integer expectedVersion
    ) {}
}
