package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * 面试简历上下文解析（P4Q-1）。
 *
 * <p>要解决的问题：出题依据此前完全取决于调用方传了什么文本，而 Agent 侧恒传 null，
 * 于是「简历驱动的面试」从未生效。现在取数规则唯一：
 * **明确指定版本 → 简历 ACTIVE 结构化版本 → 原文（标明来源）→ 无（通用面试）**。
 *
 * <p>另一条同等重要：指定了简历却读不到时**必须报错并给出原因**，不能静默变成通用面试——
 * 用户以为在面自己的简历，拿到的却是通用题，是最糟的失败方式。
 */
@DisplayName("面试简历上下文解析")
@ExtendWith(MockitoExtension.class)
class InterviewResumeContextResolverTest {

  @Mock
  private ResumeVersionService resumeVersionService;
  @Mock
  private ResumePersistenceService resumePersistenceService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private InterviewResumeContextResolver resolver() {
    return new InterviewResumeContextResolver(
        resumeVersionService, resumePersistenceService, objectMapper);
  }

  @Nested
  @DisplayName("取数优先级")
  class Priority {

    @Test
    @DisplayName("有 ACTIVE 结构化版本：用它，且文本来自确认过的内容")
    void prefersActiveStructuredVersion() {
      when(resumeVersionService.getActiveVersion(7L)).thenReturn(version(7L, 2, structured()));

      InterviewResumeContext context = resolver().resolve(7L, null, null);

      assertThat(context.source()).isEqualTo(InterviewResumeContext.Source.RESUME_VERSION);
      assertThat(context.resumeId()).isEqualTo(7L);
      assertThat(context.version()).isEqualTo(2);
      assertThat(context.text())
          .contains("Kafka 重平衡排查")
          .contains("个人负责消费端幂等改造");
    }

    @Test
    @DisplayName("明确指定版本时用该版本，不取 ACTIVE")
    void usesExplicitVersion() {
      when(resumeVersionService.getByResumeVersion(7L, 1)).thenReturn(version(7L, 1, structured()));

      InterviewResumeContext context = resolver().resolve(7L, 1, null);

      assertThat(context.version()).isEqualTo(1);
      verify(resumeVersionService, never()).getActiveVersion(anyLong());
    }

    @Test
    @DisplayName("没有可用结构化版本：降级到简历原文，并如实标明来源")
    void fallsBackToPlainText() {
      when(resumeVersionService.getActiveVersion(7L))
          .thenThrow(new BusinessException(ErrorCode.RESUME_VERSION_NOT_READY, "还没有已确认的版本"));
      ResumeEntity resume = new ResumeEntity();
      resume.setId(7L);
      resume.setResumeText("原始解析文本：某公司实习，负责订单系统");
      when(resumePersistenceService.findById(7L)).thenReturn(Optional.of(resume));

      InterviewResumeContext context = resolver().resolve(7L, null, null);

      assertThat(context.source()).isEqualTo(InterviewResumeContext.Source.RESUME_TEXT);
      assertThat(context.resumeId()).isEqualTo(7L);
      assertThat(context.version()).isNull();
      assertThat(context.text()).contains("负责订单系统");
    }

    @Test
    @DisplayName("没有简历 ID 但有显式文本：走显式文本通道")
    void explicitTextChannel() {
      InterviewResumeContext context = resolver().resolve(null, null, "手动粘贴的简历内容");

      assertThat(context.source()).isEqualTo(InterviewResumeContext.Source.EXPLICIT_TEXT);
      assertThat(context.resumeId()).isNull();
      assertThat(context.hasText()).isTrue();
    }

    @Test
    @DisplayName("什么都没有：通用面试（来源 NONE，不编造简历内容）")
    void noneWithoutAnyResume() {
      InterviewResumeContext context = resolver().resolve(null, null, null);

      assertThat(context.source()).isEqualTo(InterviewResumeContext.Source.NONE);
      assertThat(context.hasText()).isFalse();
      verify(resumePersistenceService, never()).findById(anyLong());
    }

    @Test
    @DisplayName("同时给了 resumeId 与显式文本：以 resumeId 为准（两条路径不再各取一半）")
    void resumeIdWinsOverExplicitText() {
      when(resumeVersionService.getActiveVersion(7L)).thenReturn(version(7L, 2, structured()));

      InterviewResumeContext context = resolver().resolve(7L, null, "调用方自己拼的文本");

      assertThat(context.source()).isEqualTo(InterviewResumeContext.Source.RESUME_VERSION);
      assertThat(context.text()).doesNotContain("调用方自己拼的文本");
    }
  }

  @Nested
  @DisplayName("读不到时给出可见原因")
  class VisibleFailure {

    @Test
    @DisplayName("简历不存在：报简历不存在，不静默变通用面试")
    void resumeNotFound() {
      when(resumeVersionService.getActiveVersion(99L))
          .thenThrow(new BusinessException(ErrorCode.RESUME_VERSION_NOT_READY, "还没有已确认的版本"));
      when(resumePersistenceService.findById(99L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> resolver().resolve(99L, null, null))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.RESUME_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("简历存在但没有可用文本：报错点明下一步（重新上传或先完成解析确认）")
    void resumeWithoutUsableText() {
      when(resumeVersionService.getActiveVersion(7L))
          .thenThrow(new BusinessException(ErrorCode.RESUME_VERSION_NOT_READY, "还没有已确认的版本"));
      ResumeEntity resume = new ResumeEntity();
      resume.setId(7L);
      resume.setResumeText("   ");
      when(resumePersistenceService.findById(7L)).thenReturn(Optional.of(resume));

      assertThatThrownBy(() -> resolver().resolve(7L, null, null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("解析确认");
    }

    @Test
    @DisplayName("指定版本不存在：直接抛出，不退化成原文或通用面试")
    void explicitVersionNotFound() {
      when(resumeVersionService.getByResumeVersion(7L, 9))
          .thenThrow(new BusinessException(ErrorCode.RESUME_VERSION_NOT_FOUND, "版本不存在"));

      assertThatThrownBy(() -> resolver().resolve(7L, 9, null))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.RESUME_VERSION_NOT_FOUND.getCode());
    }
  }

  @Nested
  @DisplayName("结构化版本 → 出题文本")
  class Rendering {

    @Test
    @DisplayName("只呈现确实存在的段落：空的经历/技能不出现标题（不编造、不留空壳）")
    void omitsEmptySections() {
      ResumeContentJson sparse = new ResumeContentJson(
          new ResumeContentJson.BasicInfo("张三", null, null, null, "Java 后端实习"),
          List.of(),
          List.of(),
          List.of(new ResumeContentJson.ProjectItem(
              "求职助手", "后端", "2026-03", "2026-06", "Java / Spring",
              List.of("负责画像聚合模块"))),
          List.of(),
          List.of());

      when(resumeVersionService.getActiveVersion(7L)).thenReturn(version(7L, 1, sparse));

      String text = resolver().resolve(7L, null, null).text();

      assertThat(text).contains("## 项目经历").contains("负责画像聚合模块");
      assertThat(text).doesNotContain("## 教育经历").doesNotContain("## 技能").doesNotContain("## 其他");
    }

    @Test
    @DisplayName("经历与项目要点逐条保留，技术栈带上（出题要靠它选追问方向）")
    void keepsFactsAndTechStack() {
      when(resumeVersionService.getActiveVersion(7L)).thenReturn(version(7L, 3, structured()));

      String text = resolver().resolve(7L, null, null).text();

      assertThat(text).contains("v3");
      assertThat(text).contains("技术栈：Java / Kafka");
      assertThat(text).contains("个人负责消费端幂等改造");
    }
  }

  private ResumeVersionEntity version(Long resumeId, int version, ResumeContentJson content) {
    ResumeVersionEntity entity = new ResumeVersionEntity();
    entity.setResumeId(resumeId);
    entity.setVersion(version);
    entity.setContentJson(objectMapper.writeValueAsString(content));
    return entity;
  }

  private static ResumeContentJson structured() {
    return new ResumeContentJson(
        new ResumeContentJson.BasicInfo("张三", null, null, null, "Java 后端实习"),
        List.of(new ResumeContentJson.EducationItem("某大学", "软件工程", "本科", "2022-09", "2026-06", null)),
        List.of(new ResumeContentJson.ExperienceItem(
            "某公司", "后端实习生", "2025-07", "2025-10",
            List.of("个人负责消费端幂等改造"))),
        List.of(new ResumeContentJson.ProjectItem(
            "订单系统", "开发", "2025-03", "2025-06", "Java / Kafka",
            List.of("Kafka 重平衡排查"))),
        List.of(new ResumeContentJson.SkillItem("语言", "Java（熟练）")),
        List.of());
  }
}
