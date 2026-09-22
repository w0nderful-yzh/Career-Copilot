package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import interview.guide.support.LocalDatabaseGate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
 * <p>数据库凭据从项目根 .env 或环境变量解析（与 bootRun 同源）；环境不可达时由类级
 * {@link EnabledIf} 经 {@link LocalDatabaseGate} 整类跳过。注意不能用测试方法内的
 * {@code assumeTrue}：Spring 上下文（含 Flyway 迁移）在方法体执行前就已初始化，
 * 连接失败会直接让整类报错而非跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@EnabledIf(value = "interview.guide.support.LocalDatabaseGate#available",
    disabledReason = "本地 dev 数据库不可达（POSTGRES_* 未配置或端口未监听），跳过真实 DB 集成验证")
@DisplayName("画像证据链路集成验证（P3-1）")
class SkillProfilePipelineIntegrationTest {

  private static final String E2E_SESSION_ID = "e2e0000000000001";

  /**
   * 播种用的技能名刻意带前缀，避免撞上真实数据。
   *
   * <p>本类直连**共享的 dev 数据库**，而聚合是按技能对全部证据求均值——用真实技能名
   * （MySQL / JVM / Redis）播种时，只要开发者自己跑过一场面试，均值里就会混入真实证据，
   * 断言必然失败（实测：MySQL 期望 83，混入一条 42 分的真实证据后变成 69）。
   * 用独有技能名既能覆盖同一条链路，又不会误伤真实数据，也不会被真实数据影响。
   */
  private static final String E2E_SKILL_A = "e2e-mysql-probe";

  private static final String E2E_SKILL_B = "e2e-jvm-probe";

  private static final String E2E_SKILL_UNANSWERED = "e2e-redis-probe";

  @DynamicPropertySource
  static void contextProperties(DynamicPropertyRegistry registry) {
    if (LocalDatabaseGate.DOTENV != null) {
      LocalDatabaseGate.DOTENV.forEach((key, value) -> registry.add(key, () -> value));
    }
    if (LocalDatabaseGate.DATASOURCE != null) {
      registry.add("spring.datasource.url", LocalDatabaseGate::url);
      registry.add("spring.datasource.username", LocalDatabaseGate::username);
      registry.add("spring.datasource.password", LocalDatabaseGate::password);
    }
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
    // 播种用的探针技能同样兜底清理，避免任何路径把它留在真实画像里
    for (String skill : List.of(E2E_SKILL_A, E2E_SKILL_B, E2E_SKILL_UNANSWERED)) {
      profileRepository.findByUserIdAndSkill("default", skill)
          .ifPresent(profileRepository::delete);
    }
  }

  @Test
  @DisplayName("提取→聚合→级联：种子会话产出 均值=83(2题) 与 55(1题)，未作答不入画像")
  void fullPipelineExtractsAggregatesAndCascades() {
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
    seedAnswer(0, E2E_SKILL_A, "InnoDB 使用 B+ 树索引", 88);
    seedAnswer(1, E2E_SKILL_A, "MVCC 实现事务隔离", 78);
    seedAnswer(2, E2E_SKILL_B, "G1 按 Region 分堆", 55);
    seedAnswer(3, E2E_SKILL_UNANSWERED, null, 0); // 未作答：不算证据

    // 1. 提取：4 题中只有 3 题入证据
    List<SkillEvidenceEntity> evidences = extractor.extract(E2E_SESSION_ID);
    assertThat(evidences).hasSize(3);

    // 2. 聚合：探针技能 A=(88+78)/2=83，B=55，未作答技能不出画像
    aggregator.applyEvidence(evidences);

    assertThat(profileRepository.findByUserIdAndSkill("default", E2E_SKILL_A))
        .hasValueSatisfying(profile -> {
          assertThat(profile.getScore()).isEqualTo(83);
          assertThat(profile.getEvidenceCount()).isEqualTo(2);
        });
    assertThat(profileRepository.findByUserIdAndSkill("default", E2E_SKILL_B))
        .hasValueSatisfying(profile -> {
          assertThat(profile.getScore()).isEqualTo(55);
          assertThat(profile.getEvidenceCount()).isEqualTo(1);
        });
    assertThat(profileRepository.findByUserIdAndSkill("default", E2E_SKILL_UNANSWERED)).isEmpty();

    // 3. 删除级联：清掉该会话证据后画像行同步消失
    aggregator.removeInterviewSessionEvidence(E2E_SESSION_ID);
    assertThat(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, E2E_SESSION_ID + ":")).isEmpty();
    assertThat(profileRepository.findByUserIdAndSkill("default", E2E_SKILL_A)).isEmpty();
    assertThat(profileRepository.findByUserIdAndSkill("default", E2E_SKILL_B)).isEmpty();
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
