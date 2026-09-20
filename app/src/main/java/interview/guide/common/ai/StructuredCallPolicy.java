package interview.guide.common.ai;

/**
 * 结构化调用策略（ARCH-2b）：一次操作的尝试次数与预算。
 *
 * <p>为什么要有这个对象：预算不是「每次尝试的超时」，而是**整个操作**的截止时间——
 * 解析重试共享它。否则「最多重试 2 次 × 每次 30 秒」会让用户等 60 秒，
 * 而排查时看到的只是「一次调用失败了」。
 *
 * @param maxAttempts  尝试上限（含首次）
 * @param budgetMs     整个操作的预算（毫秒）；<= 0 表示不设上限（由 HTTP 超时兜底）
 * @param minAttemptMs 剩余预算低于该值就不再发起新尝试
 * @param realtime     是否为用户正在等待的实时调用；与是否配置预算是两个独立维度
 */
public record StructuredCallPolicy(int maxAttempts, long budgetMs, long minAttemptMs, boolean realtime) {

    /** 实时档：用户正在等待（面试逐轮评估、意图分类）——预算收紧、降级要快。 */
    public static StructuredCallPolicy realtime(StructuredOutputProperties properties) {
        return new StructuredCallPolicy(
            Math.max(1, properties.getStructuredRealtimeMaxAttempts()),
            properties.getStructuredRealtimeBudgetMs(),
            Math.max(0, properties.getStructuredRealtimeMinAttemptMs()),
            true);
    }

    /** 后台档：报告、优化提案这类异步任务——默认不设上限，保持既有行为。 */
    public static StructuredCallPolicy background(StructuredOutputProperties properties) {
        return new StructuredCallPolicy(
            Math.max(1, properties.getStructuredMaxAttempts()),
            properties.getStructuredBackgroundBudgetMs(),
            0,
            false);
    }

    /** 是否受预算约束 */
    public boolean budgeted() {
        return budgetMs > 0;
    }

    /** 日志用：把本次策略的边界写清楚，排查时不必回头翻配置 */
    public String describe() {
        return budgeted()
            ? "attempts=" + maxAttempts + ", budgetMs=" + budgetMs + ", minAttemptMs=" + minAttemptMs
            : "attempts=" + maxAttempts + ", budget=unbounded";
    }
}
