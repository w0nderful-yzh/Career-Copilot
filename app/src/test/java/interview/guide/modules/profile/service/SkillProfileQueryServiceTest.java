package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.profile.dto.SkillProfileResponse;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 画像查询的「声明 vs 有分」分野（P3 待收口）。
 *
 * <p>declaredSkills 必须只装**从未被考过**的技能；已考过的技能即使简历里也列了，
 * 也只能出现在 skills 里（否则同一技能会在两个列表里各出现一次）。
 */
@DisplayName("画像查询：有分技能与简历声明分离")
@ExtendWith(MockitoExtension.class)
class SkillProfileQueryServiceTest {

  @Mock
  private SkillProfileRepository profileRepository;
  @Mock
  private SkillEvidenceRepository evidenceRepository;

  @InjectMocks
  private SkillProfileQueryService queryService;

  private static SkillProfileEntity profile(String skill, int score, int count) {
    SkillProfileEntity entity = new SkillProfileEntity();
    entity.setSkill(skill);
    entity.setScore(score);
    entity.setEvidenceCount(count);
    entity.setUpdatedAt(LocalDateTime.of(2026, 9, 15, 10, 0));
    return entity;
  }

  private static SkillEvidenceEntity declared(String skill) {
    return new SkillEvidenceEntity(ProfileConstants.DEFAULT_USER_ID, skill,
        EvidenceSourceType.RESUME, "7", null, LocalDateTime.of(2026, 9, 14, 9, 0));
  }

  @Test
  @DisplayName("已考过的技能不出现在声明列表（含大小写不一致的情况）")
  void declaredSkillsExcludeScoredOnesCaseInsensitively() {
    when(profileRepository.findByUserIdOrderByScoreDesc(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of(profile("Java", 70, 2)));
    when(evidenceRepository.findByUserIdAndSkillOrderByOccurredAtDesc(
        ProfileConstants.DEFAULT_USER_ID, "Java"))
        .thenReturn(List.of());
    when(evidenceRepository.findByUserIdAndSourceType(
        ProfileConstants.DEFAULT_USER_ID, EvidenceSourceType.RESUME))
        .thenReturn(List.of(declared("java"), declared("Kafka")));

    SkillProfileResponse response = queryService.getProfileWithEvidence();

    assertThat(response.skills()).hasSize(1);
    // "java" 与已考过的 "Java" 是同一技能，不能重复出现在声明列表里
    assertThat(response.declaredSkills())
        .extracting(SkillProfileResponse.DeclaredSkillDTO::skill)
        .containsExactly("Kafka");
  }

  @Test
  @DisplayName("声明列表带上来源简历与声明时间，供追溯")
  void declaredSkillsCarrySourceAndTime() {
    when(profileRepository.findByUserIdOrderByScoreDesc(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of());
    when(evidenceRepository.findByUserIdAndSourceType(
        ProfileConstants.DEFAULT_USER_ID, EvidenceSourceType.RESUME))
        .thenReturn(List.of(declared("Kafka")));

    SkillProfileResponse.DeclaredSkillDTO declared = queryService
        .getProfileWithEvidence().declaredSkills().getFirst();

    assertThat(declared.resumeId()).isEqualTo("7");
    assertThat(declared.declaredAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 9, 0));
  }

  @Test
  @DisplayName("无任何数据时两个列表都为空，不抛错")
  void emptyProfileIsSafe() {
    when(profileRepository.findByUserIdOrderByScoreDesc(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of());
    when(evidenceRepository.findByUserIdAndSourceType(
        ProfileConstants.DEFAULT_USER_ID, EvidenceSourceType.RESUME))
        .thenReturn(List.of());

    SkillProfileResponse response = queryService.getProfileWithEvidence();

    assertThat(response.skills()).isEmpty();
    assertThat(response.declaredSkills()).isEmpty();
  }
}
