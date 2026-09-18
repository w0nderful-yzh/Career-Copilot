package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    private int structuredMaxAttempts = 2;
    private boolean structuredIncludeLastError = true;
    private boolean structuredRetryUseRepairPrompt = true;
    private boolean structuredRetryAppendStrictJsonInstruction = true;
    private int structuredErrorMessageMaxLength = 200;
    private boolean structuredMetricsEnabled = true;
    private boolean structuredSchemaValidationEnabled = true;

    /**
     * 后台档预算（毫秒）：结构化调用的**整个操作**（含解析重试）共享的截止时间。
     *
     * <p>默认 0 = 不限制，由 HTTP 超时兜底——后台任务（报告、优化提案）本来就允许慢，
     * 不强制收紧；需要上限时打开这个开关即可。
     */
    private long structuredBackgroundBudgetMs = 0;

    /**
     * 实时档预算（毫秒）：用户正在等待的调用（面试逐轮评估、意图分类）必须在有限时间内
     * 要么给出结果、要么明确降级。默认 8 秒——逐轮评估的目标是 P95 ≤ 3s，
     * 留出余量给一次解析修复重试，同时保证最坏情况可控。
     */
    private long structuredRealtimeBudgetMs = 8000;

    /** 实时档尝试上限（含首次）。解析失败才重试，且重试吃同一个截止时间。 */
    private int structuredRealtimeMaxAttempts = 2;

    /** 剩余预算低于该值就不再发起新尝试：否则最后一次尝试会把等待拖成「预算 + 单次耗时」。 */
    private long structuredRealtimeMinAttemptMs = 1200;
}
