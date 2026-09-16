package interview.guide.modules.profile.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据技能名归一化（P4Q-6）。
 *
 * <p>历史数据把追问序号拼进了技能名（`Java（追问1）`），该字符串经
 * `interview_answers.category` 直接成了画像证据的技能名，于是画像里出现伪技能。
 * 出题侧已改为用独立元数据表达追问序号，这里负责**兼容历史分类后缀**：
 * 读取与修复都先过一遍归一化，保证同一条跟踪链路里技能名恒为稳定标识。
 */
final class SkillNameNormalizer {

  /** 追问序号后缀：全角/半角括号、序号前后可带空白 */
  private static final Pattern FOLLOW_UP_SUFFIX =
      Pattern.compile("^(.*?)[（(]\\s*追问\\s*\\d+\\s*[）)]$");

  private SkillNameNormalizer() {
  }

  /** 去掉追问序号后缀，返回稳定技能名；无后缀时原样返回（仅去首尾空白） */
  static String normalize(String skill) {
    if (skill == null) {
      return null;
    }
    String trimmed = skill.trim();
    if (trimmed.isEmpty()) {
      return trimmed;
    }
    Matcher matcher = FOLLOW_UP_SUFFIX.matcher(trimmed);
    if (!matcher.matches()) {
      return trimmed;
    }
    String base = matcher.group(1).trim();
    // 后缀式技能名剥完为空（例如整个名字就是「追问1」）时不产出空技能名
    return base.isEmpty() ? trimmed : base;
  }

  /**
   * 是否为「可归一化的」追问伪技能。
   *
   * <p>要求后缀之前还有主体（`Java（追问1）` 是；整个名字就是 `（追问1）` 不是）——
   * 该判据被修复例程当作「能否安全改名」的守卫，剥完为空的名字不属于可修复对象。
   */
  static boolean isFollowUpPseudoSkill(String skill) {
    if (skill == null) {
      return false;
    }
    Matcher matcher = FOLLOW_UP_SUFFIX.matcher(skill.trim());
    return matcher.matches() && !matcher.group(1).trim().isEmpty();
  }
}
