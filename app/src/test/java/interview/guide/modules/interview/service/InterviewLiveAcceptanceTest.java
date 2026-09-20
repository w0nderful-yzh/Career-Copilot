package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.support.LocalDatabaseGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P4-9b 真实模型联合验收。
 *
 * <p>默认测试套件不会运行本类；只有显式设置 {@code P4_LIVE_ACCEPTANCE=true} 才会连接本地
 * PostgreSQL / Redis 与真实模型。每个样例使用相同简历和候选池，从 HTTP 提交答案一直走到
 * 模型评估、Java 决策、事务落库和下一题响应，并在结束后删除播种会话。
 *
 * <p>运行两次即可分别得到正常与故障报告：
 * <pre>
 * P4_LIVE_ACCEPTANCE=true P4_LIVE_MODE=normal ./gradlew :app:test --tests '*InterviewLiveAcceptanceTest*' --rerun-tasks
 * P4_LIVE_ACCEPTANCE=true P4_LIVE_MODE=failure ./gradlew :app:test --tests '*InterviewLiveAcceptanceTest*' --rerun-tasks
 * </pre>
 * 故障模式把逐轮预算压到 100ms，真实请求仍会发出，但等待会按预算收敛并走 UNKNOWN 降级；
 * 这比替换成 mock 更能验证实际 HTTP 客户端与超时边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf(value = "interview.guide.modules.interview.service.InterviewLiveAcceptanceTest#enabled",
    disabledReason = "仅在显式启用 P4_LIVE_ACCEPTANCE 且本地依赖与模型密钥可用时运行")
@DisplayName("P4-9b 真实模型质量与响应速度联合验收")
class InterviewLiveAcceptanceTest {

  private static final String MODE_NORMAL = "normal";
  private static final String MODE_FAILURE = "failure";
  private static final int DEFAULT_SAMPLE_COUNT = 20;
  private static final long NORMAL_P95_TARGET_MS = 3_000;
  private static final double NORMAL_QUALITY_TARGET = 0.90;
  private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
  private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
  private static final String METRIC_CONTEXT = "turn_evaluation";

  private static final String FIXED_RESUME = """
      ## 求职意向
      Java 后端实习生

      ## 项目经历
      负责订单服务与 Redis 缓存一致性治理；通过请求日志和压测定位过回填乱序，
      使用版本号校验避免旧值覆盖，并补充了并发回归测试。
      """;

  private static final String INCOMPLETE_ANSWER =
      "堆用于保存对象实例，由多个线程共享；垃圾回收主要管理这部分内存。";
  private static final String COMPLETE_ANSWER =
      "堆保存对象实例并由线程共享；虚拟机栈是线程私有的，每次方法调用创建栈帧，"
          + "栈帧里保存局部变量表、操作数栈、动态链接和返回地址。";

  @LocalServerPort
  private int port;

  @Autowired
  private InterviewPersistenceService persistenceService;
  @Autowired
  private InterviewSessionCache sessionCache;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private MeterRegistry meterRegistry;
  @Autowired
  private LlmProviderRepository providerRepository;
  @Autowired
  private ApiKeyEncryptionService encryptionService;

  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();
  private final List<String> seededSessionIds = new ArrayList<>();
  private String ephemeralProviderId;

  static boolean enabled() {
    if (!"true".equalsIgnoreCase(System.getenv("P4_LIVE_ACCEPTANCE"))
        || !LocalDatabaseGate.available()) {
      return false;
    }
    if (configuredProvider() != null) {
      return true;
    }
    String apiKey = valueOf("AI_BAILIAN_API_KEY");
    return apiKey != null && !apiKey.isBlank();
  }

  @DynamicPropertySource
  static void liveProperties(DynamicPropertyRegistry registry) {
    if (LocalDatabaseGate.DOTENV != null) {
      LocalDatabaseGate.DOTENV.forEach((key, value) -> registry.add(key, () -> value));
    }
    if (LocalDatabaseGate.DATASOURCE != null) {
      registry.add("spring.datasource.url", LocalDatabaseGate::url);
      registry.add("spring.datasource.username", LocalDatabaseGate::username);
      registry.add("spring.datasource.password", LocalDatabaseGate::password);
    }
    // 验收只测逐轮同步路径，关闭后台补题，避免异步调用污染实际请求数。
    registry.add("app.interview.background-prep-threshold", () -> 0);
    if (MODE_FAILURE.equals(mode())) {
      registry.add("app.ai.structured-realtime-budget-ms", () -> 100);
      registry.add("app.ai.structured-realtime-min-attempt-ms", () -> 80);
    }
  }

  @AfterEach
  void cleanSeededSessions() {
    for (String sessionId : seededSessionIds) {
      try {
        persistenceService.deleteSessionBySessionId(sessionId);
      } catch (Exception ignored) {
        // 验收断言失败也尽量清理；会话可能已由前面的清理删除。
      }
    }
    seededSessionIds.clear();
    if (ephemeralProviderId != null) {
      providerRepository.deleteById(ephemeralProviderId);
    }
  }

  @BeforeEach
  void prepareEphemeralProvider() {
    if (configuredProvider() != null) {
      return;
    }
    String apiKey = valueOf("AI_BAILIAN_API_KEY");
    ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
    ephemeralProviderId = "p4-live-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    providerRepository.save(LlmProviderEntity.builder()
        .id(ephemeralProviderId)
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKeyNonce(encrypted.nonce())
        .apiKeyCiphertext(encrypted.ciphertext())
        .model(valueOf("AI_MODEL") != null ? valueOf("AI_MODEL") : "qwen3.5-flash")
        .supportsEmbedding(false)
        .temperature(0.2)
        .enabled(true)
        .builtin(false)
        .build());
  }

  @Test
  @DisplayName("固定简历与成对回答统计质量、P95、调用次数和降级比例")
  void measuresQualityLatencyCallsAndDegradation() throws Exception {
    MetricSnapshot before = metricSnapshot();
    List<Sample> samples = new ArrayList<>();

    int sampleCount = sampleCount();
    for (int index = 0; index < sampleCount; index++) {
      boolean complete = index % 2 == 1;
      SeededSession session = seedSession(index);
      samples.add(submit(session, complete ? COMPLETE_ANSWER : INCOMPLETE_ANSWER,
          complete ? "complete" : "incomplete"));
    }

    MetricSnapshot after = metricSnapshot();
    long p95Ms = percentile95(samples.stream().map(Sample::latencyMs).toList());
    long degraded = samples.stream().filter(Sample::degraded).count();
    long qualityPassed = samples.stream().filter(Sample::qualityPassed).count();
    double degradationRatio = ratio(degraded, sampleCount);
    double qualityPassRate = ratio(qualityPassed, sampleCount);
    double attempts = after.attempts() - before.attempts();
    double successfulInvocations = after.successfulInvocations() - before.successfulInvocations();
    double failedInvocations = after.failedInvocations() - before.failedInvocations();

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("mode", mode());
    report.put("measuredAt", Instant.now().toString());
    report.put("provider", selectedProvider());
    report.put("model", providerRepository.findById(selectedProvider())
        .map(LlmProviderEntity::getModel).orElse(null));
    report.put("sampleCount", sampleCount);
    report.put("p95Ms", p95Ms);
    report.put("latencyTargetMs", NORMAL_P95_TARGET_MS);
    report.put("latencyTargetMet", p95Ms <= NORMAL_P95_TARGET_MS);
    report.put("actualModelRequests", (long) attempts);
    report.put("successfulInvocations", (long) successfulInvocations);
    report.put("failedInvocations", (long) failedInvocations);
    report.put("degradationRatio", degradationRatio);
    report.put("qualityPassRate", qualityPassRate);
    report.put("samples", samples);
    writeReport(report);

    if (MODE_NORMAL.equals(mode())) {
      assertThat(degradationRatio).as("正常模型不应靠降级换取表面延迟").isZero();
      assertThat(qualityPassRate).as("固定成对回答的路线质量通过率").isGreaterThanOrEqualTo(NORMAL_QUALITY_TARGET);
      assertThat(attempts).as("每个样例只应发起一次实际模型请求").isEqualTo(sampleCount);
      assertThat(successfulInvocations).as("每个样例都应得到一次可用评估").isEqualTo(sampleCount);
    } else {
      assertThat(degradationRatio).as("100ms 故障预算下每轮都应走可解释降级").isEqualTo(1.0);
      assertThat(failedInvocations).as("每个故障样例都应记录失败调用").isEqualTo(sampleCount);
      assertThat(attempts).as("预算耗尽后不应隐藏重试").isEqualTo(sampleCount);
      assertThat(samples).allSatisfy(sample ->
          assertThat(sample.nextQuestionId()).isEqualTo("p4-live-redis"));
    }
  }

  private SeededSession seedSession(int index) {
    String sessionId = "p4live" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    List<InterviewQuestionDTO> candidates = fixedCandidates();
    InterviewResumeContext resumeContext = InterviewResumeContext.explicitText(FIXED_RESUME);
    InterviewPlan plan = new InterviewPlan(20, List.of("JVM", "Redis"));

    persistenceService.saveSession(sessionId, null, candidates.size(), candidates,
        selectedProvider(), "java-backend", "mid", true, resumeContext, plan);
    sessionCache.saveSession(sessionId, FIXED_RESUME, null, null, null,
        candidates, 0, SessionStatus.CREATED, true, resumeContext.source().name(), null);
    sessionCache.applyPlan(sessionId, plan);
    seededSessionIds.add(sessionId);
    return new SeededSession(sessionId, candidates.getFirst().questionId(), index);
  }

  private static List<InterviewQuestionDTO> fixedCandidates() {
    InterviewQuestionDTO jvm = InterviewQuestionDTO.createMain(
        0,
        "请说明 Java 堆与虚拟机栈分别保存什么，以及它们在线程共享边界上的区别。",
        "JVM", "JVM", "运行时数据区", 3,
        List.of("堆保存对象且线程共享", "虚拟机栈保存栈帧且线程私有"))
        .withQuestionId("p4-live-jvm");
    InterviewQuestionDTO stackFollowUp = InterviewQuestionDTO.createFollowUp(
        1,
        "你还没有说明虚拟机栈：它保存什么，为什么说它是线程私有的？",
        "JVM", "JVM", jvm.questionId(), 1, InterviewQuestionDTO.FOLLOW_UP_CLARIFICATION,
        List.of("虚拟机栈保存栈帧且线程私有"))
        .withQuestionId("p4-live-stack");
    InterviewQuestionDTO redis = InterviewQuestionDTO.createMain(
        2, "Redis 的 RDB 与 AOF 各有什么取舍？", "REDIS", "Redis", "持久化", 3,
        List.of("RDB", "AOF", "恢复速度与数据完整性取舍"))
        .withQuestionId("p4-live-redis");
    InterviewQuestionDTO redisFollowUp = InterviewQuestionDTO.createFollowUp(
        3, "AOF 重写期间如何保证新写命令不丢失？", "REDIS", "Redis", redis.questionId(),
        1, InterviewQuestionDTO.FOLLOW_UP_SCENARIO, List.of("AOF 重写缓冲区"))
        .withQuestionId("p4-live-redis-follow");
    return List.of(jvm, stackFollowUp, redis, redisFollowUp);
  }

  private Sample submit(SeededSession session, String answer, String caseName) throws Exception {
    String requestId = "p4live-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String payload = objectMapper.writeValueAsString(Map.of(
        "questionId", session.questionId(),
        "answer", answer,
        "requestId", requestId,
        "expectedVersion", 0
    ));
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + "/api/interview/sessions/"
            + session.sessionId() + "/answers"))
        .timeout(Duration.ofSeconds(12))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        .build();

    long started = System.nanoTime();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body.path("code").asInt()).as(response.body()).isEqualTo(200);

    String nextQuestionId = body.path("data").path("nextQuestion").path("questionId").asText();
    InterviewTurnDTO turn = persistenceService.findTurnsBySessionId(session.sessionId()).getFirst();
    boolean degraded = turn.decisionReason() != null
        && turn.decisionReason().contains("评估不可用");
    boolean completeCase = "complete".equals(caseName);
    String expectedNext = completeCase ? "p4-live-redis" : "p4-live-stack";
    boolean qualityPassed = nextQuestionId.equals(expectedNext);
    return new Sample(session.ordinal(), caseName, latencyMs, nextQuestionId,
        turn.decisionReason(), degraded, qualityPassed);
  }

  private MetricSnapshot metricSnapshot() {
    return new MetricSnapshot(
        count(METRIC_ATTEMPTS, null),
        count(METRIC_INVOCATIONS, "success"),
        count(METRIC_INVOCATIONS, "failure")
    );
  }

  private double count(String metricName, String status) {
    return meterRegistry.find(metricName)
        .tag("context", METRIC_CONTEXT)
        .counters().stream()
        .filter(counter -> status == null || status.equals(counter.getId().getTag("status")))
        .mapToDouble(Counter::count)
        .sum();
  }

  private static long percentile95(List<Long> values) {
    List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
    int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
    return sorted.get(index);
  }

  private static double ratio(long numerator, long denominator) {
    return denominator == 0 ? 0 : (double) numerator / denominator;
  }

  private void writeReport(Map<String, Object> report) throws Exception {
    Path directory = Path.of("build", "reports", "p4-9b");
    Files.createDirectories(directory);
    Path output = directory.resolve(mode() + ".json");
    String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
    Files.writeString(output, json, StandardCharsets.UTF_8);
    System.out.println("[P4-9b] report=" + output.toAbsolutePath());
    System.out.println("[P4-9b] summary=" + objectMapper.writeValueAsString(
        Map.of(
            "mode", report.get("mode"),
            "sampleCount", report.get("sampleCount"),
            "p95Ms", report.get("p95Ms"),
            "actualModelRequests", report.get("actualModelRequests"),
            "degradationRatio", report.get("degradationRatio"),
            "qualityPassRate", report.get("qualityPassRate")
        )));
  }

  private static String mode() {
    String value = System.getenv("P4_LIVE_MODE");
    return MODE_FAILURE.equalsIgnoreCase(value) ? MODE_FAILURE : MODE_NORMAL;
  }

  private String selectedProvider() {
    String configured = configuredProvider();
    return configured != null ? configured : ephemeralProviderId;
  }

  private static String configuredProvider() {
    String value = System.getenv("P4_LIVE_PROVIDER");
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static int sampleCount() {
    String value = System.getenv("P4_LIVE_SAMPLE_COUNT");
    if (value == null || value.isBlank()) {
      return DEFAULT_SAMPLE_COUNT;
    }
    return Math.max(2, Integer.parseInt(value));
  }

  private static String valueOf(String key) {
    String environment = System.getenv(key);
    if (environment != null && !environment.isBlank()) {
      return environment;
    }
    return LocalDatabaseGate.DOTENV != null ? LocalDatabaseGate.DOTENV.get(key) : null;
  }

  private record SeededSession(String sessionId, String questionId, int ordinal) {}

  private record MetricSnapshot(
      double attempts,
      double successfulInvocations,
      double failedInvocations
  ) {}

  private record Sample(
      int ordinal,
      String caseName,
      long latencyMs,
      String nextQuestionId,
      String decisionReason,
      boolean degraded,
      boolean qualityPassed
  ) {}
}
