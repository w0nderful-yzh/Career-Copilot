package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 证据技能名归一化（P4Q-6）。
 *
 * <p>历史数据把追问序号拼进了技能名（`Java（追问1）`），修复与读取都要先归一化；
 * 归一化必须严格（只有「（追问N）」后缀才剥），否则会误伤恰好含「追问」二字的正常技能名。
 */
@DisplayName("证据技能名归一化")
class SkillNameNormalizerTest {

  @Test
  @DisplayName("剥离全角括号的追问序号后缀")
  void stripsFullWidthSuffix() {
    assertThat(SkillNameNormalizer.normalize("Java（追问1）")).isEqualTo("Java");
    assertThat(SkillNameNormalizer.normalize("系统设计/场景题（追问2）")).isEqualTo("系统设计/场景题");
  }

  @Test
  @DisplayName("半角括号与序号周边空白同样识别")
  void stripsHalfWidthAndSpaces() {
    assertThat(SkillNameNormalizer.normalize("MySQL(追问3)")).isEqualTo("MySQL");
    assertThat(SkillNameNormalizer.normalize("Redis（ 追问 1 ）")).isEqualTo("Redis");
  }

  @Test
  @DisplayName("稳定技能名原样返回（不去空格以外的加工）")
  void keepsStableSkillUnchanged() {
    assertThat(SkillNameNormalizer.normalize("Java")).isEqualTo("Java");
    assertThat(SkillNameNormalizer.normalize("  MySQL  ")).isEqualTo("MySQL");
    assertThat(SkillNameNormalizer.normalize(null)).isNull();
    assertThat(SkillNameNormalizer.normalize("   ")).isEmpty();
  }

  @Test
  @DisplayName("不误伤含「追问」二字但不是后缀形式的技能名")
  void doesNotTouchNonSuffixNames() {
    assertThat(SkillNameNormalizer.normalize("追问技巧")).isEqualTo("追问技巧");
    assertThat(SkillNameNormalizer.normalize("Java（追问）")).isEqualTo("Java（追问）");
    assertThat(SkillNameNormalizer.normalize("Java（讨论1）")).isEqualTo("Java（讨论1）");
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill("追问技巧")).isFalse();
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill("Java（追问）")).isFalse();
  }

  @Test
  @DisplayName("整个名字就是追问序号的异常值不产出空技能名")
  void doesNotProduceEmptySkill() {
    assertThat(SkillNameNormalizer.normalize("（追问1）")).isEqualTo("（追问1）");
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill("（追问1）"))
        .as("后缀形式成立，但剥完为空，由调用方按原值处理")
        .isFalse();
  }

  @Test
  @DisplayName("伪技能判定与归一化一致：仅后缀形式为 true")
  void pseudoSkillPredicateMatchesSuffixOnly() {
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill("Java（追问1）")).isTrue();
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill("Java(追问12)")).isTrue();
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill("Java")).isFalse();
    assertThat(SkillNameNormalizer.isFollowUpPseudoSkill(null)).isFalse();
  }
}
