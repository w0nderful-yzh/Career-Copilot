package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.repository.ResumeVersionRepository;
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
class ResumeVersionServiceTest {

  @Mock
  private ResumeVersionRepository versionRepository;
  @Mock
  private ResumePersistenceService persistenceService;

  @Spy
  private final ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks
  private ResumeVersionService versionService;

  private ResumeParseStructuredService.ResumeParseResult parseResult(
      String name, List<String> missing) {
    ResumeContentJson content = new ResumeContentJson(
        new ResumeContentJson.BasicInfo(name, null, null, null, null),
        List.of(), List.of(), List.of(), List.of(), List.of());
    return new ResumeParseStructuredService.ResumeParseResult(content, missing);
  }

  @Nested
  @DisplayName("导入版本创建")
  class CreateImportVersion {

    @Test
    @DisplayName("正常解析创建 V1，状态 PENDING_CONFIRMATION")
    void createsV1PendingConfirmation() {
      when(persistenceService.findById(1L)).thenReturn(Optional.of(new ResumeEntity()));
      when(versionRepository.findByResumeIdAndVersion(1L, 1)).thenReturn(Optional.empty());
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeVersionEntity saved = versionService.createImportVersion(
          1L, parseResult("张三", List.of("education")));

      assertThat(saved.getVersion()).isEqualTo(1);
      assertThat(saved.getSource()).isEqualTo(ResumeVersionEntity.VersionSource.IMPORT);
      assertThat(saved.getConfirmationStatus())
          .isEqualTo(ResumeVersionEntity.ConfirmationStatus.PENDING_CONFIRMATION);
      assertThat(saved.getMissingFieldsJson()).contains("education");
    }

    @Test
    @DisplayName("姓名缺失时状态 NEED_USER_INFO（关键身份字段不猜测）")
    void criticalMissingMarksNeedUserInfo() {
      when(persistenceService.findById(1L)).thenReturn(Optional.of(new ResumeEntity()));
      when(versionRepository.findByResumeIdAndVersion(1L, 1)).thenReturn(Optional.empty());
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeVersionEntity saved = versionService.createImportVersion(
          1L, parseResult(null, List.of("basicInfo.name")));

      assertThat(saved.getConfirmationStatus())
          .isEqualTo(ResumeVersionEntity.ConfirmationStatus.NEED_USER_INFO);
    }

    @Test
    @DisplayName("V1 已存在时幂等返回既有版本，不重复创建")
    void idempotentOnExistingV1() {
      ResumeVersionEntity existing = new ResumeVersionEntity();
      existing.setVersion(1);
      when(persistenceService.findById(1L)).thenReturn(Optional.of(new ResumeEntity()));
      when(versionRepository.findByResumeIdAndVersion(1L, 1)).thenReturn(Optional.of(existing));

      ResumeVersionEntity result = versionService.createImportVersion(
          1L, parseResult("张三", List.of()));

      assertThat(result).isSameAs(existing);
      verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("简历已被删除时丢弃任务（异步链路实体校验）")
    void discardsTaskWhenResumeDeleted() {
      when(persistenceService.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> versionService.createImportVersion(
          999L, parseResult("张三", List.of())))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.RESUME_NOT_FOUND.getCode());
      verify(versionRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("确认与查询")
  class ConfirmAndQuery {

    @Test
    @DisplayName("确认解析结果：状态转 ACTIVE，可携带修正内容")
    void confirmActivatesVersion() {
      ResumeVersionEntity version = new ResumeVersionEntity();
      version.setId(5L);
      version.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.PENDING_CONFIRMATION);
      version.setContentJson("{\"basicInfo\":{\"name\":null}}");
      when(versionRepository.findById(5L)).thenReturn(Optional.of(version));
      when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ResumeContentJson corrected = new ResumeContentJson(
          new ResumeContentJson.BasicInfo("张三", "13800000000", null, null, null),
          List.of(), List.of(), List.of(), List.of(), List.of());
      ResumeVersionEntity confirmed = versionService.confirmVersion(5L, corrected);

      assertThat(confirmed.getConfirmationStatus())
          .isEqualTo(ResumeVersionEntity.ConfirmationStatus.ACTIVE);
      assertThat(confirmed.getMissingFieldsJson()).isNull();
      assertThat(confirmed.getContentJson()).contains("张三");
      ArgumentCaptor<ResumeVersionEntity> captor =
          ArgumentCaptor.forClass(ResumeVersionEntity.class);
      verify(versionRepository).save(captor.capture());
      assertThat(captor.getValue().getConfirmationStatus())
          .isEqualTo(ResumeVersionEntity.ConfirmationStatus.ACTIVE);
    }

    @Test
    @DisplayName("getActiveVersion 跳过未确认版本，无 ACTIVE 时报错")
    void activeVersionRequiresConfirmation() {
      ResumeVersionEntity pending = new ResumeVersionEntity();
      pending.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.PENDING_CONFIRMATION);
      when(versionRepository.findByResumeIdOrderByVersionDesc(1L))
          .thenReturn(List.of(pending));

      assertThatThrownBy(() -> versionService.getActiveVersion(1L))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.RESUME_VERSION_NOT_READY.getCode());
    }

    @Test
    @DisplayName("getActiveVersion 返回最新的 ACTIVE 版本")
    void activeVersionReturnsLatestActive() {
      ResumeVersionEntity older = new ResumeVersionEntity();
      older.setVersion(1);
      older.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.ACTIVE);
      ResumeVersionEntity pending = new ResumeVersionEntity();
      pending.setVersion(2);
      pending.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.PENDING_CONFIRMATION);
      when(versionRepository.findByResumeIdOrderByVersionDesc(1L))
          .thenReturn(List.of(pending, older));

      assertThat(versionService.getActiveVersion(1L)).isSameAs(older);
    }
  }
}
