package interview.guide.modules.interview.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * focus 裁剪出题分类（P3 待收口）。
 *
 * <p>最需要守住的是「未命中时不能把分类裁空」：空分类会让出题 prompt 失去依据，
 * 比没聚焦严重得多，因此任何未命中的情况都必须回落到原方向。
 */
@DisplayName("面试方向 focus 裁剪")
class InterviewSkillServiceFocusTest {

  private final InterviewSkillService skillService = newSkillService();

  private static InterviewSkillService newSkillService() {
    try {
      return new InterviewSkillService(
          mock(LlmProviderRegistry.class),
          mock(StructuredOutputInvoker.class),
          new DefaultResourceLoader(),
          mock(PromptSanitizer.class));
    } catch (Exception e) {
      throw new IllegalStateException("构造 SkillService 失败", e);
    }
  }

  private static SkillDTO skill(SkillCategoryDTO... categories) {
    return new SkillDTO("java-backend", "Java 后端开发", "描述", List.of(categories),
        true, null, null, null);
  }

  private static SkillCategoryDTO category(String key, String label) {
    return new SkillCategoryDTO(key, label, "CORE", null, true);
  }

  private static SkillDTO javaBackend() {
    return skill(
        category("JAVA", "Java"),
        category("MYSQL", "MySQL"),
        category("REDIS", "Redis"),
        category("PROJECT", "项目经历"));
  }

  @Test
  @DisplayName("按分类 key 匹配（大小写不敏感）")
  void matchesByKeyCaseInsensitively() {
    SkillDTO focused = skillService.focusOn(javaBackend(), List.of("mysql"));

    assertThat(focused.categories())
        .extracting(SkillCategoryDTO::key)
        .containsExactly("MYSQL");
  }

  @Test
  @DisplayName("按分类 label 匹配：画像里的技能名习惯是 label")
  void matchesByLabel() {
    SkillDTO focused = skillService.focusOn(javaBackend(), List.of("Redis", "项目经历"));

    assertThat(focused.categories())
        .extracting(SkillCategoryDTO::key)
        .containsExactly("REDIS", "PROJECT");
  }

  @Test
  @DisplayName("一个都没命中时返回原方向全量分类，绝不裁成空")
  void unmatchedFocusFallsBackToWholeSkill() {
    SkillDTO focused = skillService.focusOn(javaBackend(), List.of("Kafka", "Elasticsearch"));

    assertThat(focused.categories()).hasSize(4);
  }

  @Test
  @DisplayName("空 focus 原样返回，不做无意义的过滤")
  void emptyFocusReturnsOriginal() {
    SkillDTO original = javaBackend();

    assertThat(skillService.focusOn(original, List.of())).isSameAs(original);
    assertThat(skillService.focusOn(original, null)).isSameAs(original);
  }

  @Test
  @DisplayName("裁剪只改分类，方向标识与元信息保持")
  void keepsSkillIdentity() {
    SkillDTO focused = skillService.focusOn(javaBackend(), List.of("JAVA"));

    assertThat(focused.id()).isEqualTo("java-backend");
    assertThat(focused.name()).isEqualTo("Java 后端开发");
    assertThat(focused.isPreset()).isTrue();
  }

  @Test
  @DisplayName("方向没有分类时不抛错，原样返回")
  void handlesSkillWithoutCategories() {
    SkillDTO empty = skill();

    assertThat(skillService.focusOn(empty, List.of("JAVA"))).isSameAs(empty);
  }
}
