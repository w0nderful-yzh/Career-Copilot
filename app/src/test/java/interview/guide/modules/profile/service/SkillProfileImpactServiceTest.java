package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.profile.dto.SkillProfileImpactResponse;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 本场面试的画像变化（P3 待收口）。
 *
 * <p>差分是**派生**的：before = 排除本场证据后的均值，after = 含本场证据的均值；
 * 不新增任何存储。展示与追溯都靠证据自身带的来源与时间。
 */
@DisplayName("面试画像变化差分")
@ExtendWith(MockitoExtension.class)
class SkillProfileImpactServiceTest {

  private static final String SESSION = "s1";

  @Mock
  private SkillEvidenceRepository evidenceRepository;

  /** P4-1：证据里只有题目标识，展示序号要回轮次行取（真实发生顺序） */
  @Mock
  private InterviewAnswerRepository answerRepository;

  @InjectMocks
  private SkillProfileImpactService impactService;

  private static SkillEvidenceEntity evidence(String skill, String sourceId, int score, int day) {
    return new SkillEvidenceEntity(ProfileConstants.DEFAULT_USER_ID, skill,
        EvidenceSourceType.INTERVIEW_TURN, sourceId, score,
        LocalDateTime.of(2026, 9, day, 10, 0));
  }

  private static InterviewAnswerEntity turn(String questionId, int ordinal) {
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setQuestionId(questionId);
    answer.setTurnOrdinal(ordinal);
    return answer;
  }

  @Test
  @DisplayName("已有历史证据时给出前后分与差值")
  void computesDeltaAgainstPreviousEvidence() {
    List<SkillEvidenceEntity> sessionEvidence = List.of(evidence("JVM", "s1:0", 80, 15));
    when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, "s1:")).thenReturn(sessionEvidence);
    when(evidenceRepository.findByUserIdAndSkill(anyString(), eq("JVM")))
        .thenReturn(List.of(evidence("JVM", "s0:0", 60, 10), sessionEvidence.getFirst()));
    when(answerRepository.findTurnsBySessionId(SESSION))
        .thenReturn(List.of(turn("legacy-0", 1)));

    SkillProfileImpactResponse response = impactService.impactOf(SESSION);

    SkillProfileImpactResponse.SkillImpactDTO impact = response.skills().getFirst();
    assertThat(impact.skill()).isEqualTo("JVM");
    assertThat(impact.beforeScore()).isEqualTo(60);
    assertThat(impact.afterScore()).isEqualTo(70);
    assertThat(impact.delta()).isEqualTo(10);
    assertThat(impact.sessionEvidences())
        .extracting(SkillProfileImpactResponse.SessionEvidenceDTO::questionKey,
            SkillProfileImpactResponse.SessionEvidenceDTO::questionOrdinal,
            SkillProfileImpactResponse.SessionEvidenceDTO::score)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("0", 1, 80));
  }

  @Test
  @DisplayName("本场首次考到的技能 beforeScore 为空且 delta 为 0（不由前端编造涨幅）")
  void firstTimeSkillHasNoBeforeScore() {
    List<SkillEvidenceEntity> sessionEvidence = List.of(evidence("Kafka", "s1:3", 55, 15));
    when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, "s1:")).thenReturn(sessionEvidence);
    when(evidenceRepository.findByUserIdAndSkill(anyString(), eq("Kafka")))
        .thenReturn(sessionEvidence);

    SkillProfileImpactResponse.SkillImpactDTO impact =
        impactService.impactOf(SESSION).skills().getFirst();

    assertThat(impact.beforeScore()).isNull();
    assertThat(impact.afterScore()).isEqualTo(55);
    assertThat(impact.delta()).isZero();
  }

  @Test
  @DisplayName("声明型证据不参与差分：简历声明不改变前后分")
  void declarationDoesNotAffectImpact() {
    SkillEvidenceEntity declaration = new SkillEvidenceEntity(
        ProfileConstants.DEFAULT_USER_ID, "JVM", EvidenceSourceType.RESUME, "7", null,
        LocalDateTime.of(2026, 9, 14, 10, 0));
    List<SkillEvidenceEntity> sessionEvidence = List.of(evidence("JVM", "s1:0", 80, 15));
    when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, "s1:")).thenReturn(sessionEvidence);
    when(evidenceRepository.findByUserIdAndSkill(anyString(), eq("JVM")))
        .thenReturn(List.of(declaration, sessionEvidence.getFirst()));

    SkillProfileImpactResponse.SkillImpactDTO impact =
        impactService.impactOf(SESSION).skills().getFirst();

    assertThat(impact.beforeScore()).isNull();
    assertThat(impact.afterScore()).isEqualTo(80);
  }

  @Test
  @DisplayName("本场无可计分证据时返回空列表")
  void noSessionEvidenceYieldsEmpty() {
    when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, "s1:")).thenReturn(List.of());

    assertThat(impactService.impactOf(SESSION).skills()).isEmpty();
    assertThat(impactService.impactOf(null).skills()).isEmpty();
  }

  @Test
  @DisplayName("多个技能按技能名稳定排序返回")
  void returnsStableOrdering() {
    List<SkillEvidenceEntity> sessionEvidence = List.of(
        evidence("Redis", "s1:1", 70, 15),
        evidence("JVM", "s1:0", 80, 15));
    when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
        EvidenceSourceType.INTERVIEW_TURN, "s1:")).thenReturn(sessionEvidence);
    when(evidenceRepository.findByUserIdAndSkill(anyString(), eq("Redis")))
        .thenReturn(List.of(sessionEvidence.getFirst()));
    when(evidenceRepository.findByUserIdAndSkill(anyString(), eq("JVM")))
        .thenReturn(List.of(sessionEvidence.get(1)));

    assertThat(impactService.impactOf(SESSION).skills())
        .extracting(SkillProfileImpactResponse.SkillImpactDTO::skill)
        .containsExactly("JVM", "Redis");
    verify(answerRepository).findTurnsBySessionId(SESSION);
  }
}
