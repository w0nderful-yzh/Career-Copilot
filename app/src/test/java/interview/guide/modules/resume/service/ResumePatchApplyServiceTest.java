package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.repository.ResumeVersionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
class ResumePatchApplyServiceTest {

  private static final String SOURCE_CONTENT = """
      {"basicInfo": {"name": "张三", "phone": "138", "email": "", "location": "", "jobIntention": ""},
       "education": [], "experience": [],
       "projects": [{"name": "Demo", "role": "", "startDate": "", "endDate": "",
                     "techStack": "Spring Boot", "bullets": ["负责后端开发工作", "参与数据库设计"]}],
       "skills": [{"category": "", "content": "熟悉 Java"}],
       "customSections": []}
      """;

  @Mock
  private ResumeVersionRepository versionRepository;
  @Mock
  private ResumeVersionService versionService;
  @Mock
  private ResumeOptimizationProposalService proposalService;

  @Spy
  private final ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks
  private ResumePatchApplyService applyService;

  private ResumeVersionEntity sourceVersion;
  private ResumeOptimizationProposalEntity proposal;

  @BeforeEach
  void setUp() {
    sourceVersion = new ResumeVersionEntity();
    sourceVersion.setId(5L);
    sourceVersion.setResumeId(1L);
    sourceVersion.setVersion(1);
    sourceVersion.setContentJson(SOURCE_CONTENT);

    proposal = new ResumeOptimizationProposalEntity();
    proposal.setId(77L);
    proposal.setResumeId(1L);
    proposal.setSourceVersionId(5L);
    proposal.setStatus(ResumeOptimizationProposalEntity.ProposalStatus.PENDING);
  }

  @Nested
  @DisplayName("Patch 应用与版本生成")
  class Apply {

    @Test
    @DisplayName("REPLACE 命中 oldValue：应用到新版本（V2），原版本内容不变")
    void appliesReplaceToNewVersion() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.REPLACE,
              "projects[0].bullets[0]", "负责后端开发工作", "主导后端开发工作", "突出职责")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);
      when(versionRepository.findByResumeIdAndVersion(1L, 2)).thenReturn(Optional.empty());
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeVersionEntity newVersion = applyService.applyPatches(77L, List.of());

      assertThat(newVersion.getVersion()).isEqualTo(2);
      assertThat(newVersion.getSource())
          .isEqualTo(ResumeVersionEntity.VersionSource.AI_OPTIMIZE);
      assertThat(newVersion.getContentJson()).contains("主导后端开发工作");
      // 原版本对象未被修改
      assertThat(sourceVersion.getContentJson()).contains("负责后端开发工作");
      // 提案状态流转到 APPLIED
      org.mockito.Mockito.verify(proposalService).transitionFromPending(
          77L, ResumeOptimizationProposalEntity.ProposalStatus.APPLIED);
    }

    @Test
    @DisplayName("只应用用户勾选的 patchIds")
    void appliesOnlySelectedPatches() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.REPLACE,
              "projects[0].bullets[0]", "负责后端开发工作", "主导后端开发工作", "r"),
          new ResumePatchItem("p2", ResumePatchItem.PatchType.REPLACE,
              "skills[0].content", "熟悉 Java", "熟练掌握 Java", "r")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);
      when(versionRepository.findByResumeIdAndVersion(1L, 2)).thenReturn(Optional.empty());
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeVersionEntity newVersion = applyService.applyPatches(77L, List.of("p2"));

      assertThat(newVersion.getContentJson()).contains("熟练掌握 Java");
      assertThat(newVersion.getContentJson()).contains("负责后端开发工作");
      ArgumentCaptor<ResumeVersionEntity> captor =
          ArgumentCaptor.forClass(ResumeVersionEntity.class);
      org.mockito.Mockito.verify(versionRepository).save(captor.capture());
      assertThat(captor.getValue().getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("oldValue 不一致（内容漂移）→ PATCH_CONFLICT 拒绝应用")
    void rejectsWhenContentDrifted() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.REPLACE,
              "projects[0].bullets[0]", "已被修改的旧内容", "新内容", "r")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);

      assertThatThrownBy(() -> applyService.applyPatches(77L, List.of()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "code", ErrorCode.RESUME_OPTIMIZATION_PATCH_CONFLICT.getCode());
      org.mockito.Mockito.verify(proposalService, org.mockito.Mockito.never())
          .transitionFromPending(any(), any());
    }

    @Test
    @DisplayName("DELETE 移除目标数组元素")
    void deletesArrayElement() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.DELETE,
              "projects[0].bullets[1]", "参与数据库设计", null, "删除冗余")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);
      when(versionRepository.findByResumeIdAndVersion(1L, 2)).thenReturn(Optional.empty());
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeVersionEntity newVersion = applyService.applyPatches(77L, List.of());

      assertThat(newVersion.getContentJson()).doesNotContain("参与数据库设计");
      assertThat(newVersion.getContentJson()).contains("负责后端开发工作");
    }

    @Test
    @DisplayName("ADD 向数组追加新元素")
    void addsArrayElement() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.ADD,
              "projects[0].bullets", null, "搭建 CI 流水线", "补充职责")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);
      when(versionRepository.findByResumeIdAndVersion(1L, 2)).thenReturn(Optional.empty());
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeVersionEntity newVersion = applyService.applyPatches(77L, List.of());

      assertThat(newVersion.getContentJson()).contains("搭建 CI 流水线");
    }

    @Test
    @DisplayName("非法 path 段拒绝（防御任意 JSON 改写）")
    void rejectsUnknownSegment() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.REPLACE,
              "secretField", "x", "y", "r")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);

      assertThatThrownBy(() -> applyService.applyPatches(77L, List.of()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "code", ErrorCode.RESUME_OPTIMIZATION_INVALID.getCode());
    }

    @Test
    @DisplayName("REORDER 类型拒绝（一期决策兜底）")
    void rejectsReorder() {
      when(proposalService.getProposal(77L)).thenReturn(proposal);
      when(proposalService.parsePatches(proposal)).thenReturn(List.of(
          new ResumePatchItem("p1", ResumePatchItem.PatchType.REORDER,
              "skills", null, null, "r")));
      when(versionService.getVersion(5L)).thenReturn(sourceVersion);

      assertThatThrownBy(() -> applyService.applyPatches(77L, List.of()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "code", ErrorCode.RESUME_OPTIMIZATION_INVALID.getCode());
    }
  }
}
