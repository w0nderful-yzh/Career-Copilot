package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.service.SkillProfileRepairService.RepairReport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 历史伪技能修复（P4Q-6）。
 *
 * <p>核心要求：**可重跑且不重复计分**。合并时以 (技能, 来源类型, 来源ID) 为准——
 * 基技能已有同源证据就丢弃伪技能那条，绝不改名后造成同一句回答被计两次。
 */
@DisplayName("画像历史伪技能修复")
@ExtendWith(MockitoExtension.class)
class SkillProfileRepairServiceTest {

  @Mock
  private SkillEvidenceRepository evidenceRepository;
  @Mock
  private SkillProfileAggregator aggregator;

  @InjectMocks
  private SkillProfileRepairService repairService;

  private static SkillEvidenceEntity evidence(String skill, String sourceId, int score) {
    return new SkillEvidenceEntity(ProfileConstants.DEFAULT_USER_ID, skill,
        EvidenceSourceType.INTERVIEW_TURN, sourceId, score,
        LocalDateTime.of(2026, 9, 15, 14, 0));
  }

  @Test
  @DisplayName("伪技能证据改名回稳定技能，并重算基技能与伪技能两边的画像")
  void mergesPseudoSkillIntoStableSkill() {
    SkillEvidenceEntity pseudo = evidence("Java（追问1）", "s1:1", 52);
    when(evidenceRepository.findFollowUpSuffixedSkills(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of(pseudo));
    when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
        ProfileConstants.DEFAULT_USER_ID, "Java", EvidenceSourceType.INTERVIEW_TURN, "s1:1"))
        .thenReturn(Optional.empty());

    RepairReport report = repairService.mergeFollowUpPseudoSkills();

    assertThat(pseudo.getSkill()).isEqualTo("Java");
    verify(evidenceRepository).saveAll(List.of(pseudo));
    assertThat(report.merged()).isEqualTo(1);
    assertThat(report.droppedDuplicates()).isZero();
    assertThat(report.reaggregatedSkills()).containsExactlyInAnyOrder("Java", "Java（追问1）");
    verify(aggregator).reaggregateSkill("Java");
    verify(aggregator).reaggregateSkill("Java（追问1）");
  }

  @Test
  @DisplayName("基技能已有同源证据时丢弃伪技能那条：同一句回答不被计两次")
  void dropsDuplicateInsteadOfRenaming() {
    SkillEvidenceEntity pseudo = evidence("MySQL（追问1）", "s1:3", 45);
    SkillEvidenceEntity existing = evidence("MySQL", "s1:3", 45);
    when(evidenceRepository.findFollowUpSuffixedSkills(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of(pseudo));
    when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
        ProfileConstants.DEFAULT_USER_ID, "MySQL", EvidenceSourceType.INTERVIEW_TURN, "s1:3"))
        .thenReturn(Optional.of(existing));

    RepairReport report = repairService.mergeFollowUpPseudoSkills();

    verify(evidenceRepository).deleteAll(List.of(pseudo));
    verify(evidenceRepository, never()).saveAll(any());
    assertThat(report.merged()).isZero();
    assertThat(report.droppedDuplicates()).isEqualTo(1);
    // 伪技能那边仍要重算：证据没了，画像行应被聚合器删掉
    verify(aggregator).reaggregateSkill("MySQL（追问1）");
  }

  @Test
  @DisplayName("可重跑：没有伪技能时是空操作，不碰任何数据")
  void rerunIsNoop() {
    when(evidenceRepository.findFollowUpSuffixedSkills(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of());

    RepairReport report = repairService.mergeFollowUpPseudoSkills();

    assertThat(report.scanned()).isZero();
    assertThat(report.merged()).isZero();
    assertThat(report.droppedDuplicates()).isZero();
    assertThat(report.reaggregatedSkills()).isEmpty();
    verify(evidenceRepository, never()).deleteAll(any());
    verify(evidenceRepository, never()).saveAll(any());
    verify(aggregator, never()).reaggregateSkill(anyString());
  }

  @Test
  @DisplayName("粗筛漏网的名字（含「追问」但不是后缀形式）不动")
  void skipsNonSuffixCandidates() {
    when(evidenceRepository.findFollowUpSuffixedSkills(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of(evidence("追问技巧", "s1:9", 70)));

    RepairReport report = repairService.mergeFollowUpPseudoSkills();

    assertThat(report.merged()).isZero();
    assertThat(report.droppedDuplicates()).isZero();
    verify(evidenceRepository, never()).saveAll(any());
    verify(aggregator, never()).reaggregateSkill(anyString());
  }

  @Test
  @DisplayName("先删后改：丢弃与改名同时存在时不留下中间态")
  void dropsBeforeRenaming() {
    SkillEvidenceEntity toDrop = evidence("Java（追问1）", "s1:1", 50);
    SkillEvidenceEntity toRename = evidence("Java（追问2）", "s1:2", 60);
    when(evidenceRepository.findFollowUpSuffixedSkills(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of(toDrop, toRename));
    when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
        ProfileConstants.DEFAULT_USER_ID, "Java", EvidenceSourceType.INTERVIEW_TURN, "s1:1"))
        .thenReturn(Optional.of(evidence("Java", "s1:1", 50)));
    when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
        ProfileConstants.DEFAULT_USER_ID, "Java", EvidenceSourceType.INTERVIEW_TURN, "s1:2"))
        .thenReturn(Optional.empty());

    RepairReport report = repairService.mergeFollowUpPseudoSkills();

    assertThat(report.merged()).isEqualTo(1);
    assertThat(report.droppedDuplicates()).isEqualTo(1);
    verify(evidenceRepository).deleteAll(List.of(toDrop));
    verify(evidenceRepository).saveAll(List.of(toRename));
    // 删除先于改名：避免改名后与既有行撞唯一约束
    var order = org.mockito.Mockito.inOrder(evidenceRepository);
    order.verify(evidenceRepository).deleteAll(List.of(toDrop));
    order.verify(evidenceRepository).saveAll(List.of(toRename));
  }

  @Test
  @DisplayName("重算技能集合去重且稳定：基技能与伪技能各出现一次")
  void reaggregatedSkillsAreDeduplicated() {
    when(evidenceRepository.findFollowUpSuffixedSkills(ProfileConstants.DEFAULT_USER_ID))
        .thenReturn(List.of(
            evidence("Spring（追问1）", "s1:7", 60),
            evidence("Spring（追问1）", "s1:8", 61)));
    when(evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
        anyString(), eq("Spring"), eq(EvidenceSourceType.INTERVIEW_TURN), anyString()))
        .thenReturn(Optional.empty());

    RepairReport report = repairService.mergeFollowUpPseudoSkills();

    assertThat(report.reaggregatedSkills()).containsExactly("Spring", "Spring（追问1）");
    verify(aggregator, org.mockito.Mockito.times(1)).reaggregateSkill("Spring");
    verify(aggregator, org.mockito.Mockito.times(1)).reaggregateSkill("Spring（追问1）");
  }
}
