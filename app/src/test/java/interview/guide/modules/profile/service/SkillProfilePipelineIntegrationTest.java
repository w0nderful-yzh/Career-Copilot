package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 画像证据链路真实持久化验证（P3-1）。
 *
 * <p>直连本地 dev 数据库（docker-compose.dev.yml 的 Postgres），验证
 * 提取→聚合→级联全链路在真实 JPA + 迁移表结构上的行为。测试数据自播种自清理，
 * 依赖真实 DB 而非 H2（证据表有 PG 方言约束）。
 *
 * <p>数据库凭据从项目根 .env 或环境变量解析（与 bootRun 同源）；两者都不可用时
 * assumeTrue 跳过而不是挂掉，避免破坏无本地 DB 的 CI/全量测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@DisplayName("画像证据链路集成验证（P3-1）")
class SkillProfilePipelineIntegrationTest {

  private static final String E2E_SESSION_ID = "e2e0000000000001";

  /** .env 全量键值（对齐 bootRun 的注入行为），供上下文补齐 APP_AI_* 等非数据源配置 */
  // 注意：DATASOURCE 的解析依赖本字段，声明顺序必须在其之前
  private static final Map<String, String> DOTENV = loadDotenv();

  /** 解析后的数据库连接配置；null 表示环境不可用，测试将跳过 */
  private static final Map<String, String> DATASOURCE = resolveDatasource();

  @DynamicPropertySource
  static void contextProperties(DynamicPropertyRegistry registry) {
    if (DOTENV != null) {
      DOTENV.forEach((key, value) -> registry.add(key, () -> value));
    }
    if (DATASOURCE != null) {
      registry.add("spring.datasource.url", () -> DATASOURCE.get("url"));
      registry.add("spring.datasource.username", () -> DATASOURCE.get("username"));
      registry.add("spring.datasource.password", () -> DATASOURCE.get("password"));
    }
  }

  /**
   * 数据库配置解析顺序：环境变量（POSTGRES_*，与 application.yml/docker-compose 同源）
   * → 项目根 .env。任一路径给出凭据即视为环境可用。
   */
  private static Map<String, String> resolveDatasource() {
    String host = envOrDotenv("POSTGRES_HOST");
    String user = envOrDotenv("POSTGRES_USER");
    String password = envOrDotenv("POSTGRES_PASSWORD");
    if (password == null) {
      return null;
    }
    Map<String, String> config = new HashMap<>();
    config.put("url", "jdbc:postgresql://"
        + (host != null ? host : "localhost") + ":"
        + envOrDefault("POSTGRES_PORT", "5432") + "/"
        + envOrDefault("POSTGRES_DB", "interview_guide"));
    config.put("username", user != null ? user : "postgres");
    config.put("password", password);
    return config;
  }

  /** 环境变量优先；为空时尝试从项目根 .env 读取（bootRun 与测试不同 JVM，.env 不会自动加载） */
  private static String envOrDotenv(String key) {
    String value = System.getenv(key);
    if (value != null && !value.isBlank()) {
      return value;
    }
    return DOTENV != null ? DOTENV.get(key) : null;
  }

  /** 全量解析 .env 为有序 Map（跳过注释与空行），找不到文件返回 null */
  private static Map<String, String> loadDotenv() {
    Path envFile = findEnvFile();
    if (envFile == null) {
      return null;
    }
    try {
      Map<String, String> values = new HashMap<>();
      for (String line : Files.readAllLines(envFile)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = trimmed.substring(0, eq).trim();
        String value = trimmed.substring(eq + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))) {
          value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
      }
      return values;
    } catch (IOException e) {
      return null;
    }
  }

  private static String envOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }

  /**
   * 从当前目录向上查找仓库根的 .env（Gradle test worker 的 user.dir 是 app/ 子目录，
   * 与 bootRun 的 rootProject.file('.env') 不同源，需自行向上定位）。
   */
  private static Path findEnvFile() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++) {
      Path candidate = dir.resolve(".env");
      if (Files.isReadable(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }

  @Autowired
  private InterviewEvidenceExtractor extractor;
  @Autowired
  private SkillProfileAggregator aggregator;
  @Autowired
  private InterviewSessionRepository sessionRepository;
  @Autowired
  private SkillEvidenceRepository evidenceRepository;
  @Autowired
  private SkillProfileRepository profileRepository;

  @AfterEach
  void cleanup() {
    // @Transactional 回滚已覆盖 JPA 写入；此处兜底清理（防非事务路径残留）
    evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
            EvidenceSourceType.INTERVIEW_TURN, E2E_SESSION_ID + ":")
        .forEach(evidenceRepository::delete);
  }

  @Test
  @DisplayName("提取→聚合→级联：种子会话产出 MySQL=83(2题) 且 JVM=55(1题)，Redis 无证据不入画像")
  void fullPipelineExtractsAggregatesAndCascades() {
    assumeTrue(DATASOURCE != null, "本地 dev 数据库凭据不可用（无 POSTGRES_* 环境变量/.env），跳过");

    // 测试自播种：一场 4 题面试（3 题真实作答 + 1 题未作答）
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(E2E_SESSION_ID);
    session.setTotalQuestions(4);
    session.setCurrentQuestionIndex(4);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    session.setEvaluateStatus(AsyncTaskStatusCompleted());
    session.setCompletedAt(java.time.LocalDateTime.of(2026, 8, 28, 12, 0));
    session.setQuestionsJson("[]");
    sessionRepository.save(session);
    seedAnswer(0, "MySQL", "InnoDB 使用 B+ 树索引", 88);
    seedAnswer(1, "MySQL", "MVCC 实现事务隔离", 78);
    seedAnswer(2, "JVM", "G1 按 Region 分堆", 55);
    seedAnswer(3, "Redis", null, 0); // 未作答：不算证据

    // 1. 提取：4 题中只有 3 题入证据
    List<SkillEvidenceEntity> evidences = extractor.extract(E2E_SESSION_ID);
    assertThat(evidences).hasSize(3);

    // 2. 聚合：MySQL=(88+78)/2=83，JVM=55，Redis 不出画像
    aggregator.applyEvidence(evidences);

    assertThat(profileRepository.findByUserIdAndSkill("default", "MySQL"))
        .hasValueSatisfying(profile -> {
          assertThat(profile.getScore()).isEqualTo(83);
          assertThat(profile.getEvidenceCount()).isEqualTo(2);
        });
    assertThat(profileRepository.findByUserIdAndSkill("default", "JVM"))
        .hasValueSatisfying(profile -> {
          assertThat(profile.getScore()).isEqualTo(55);
          assertThat(profile.getEvidenceCount()).isEqualTo(1);
        });
    assertThat(profileRepository.findByUserIdAndSkill("default", "Redis")).isEmpty();

    // 3. 删除级联：清掉该会话证据后画像行同步消失
    aggregator.removeInterviewSessionEvidence(E2E_SESSION_ID);
    assertThat(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, E2E_SESSION_ID + ":")).isEmpty();
    assertThat(profileRepository.findByUserIdAndSkill("default", "MySQL")).isEmpty();
    assertThat(profileRepository.findByUserIdAndSkill("default", "JVM")).isEmpty();
  }

  /** 播种一条作答回答（session_id 关联通过 JPA 关系维护） */
  private void seedAnswer(int index, String category, String answer, int score) {
    InterviewSessionEntity session = sessionRepository.findBySessionId(E2E_SESSION_ID).orElseThrow();
    interview.guide.modules.interview.model.InterviewAnswerEntity entity =
        new interview.guide.modules.interview.model.InterviewAnswerEntity();
    entity.setQuestionIndex(index);
    entity.setCategory(category);
    entity.setUserAnswer(answer);
    entity.setScore(answer != null ? score : null);
    entity.setQuestion("Q" + index);
    session.addAnswer(entity);
  }

  /** AsyncTaskStatus.COMPLETED 的局部别名，避免为此引入静态导入 */
  private static interview.guide.common.model.AsyncTaskStatus AsyncTaskStatusCompleted() {
    return interview.guide.common.model.AsyncTaskStatus.COMPLETED;
  }
}
