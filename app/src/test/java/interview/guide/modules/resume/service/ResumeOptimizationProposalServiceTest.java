package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.repository.ResumeOptimizationProposalRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ResumeOptimizationProposalServiceTest {

  @Mock
  private ResumeOptimizationProposalRepository proposalRepository;

  @Spy
  private final ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks
  private ResumeOptimizationProposalService proposalService;

  private ResumePatchItem patch(ResumePatchItem.PatchType type) {
    return new ResumePatchItem(
        "patch_1", type, "projects[0].bullets[0]", "旧描述", "新描述", "强化技术职责");
  }

  @Nested
  @DisplayName("提案创建")
  class Create {

    @Test
    @DisplayName("创建提案：状态 PENDING，patches 序列化存储")
    void createsPendingProposal() {
      when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeOptimizationProposalEntity saved = proposalService.createProposal(
          1L, 5L,
          ResumeOptimizationProposalEntity.OptimizationType.GENERAL,
          "强化项目职责",
          List.of(patch(ResumePatchItem.PatchType.REPLACE)));

      assertThat(saved.getStatus())
          .isEqualTo(ResumeOptimizationProposalEntity.ProposalStatus.PENDING);
      assertThat(saved.getPatchesJson()).contains("projects[0].bullets[0]");
      assertThat(saved.getResumeId()).isEqualTo(1L);
      assertThat(saved.getSourceVersionId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("空 patch 列表拒绝创建")
    void rejectsEmptyPatches() {
      assertThatThrownBy(() -> proposalService.createProposal(
          1L, 5L,
          ResumeOptimizationProposalEntity.OptimizationType.GENERAL,
          "无修改", List.of()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "code", ErrorCode.RESUME_OPTIMIZATION_INVALID.getCode());
    }

    @Test
    @DisplayName("REORDER 类型拒绝创建（一期决策）")
    void rejectsReorder() {
      assertThatThrownBy(() -> proposalService.createProposal(
          1L, 5L,
          ResumeOptimizationProposalEntity.OptimizationType.GENERAL,
          "调序", List.of(patch(ResumePatchItem.PatchType.REORDER))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "code", ErrorCode.RESUME_OPTIMIZATION_INVALID.getCode());
    }
  }

  @Nested
  @DisplayName("提案查询与状态流转")
  class QueryAndTransition {

    @Test
    @DisplayName("parsePatches 还原结构化 Patch 列表")
    void parsesPatches() {
      ResumeOptimizationProposalEntity proposal = new ResumeOptimizationProposalEntity();
      proposal.setPatchesJson(
          "[{\"id\":\"p1\",\"type\":\"REPLACE\",\"path\":\"skills[0].content\","
              + "\"oldValue\":\"旧\",\"newValue\":\"新\",\"reason\":\"r\"}]");

      List<ResumePatchItem> patches = proposalService.parsePatches(proposal);

      assertThat(patches).hasSize(1);
      assertThat(patches.get(0).type()).isEqualTo(ResumePatchItem.PatchType.REPLACE);
      assertThat(patches.get(0).path()).isEqualTo("skills[0].content");
    }

    @Test
    @DisplayName("PENDING → APPLIED 流转记录决策时间")
    void transitionsPendingToApplied() {
      ResumeOptimizationProposalEntity proposal = new ResumeOptimizationProposalEntity();
      proposal.setStatus(ResumeOptimizationProposalEntity.ProposalStatus.PENDING);
      when(proposalRepository.findById(9L)).thenReturn(Optional.of(proposal));

      proposalService.transitionFromPending(
          9L, ResumeOptimizationProposalEntity.ProposalStatus.APPLIED);

      ArgumentCaptor<ResumeOptimizationProposalEntity> captor =
          ArgumentCaptor.forClass(ResumeOptimizationProposalEntity.class);
      org.mockito.Mockito.verify(proposalRepository).save(captor.capture());
      assertThat(captor.getValue().getStatus())
          .isEqualTo(ResumeOptimizationProposalEntity.ProposalStatus.APPLIED);
      assertThat(captor.getValue().getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("非 PENDING 状态拒绝重复决策（幂等保护）")
    void rejectsDoubleDecision() {
      ResumeOptimizationProposalEntity proposal = new ResumeOptimizationProposalEntity();
      proposal.setStatus(ResumeOptimizationProposalEntity.ProposalStatus.APPLIED);
      when(proposalRepository.findById(9L)).thenReturn(Optional.of(proposal));

      assertThatThrownBy(() -> proposalService.transitionFromPending(
          9L, ResumeOptimizationProposalEntity.ProposalStatus.REJECTED))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "code", ErrorCode.RESUME_OPTIMIZATION_INVALID.getCode());
    }
  }
}
