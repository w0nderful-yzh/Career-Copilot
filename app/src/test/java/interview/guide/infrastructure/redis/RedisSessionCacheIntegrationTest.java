package interview.guide.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.support.LocalDatabaseGate;
import interview.guide.support.LocalRedisGate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话缓存的真实 Redis 验证（§4.5 可靠性回归）。
 *
 * <p>要证的是 mock 测不到的那部分：**Redisson 二进制编解码下的字段往返与 TTL**。
 * 会话缓存每加一个字段（P4-1 的轨迹、P4Q-2 的计划与耗时、P5-2 的重点），
 * 都可能「写进去了但读不回来」——mock 掉 RedisService 时字段是同一个对象，
 * 这类丢失永远不会暴露；用户看到的是刷新后进度回退。
 *
 * <p>连的是本地 dev Redis（与 docker-compose.dev 同源），数据自播种自清理；
 * 环境不可达时整类跳过（见 {@link LocalRedisGate}）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf(value = "interview.guide.support.LocalRedisGate#availableForContext",
    disabledReason = "本地 Redis 或数据库不可达，跳过真实缓存验证")
@DisplayName("会话缓存真实 Redis 验证（§4.5）")
class RedisSessionCacheIntegrationTest {

  private static final String SESSION_ID = "e2e-redis-cache-probe-01";

  private static final String KEY = "interview:session:" + SESSION_ID;

  @DynamicPropertySource
  static void contextProperties(DynamicPropertyRegistry registry) {
    // 上下文启动所需的数据库配置（与既有真实 DB 测试同一套解析）
    if (LocalDatabaseGate.DOTENV != null) {
      LocalDatabaseGate.DOTENV.forEach((key, value) -> registry.add(key, () -> value));
    }
    if (LocalDatabaseGate.DATASOURCE != null) {
      registry.add("spring.datasource.url", LocalDatabaseGate::url);
      registry.add("spring.datasource.username", LocalDatabaseGate::username);
      registry.add("spring.datasource.password", LocalDatabaseGate::password);
    }
    // 显式指向探测到的那台 Redis：测试 JVM 不会自动读 .env
    registry.add("spring.redis.redisson.config", () -> """
        singleServerConfig:
          address: "redis://%s:%d"
          database: 0
        """.formatted(LocalRedisGate.host(), LocalRedisGate.port()));
  }

  @Autowired
  private InterviewSessionCache sessionCache;

  @Autowired
  private RedisService redisService;

  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanUp() {
    sessionCache.deleteSession(SESSION_ID);
  }

  private static List<InterviewQuestionDTO> candidates() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "JVM 内存模型？", "JAVA", "Java", null, 3, List.of("堆"))
            .withQuestionId("q1"),
        InterviewQuestionDTO.createFollowUp(1, "堆区分代？", "JAVA", "Java", "q1", 1, "DEPTH",
            List.of()).withQuestionId("q2"));
  }

  private static InterviewTurnDTO turn() {
    return new InterviewTurnDTO("q1", 1, 0, "JVM 内存模型？", "Java", null, "堆和方法区",
        AnswerState.ANSWERED, null, null, InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(),
        LocalDateTime.now());
  }

  @Test
  @DisplayName("候选 / 轨迹 / 计划 / 耗时都能穿过 Redis 编解码往返")
  void sessionSnapshotSurvivesRedisRoundTrip() {
    sessionCache.saveSession(SESSION_ID, "简历原文", 7L, null, null, candidates(), 0,
        SessionStatus.IN_PROGRESS, true);
    sessionCache.applyPlan(SESSION_ID, new InterviewPlan(20, List.of("JAVA"), List.of("Java")));
    sessionCache.applyTurnState(SESSION_ID, candidates(), List.of(turn()), 1, "q2",
        SessionStatus.IN_PROGRESS, 3, 95);

    Optional<CachedSession> restored = sessionCache.getSession(SESSION_ID);

    assertThat(restored).as("缓存键存在且能反序列化").isPresent();
    CachedSession session = restored.get();
    assertThat(session.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(session.getResumeText()).isEqualTo("简历原文");
    assertThat(session.getQuestions(objectMapper))
        .extracting(InterviewQuestionDTO::questionId)
        .containsExactly("q1", "q2");
    // 轨迹与计划是 P4-1 / P4Q-2 / P5-2 新增的字段：读不回来就等于刷新后进度回退
    assertThat(session.getTurns(objectMapper))
        .extracting(InterviewTurnDTO::questionId)
        .containsExactly("q1");
    assertThat(session.hasTurnsSnapshot()).isTrue();
    assertThat(session.getPlannedDurationMinutes()).isEqualTo(20);
    assertThat(session.getRequiredTopicsJson()).contains("Java");
    assertThat(session.getFocusCategoriesJson()).contains("JAVA");
    assertThat(session.getConsumedSeconds()).isEqualTo(95);
    assertThat(session.getTurnVersion()).isEqualTo(3);
    assertThat(session.getCurrentQuestionId()).isEqualTo("q2");
    assertThat(session.getCurrentIndex()).isEqualTo(1);
  }

  @Test
  @DisplayName("会话缓存带 TTL：不会永久占用 Redis")
  void sessionKeyHasTtl() {
    sessionCache.saveSession(SESSION_ID, "简历", null, null, null, candidates(), 0,
        SessionStatus.IN_PROGRESS, false);

    assertThat(redisService.exists(KEY)).isTrue();
    assertThat(redisService.getTimeToLive(KEY))
        .as("会话缓存必须有过期时间（24h）")
        .isPositive();
  }

  @Test
  @DisplayName("缓存缺失：读取返回空而不是抛错，由上层回源数据库")
  void missingSessionReadsAsEmpty() {
    sessionCache.deleteSession(SESSION_ID);

    assertThat(sessionCache.getSession(SESSION_ID)).isEmpty();
    assertThat(redisService.exists(KEY)).isFalse();
  }
}
