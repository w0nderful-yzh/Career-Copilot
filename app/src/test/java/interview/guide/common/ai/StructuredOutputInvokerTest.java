package interview.guide.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * 结构化调用的实时预算与失败分类（ARCH-2b）。
 *
 * <p>要守住的三件事：
 * <ol>
 *   <li>重试共享一个截止时间——剩余预算不足时**不再发起**新尝试，最坏等待不翻倍；</li>
 *   <li>失败有分类（超时 / 不合契约 / 上游），降级原因不是一句「模型不可用」；</li>
 *   <li>后台档不受预算约束（行为与改造前一致），避免收口把异步任务也拖下水。</li>
 * </ol>
 */
@DisplayName("结构化调用预算与失败分类（ARCH-2b）")
class StructuredOutputInvokerTest {

    public record Draft(String direction) {}

    private static final BeanOutputConverter<Draft> CONVERTER = new BeanOutputConverter<>(Draft.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** 走本地解析路径（schema validation 关闭），这样才能精确断言「解析失败」这一分类 */
    private static StructuredOutputProperties properties(long budgetMs, int maxAttempts,
                                                        long minAttemptMs) {
        StructuredOutputProperties properties = new StructuredOutputProperties();
        properties.setStructuredSchemaValidationEnabled(false);
        properties.setStructuredRealtimeBudgetMs(budgetMs);
        properties.setStructuredRealtimeMaxAttempts(maxAttempts);
        properties.setStructuredRealtimeMinAttemptMs(minAttemptMs);
        return properties;
    }

    /**
     * 按脚本作答的客户端。
     *
     * <p>脚本项两种形式：{@code sleep:毫秒|内容}（先等待再返回该内容）与 {@code 内容}。
     * 等待用来模拟「一次调用吃掉大半预算」，是验证预算共享的关键道具。
     */
    private static final class ScriptedClient {
        private final ChatClient client;
        private int calls;

        ScriptedClient(String... contents) {
            this.client = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
            when(client.prompt()).thenReturn(spec);
            when(spec.system(anyString())).thenReturn(spec);
            when(spec.user(anyString())).thenReturn(spec);
            when(spec.call()).thenReturn(responseSpec);
            when(responseSpec.content()).thenAnswer(invocation -> {
                int index = Math.min(calls, contents.length - 1);
                calls++;
                String item = contents[index];
                if (!item.startsWith("sleep:")) {
                    return item;
                }
                String[] parts = item.substring("sleep:".length()).split("\\|", 2);
                Thread.sleep(Long.parseLong(parts[0]));
                return parts.length > 1 ? parts[1] : "{\"direction\": \"late\"}";
            });
        }

        ChatClient client() {
            return client;
        }

        /** 实际发出的模型请求数：断言「没有隐藏重试 / 没有多余尝试」 */
        int calls() {
            return calls;
        }
    }

    private StructuredCallPolicy realtime(long budgetMs, int maxAttempts, long minAttemptMs) {
        return StructuredCallPolicy.realtime(properties(budgetMs, maxAttempts, minAttemptMs));
    }

    private Draft invoke(ChatClient client, int maxAttempts, long budgetMs, long minAttemptMs) {
        StructuredOutputInvoker invoker =
            new StructuredOutputInvoker(properties(budgetMs, maxAttempts, minAttemptMs), meterRegistry);
        return invoker.invoke(client, "系统", "用户", CONVERTER,
            ErrorCode.INTERVIEW_EVALUATION_FAILED, "失败：", "测试", LoggerFactory.getLogger(getClass()),
            realtime(budgetMs, maxAttempts, minAttemptMs));
    }

    @Test
    @DisplayName("解析失败在预算内重试一次即成功")
    void parseFailureRetriesWithinBudget() {
        ScriptedClient scripted = new ScriptedClient("这不是 JSON", "{\"direction\": \"java-backend\"}");

        Draft result = invoke(scripted.client(), 2, 5000, 1200);

        assertThat(result.direction()).isEqualTo("java-backend");
        assertThat(scripted.calls()).isEqualTo(2);
        assertThat(meterRegistry.get("app.ai.structured_output.attempts")
            .tag("status", "failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("app.ai.structured_output.invocations")
            .tag("status", "success").tag("kind", "none").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("首次尝试吃掉大部分预算时不再重试：最坏等待不翻倍")
    void budgetExhaustedStopsRetrying() {
        ScriptedClient scripted = new ScriptedClient("sleep:900|不是 JSON", "{\"direction\": \"java\"}");

        assertThatThrownBy(() -> invoke(scripted.client(), 2, 1500, 1200))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("实时预算已用尽")
            .hasMessageContaining("[timeout]");

        assertThat(scripted.calls())
            .as("剩余预算不足以再做一次有意义的尝试，就不该再打一次模型")
            .isEqualTo(1);
        assertThat(meterRegistry.get("app.ai.structured_output.invocations")
            .tag("kind", "timeout").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("超出预算的调用按 TIMEOUT 收敛，而不是一直等 HTTP 超时")
    void timeoutIsClassified() {
        ScriptedClient scripted = new ScriptedClient("sleep:3000");

        long start = System.nanoTime();
        assertThatThrownBy(() -> invoke(scripted.client(), 2, 300, 100))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("[timeout]");

        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs)
            .as("等待必须收敛在预算附近，不能跟着 HTTP 超时走")
            .isLessThan(2000);
    }

    @Test
    @DisplayName("解析始终不合契约 → 分类为 parse_failed 且用满尝试次数")
    void persistentParseFailureIsClassified() {
        ScriptedClient scripted = new ScriptedClient("一直不是 JSON");

        assertThatThrownBy(() -> invoke(scripted.client(), 2, 5000, 1200))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("[parse_failed]");

        assertThat(scripted.calls()).isEqualTo(2);
        assertThat(meterRegistry.get("app.ai.structured_output.attempts")
            .tag("status", "failure").tag("kind", "parse_failed").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("后台档不受预算约束：异步任务的行为与改造前一致")
    void backgroundPolicyIsNotBudgeted() {
        StructuredOutputProperties properties = properties(0, 2, 0);
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, meterRegistry);
        ScriptedClient scripted = new ScriptedClient("sleep:400|{\"direction\": \"report\"}");

        // 旧签名（后台策略）：没有预算，不因「慢」而被收敛
        Draft result = invoker.invoke(scripted.client(), "系统", "用户", CONVERTER,
            ErrorCode.INTERVIEW_EVALUATION_FAILED, "失败：", "后台任务", LoggerFactory.getLogger(getClass()));

        assertThat(result.direction()).isEqualTo("report");
        assertThat(StructuredCallPolicy.background(properties).budgeted()).isFalse();
    }
}
