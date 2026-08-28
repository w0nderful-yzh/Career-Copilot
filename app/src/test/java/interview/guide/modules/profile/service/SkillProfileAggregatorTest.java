package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillProfileAggregatorTest {

  @Mock
  private SkillEvidenceRepository evidenceRepository;
  @Mock
  private SkillProfileRepository profileRepository;

  @InjectMocks
  private SkillProfileAggregator aggregator;

  private SkillEvidenceEntity evidence(String skill, String sourceId, int score) {
    return new SkillEvidenceEntity(
        ProfileConstants.DEFAULT_USER_ID, skill,
        EvidenceSourceType.INTERVIEW_TURN, sourceId, score,
        LocalDateTime.of(2026, 8, 28, 10, 0));
  }

  @Nested
  @DisplayName("证据应用与聚合")
  class ApplyEvidence {

    @Test
    @DisplayName("多条证据按等权均值聚合，evidenceCount 等于证据条数")
    void aggregatesEqualWeightMean() {
      when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
          anyString(), anyString(), any(), anyString())).thenReturn(Optional.empty());
      when(evidenceRepository.findByUserIdAndSkill(ProfileConstants.DEFAULT_USER_ID, "MySQL"))
          .thenReturn(List.of(
              evidence("MySQL", "s1:0", 90),
              evidence("MySQL", "s1:1", 70),
              evidence("MySQL", "s2:0", 80)));

      List<String> skills = aggregator.applyEvidence(List.of(
          evidence("MySQL", "s1:0", 90),
          evidence("MySQL", "s1:1", 70),
          evidence("MySQL", "s2:0", 80)));

      assertThat(skills).containsExactly("MySQL");
      ArgumentCaptor<SkillProfileEntity> captor = ArgumentCaptor.forClass(SkillProfileEntity.class);
      verify(profileRepository).save(captor.capture());
      SkillProfileEntity saved = captor.getValue();
      assertThat(saved.getSkill()).isEqualTo("MySQL");
      assertThat(saved.getScore()).isEqualTo(80);
      assertThat(saved.getEvidenceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("已存在的同源证据按更新处理，不产生重复计分（幂等）")
    void updatesExistingEvidenceInsteadOfDuplicating() {
      SkillEvidenceEntity existing = evidence("JVM", "s1:0", 40);
      when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
          anyString(), anyString(), any(), anyString())).thenReturn(Optional.of(existing));
      when(evidenceRepository.findByUserIdAndSkill(ProfileConstants.DEFAULT_USER_ID, "JVM"))
          .thenReturn(List.of(existing));

      aggregator.applyEvidence(List.of(evidence("JVM", "s1:0", 56)));

      assertThat(existing.getScore()).isEqualTo(56);
      verify(evidenceRepository, never()).save(any());
      ArgumentCaptor<SkillProfileEntity> captor = ArgumentCaptor.forClass(SkillProfileEntity.class);
      verify(profileRepository).save(captor.capture());
      assertThat(captor.getValue().getScore()).isEqualTo(56);
      assertThat(captor.getValue().getEvidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("空证据列表直接返回，不触发任何写操作")
    void emptyEvidenceIsNoop() {
      assertThat(aggregator.applyEvidence(List.of())).isEmpty();
      assertThat(aggregator.applyEvidence(null)).isEmpty();
      verify(evidenceRepository, never()).save(any());
      verify(profileRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("删除级联与重聚合")
  class RemoveEvidence {

    @Test
    @DisplayName("删除会话证据后重聚合剩余证据的平均分")
    void removeSessionEvidenceReaggregates() {
      SkillEvidenceEntity remaining = evidence("MySQL", "s2:0", 90);
      when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
          EvidenceSourceType.INTERVIEW_TURN, "s1:"))
          .thenReturn(List.of(evidence("MySQL", "s1:0", 40), evidence("MySQL", "s1:1", 50)));
      when(evidenceRepository.findByUserIdAndSkill(ProfileConstants.DEFAULT_USER_ID, "MySQL"))
          .thenReturn(List.of(remaining));

      aggregator.removeInterviewSessionEvidence("s1");

      verify(evidenceRepository).deleteAll(any());
      ArgumentCaptor<SkillProfileEntity> captor = ArgumentCaptor.forClass(SkillProfileEntity.class);
      verify(profileRepository).save(captor.capture());
      assertThat(captor.getValue().getScore()).isEqualTo(90);
      assertThat(captor.getValue().getEvidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("证据清空后删除对应画像行，而不是留 0 分空壳")
    void deletesProfileWhenNoEvidenceLeft() {
      when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
          EvidenceSourceType.INTERVIEW_TURN, "s1:"))
          .thenReturn(List.of(evidence("JVM", "s1:0", 40)));
      when(evidenceRepository.findByUserIdAndSkill(ProfileConstants.DEFAULT_USER_ID, "JVM"))
          .thenReturn(List.of());
      SkillProfileEntity profile = new SkillProfileEntity();
      profile.setSkill("JVM");
      when(profileRepository.findByUserIdAndSkill(ProfileConstants.DEFAULT_USER_ID, "JVM"))
          .thenReturn(Optional.of(profile));

      aggregator.removeInterviewSessionEvidence("s1");

      verify(profileRepository).delete(profile);
      verify(profileRepository, never()).save(any());
    }

    @Test
    @DisplayName("批量会话级联：按前缀合并清理并重聚合一次")
    void removesBatchSessionEvidence() {
      SkillEvidenceEntity remaining = evidence("JVM", "s3:0", 70);
      when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
          EvidenceSourceType.INTERVIEW_TURN, "s1:"))
          .thenReturn(List.of(evidence("JVM", "s1:0", 40)));
      when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
          EvidenceSourceType.INTERVIEW_TURN, "s2:"))
          .thenReturn(List.of(evidence("JVM", "s2:0", 50)));
      when(evidenceRepository.findByUserIdAndSkill(ProfileConstants.DEFAULT_USER_ID, "JVM"))
          .thenReturn(List.of(remaining));

      aggregator.removeInterviewSessionEvidence(List.of("s1", "s2"));

      verify(evidenceRepository).deleteAll(any());
      ArgumentCaptor<SkillProfileEntity> captor = ArgumentCaptor.forClass(SkillProfileEntity.class);
      verify(profileRepository).save(captor.capture());
      assertThat(captor.getValue().getScore()).isEqualTo(70);
    }

    @Test
    @DisplayName("无证据可清理时静默返回")
    void removeNothingIsNoop() {
      when(evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
          EvidenceSourceType.INTERVIEW_TURN, "s9:")).thenReturn(List.of());

      aggregator.removeInterviewSessionEvidence("s9");

      verify(evidenceRepository, never()).deleteAll(any());
      verify(profileRepository, never()).save(any());
    }
  }
}
