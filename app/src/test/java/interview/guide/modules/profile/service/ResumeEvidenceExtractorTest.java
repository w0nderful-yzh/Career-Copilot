package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.resume.model.ResumeContentJson;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 简历 → 声明型证据（P3 待收口）。
 *
 * <p>关键约束：简历侧没有逐技能分，因此声明证据**必须无分**——给未验证的技能编一个分数
 * 会让画像分不再能由证据还原。
 */
@DisplayName("简历声明型证据提取")
class ResumeEvidenceExtractorTest {

  private final ResumeEvidenceExtractor extractor = new ResumeEvidenceExtractor();
  private final LocalDateTime declaredAt = LocalDateTime.of(2026, 9, 15, 10, 0);

  private static ResumeContentJson content(List<ResumeContentJson.SkillItem> skills) {
    return new ResumeContentJson(null, List.of(), List.of(), List.of(), skills, List.of());
  }

  @Test
  @DisplayName("技能条目转成无分声明证据，来源类型为 RESUME、sourceId 为 resumeId")
  void extractsDeclarationsWithoutScore() {
    ResumeContentJson resume = content(List.of(
        new ResumeContentJson.SkillItem("语言", "熟悉 Java、Go"),
        new ResumeContentJson.SkillItem("中间件", "Redis")));

    List<SkillEvidenceEntity> declarations = extractor.extract(7L, resume, declaredAt);

    assertThat(declarations)
        .extracting(SkillEvidenceEntity::getSkill, SkillEvidenceEntity::getSourceType,
            SkillEvidenceEntity::getScore, SkillEvidenceEntity::getSourceId)
        .containsExactly(
            tuple("Java", EvidenceSourceType.RESUME, null, "7"),
            tuple("Go", EvidenceSourceType.RESUME, null, "7"),
            tuple("Redis", EvidenceSourceType.RESUME, null, "7"));
    assertThat(declarations)
        .allSatisfy(evidence -> assertThat(evidence.getOccurredAt()).isEqualTo(declaredAt));
  }

  @Test
  @DisplayName("无技能条目 / 无简历 ID 时返回空，不产生脏证据")
  void skipsWhenNothingToDeclare() {
    assertThat(extractor.extract(7L, content(List.of()), declaredAt)).isEmpty();
    assertThat(extractor.extract(7L, null, declaredAt)).isEmpty();
    assertThat(extractor.extract(null, content(List.of(
        new ResumeContentJson.SkillItem("语言", "Java"))), declaredAt)).isEmpty();
  }

  @Test
  @DisplayName("遵守声明数量上限")
  void respectsMaxDeclarations() {
    List<ResumeContentJson.SkillItem> items = new ArrayList<>();
    for (int i = 0; i < ResumeEvidenceExtractor.MAX_DECLARATIONS + 10; i++) {
      items.add(new ResumeContentJson.SkillItem("语言", "技能" + i));
    }

    assertThat(extractor.extract(7L, content(items), declaredAt))
        .hasSize(ResumeEvidenceExtractor.MAX_DECLARATIONS);
  }
}
