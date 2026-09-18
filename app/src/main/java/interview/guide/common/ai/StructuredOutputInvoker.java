package interview.guide.common.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略。
 *
 * <h3>重试与预算（ARCH-2b）</h3>
 * <ul>
 *   <li><b>重试责任只有这一层</b>：底层重试已显式关闭
 *       （{@code spring.ai.retry.max-attempts=1}），因此这里的 attempts 就是实际模型请求数，
 *       不会出现「日志说 1 次、账单说 3 次」。</li>
 *   <li><b>重试共享一个截止时间</b>：预算按**整个操作**计，解析重试只能用剩余额度；
 *       剩余低于 {@code minAttemptMs} 时不再发起新尝试，避免把用户等待拖成
 *       「预算 + 单次调用耗时」。</li>
 *   <li><b>失败可分类</b>：TIMEOUT / PARSE_FAILED / UPSTREAM 分别记录，降级原因不再是一句
 *       「模型不可用」。</li>
 * </ul>
 *
 * <p>预算用「提交到虚拟线程 + 超时收敛」实现：Spring AI 的阻塞式调用无法被打断，
 * 超时后我们只是**不再等待**（被放弃的调用会随 HTTP 超时自行结束）。虚拟线程按需创建，
 * 因此被放弃的调用不会占满线程池而拖垮其他请求——这是不用固定线程池的原因。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    private static final String TAG_KIND_NONE = "none";
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    /** 失败分类：让「超时 / 不合契约 / 上游报错」在日志与指标里区分开 */
    private enum StructuredErrorKind {
        TIMEOUT, PARSE_FAILED, UPSTREAM;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final boolean schemaValidationEnabled;
    private final MeterRegistry meterRegistry;
    /** 未显式传策略时的默认策略（后台档，保持既有行为） */
    private final StructuredCallPolicy defaultPolicy;
    /** 预算收敛用的执行器：虚拟线程按需创建，被放弃的调用不会占住池子 */
    private final ExecutorService budgetExecutor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("structured-llm-", 0).factory());

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.meterRegistry = meterRegistry;
        this.defaultPolicy = StructuredCallPolicy.background(properties);
    }

    @PreDestroy
    void shutdown() {
        budgetExecutor.shutdown();
    }

    /**
     * 结构化调用（后台策略：尝试次数来自全局配置，默认不受预算约束）。
     */
    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, outputConverter, errorCode,
            errorPrefix, logContext, log, defaultPolicy);
    }

    /**
     * 结构化调用（显式策略）。
     *
     * <p>实时调用请传 {@link StructuredCallPolicy#realtime}：预算覆盖整个操作（含解析重试），
     * 让最坏等待可控、降级原因可解释。
     */
    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log,
        StructuredCallPolicy policy
    ) {
        long startNanos = System.nanoTime();
        long deadlineNanos = policy.budgeted()
            ? startNanos + Duration.ofMillis(policy.budgetMs()).toNanos()
            : Long.MAX_VALUE;
        String contextTag = normalizeContextTag(logContext);
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        StructuredErrorKind lastKind = StructuredErrorKind.UPSTREAM;
        int attempt = 0;
        boolean budgetExhausted = false;

        while (attempt < policy.maxAttempts()) {
            if (attempt > 0 && remainMs(deadlineNanos) < policy.minAttemptMs()) {
                // 预算不足以完成一次有意义的调用：停在解析失败上，并标记「是没时间了」
                budgetExhausted = true;
                log.warn("{}剩余预算不足，放弃重试: attempts={}, remainingMs={}, {}",
                    logContext, attempt, Math.max(0, remainMs(deadlineNanos)),
                    policy.describe());
                break;
            }
            attempt++;
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                T result = callWithinBudget(
                    () -> callStructuredOutput(chatClient, attemptSystemPrompt, userPrompt,
                        outputConverter, logContext, log),
                    policy, remainMs(deadlineNanos));
                recordAttempt(contextTag, STATUS_SUCCESS, null);
                recordInvocation(contextTag, STATUS_SUCCESS, null, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                lastKind = classify(e);
                recordAttempt(contextTag, STATUS_FAILURE, lastKind);
                String remaining = policy.budgeted()
                    ? Math.max(0, remainMs(deadlineNanos)) + "ms"
                    : "unbounded";
                if (attempt < policy.maxAttempts()) {
                    log.warn("{}结构化调用失败，准备重试: attempt={}/{}, kind={}, remaining={}, error={}",
                        logContext, attempt, policy.maxAttempts(), lastKind, remaining,
                        rootCause(e).getMessage());
                } else {
                    log.error("{}结构化调用失败，已达尝试上限: attempts={}, kind={}, remaining={}, error={}",
                        logContext, attempt, lastKind, remaining, rootCause(e).getMessage());
                }
            }
        }

        StructuredErrorKind finalKind = budgetExhausted ? StructuredErrorKind.TIMEOUT : lastKind;
        recordInvocation(contextTag, STATUS_FAILURE, finalKind, startNanos);
        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? rootCause(lastError).getMessage() : "unknown")
                + (budgetExhausted ? "（实时预算已用尽）" : "")
                + "[" + finalKind.tag() + "]"
        );
    }

    /** 预算内执行一次调用；不受预算约束时直接同步执行（后台路径行为不变） */
    private <T> T callWithinBudget(Supplier<T> call, StructuredCallPolicy policy, long remainingMs) {
        if (!policy.budgeted()) {
            return call.get();
        }
        if (remainingMs <= 0) {
            throw new CompletionException(new TimeoutException("实时预算已用尽"));
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call, budgetExecutor);
        try {
            return future.orTimeout(remainingMs, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            // 不再等待被放弃的调用；它会随 HTTP 超时自行结束（阻塞式调用无法打断）
            future.cancel(true);
            throw e;
        }
    }

    private long remainMs(long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private <T> T callStructuredOutput(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        var call = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call();
        if (schemaValidationEnabled) {
            return call.entity(outputConverter, spec -> spec.validateSchema());
        }
        String content = call.content();
        return convertWithRepair(content, outputConverter, logContext, log);
    }

    private <T> T convertWithRepair(
        String content,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 失败分类：按**根因类型**判定，而不是靠错误文案。
     *
     * <p>超时（我们自己的预算收敛或上游读超时）与解析失败（JSON 不合契约）必须分开：
     * 前者说明「要不到结果」，后者说明「要到了但没法用」，降级策略不同。
     */
    private static StructuredErrorKind classify(Exception error) {
        Throwable cause = rootCause(error);
        if (cause instanceof TimeoutException
            || cause instanceof SocketTimeoutException
            || cause instanceof HttpTimeoutException) {
            return StructuredErrorKind.TIMEOUT;
        }
        if (cause instanceof ConversionException
            || cause instanceof JacksonException
            || cause instanceof IllegalArgumentException) {
            return StructuredErrorKind.PARSE_FAILED;
        }
        return StructuredErrorKind.UPSTREAM;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(rootCause(lastError).getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    private void recordAttempt(String contextTag, String status, StructuredErrorKind kind) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status, "kind", tagOf(kind))
        ).increment();
    }

    private void recordInvocation(String contextTag, String status, StructuredErrorKind kind,
                                  long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status, "kind", tagOf(kind));
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private static String tagOf(StructuredErrorKind kind) {
        return kind == null ? TAG_KIND_NONE : kind.tag();
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
