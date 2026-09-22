package interview.guide.modules.profile.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 简历技能条目归一化：把结构化简历的 {@code skills[].content} 拆成技能名列表。
 *
 * <p>为什么需要它：结构化解析刻意「不改写表述」，所以 skills 的 content 保留原文
 * （形如「熟悉 Java、Spring、MySQL 等主流框架」），直接当技能名会让画像里出现一整句话。
 * 这里做**确定性**拆分与去修饰，不引入 LLM——技能名会进入 focus 选择与画像展示，
 * 不该由模型即席生成（编造风险 + 不可复现）。
 *
 * <p>刻意不做的事：
 * <ul>
 *   <li>不按空格拆分——「Spring Boot」「Spring Cloud」是单个技能名；
 *   <li>不做同义词归并（JVM → Java 等）——归并需要方向上下文，交给提案节点的语义判断，
 *       这里只保证「拆出来的名字干净」。
 * </ul>
 */
final class ResumeSkillNormalizer {

  /** 技能条目的分隔符（中英文顿号/逗号/分号/斜杠/竖线/中点） */
  private static final Pattern SEPARATOR = Pattern.compile("[、,，;；/|·]");

  /** 「和/及/以及」作为并列连接词时也起分隔作用 */
  private static final Pattern CONJUNCTION = Pattern.compile("以及|和|及");

  /**
   * 修饰词：按长度倒序匹配，避免「熟练」先于「熟练掌握」命中导致残留。
   * 只剥离开头的修饰，不动词干本身。
   */
  private static final List<String> LEADING_MODIFIERS = List.of(
      "熟练掌握", "熟练使用", "熟练运用", "熟练", "精通", "熟悉", "掌握", "了解", "会用",
      "使用过", "使用", "具备", "具有良好的", "具有", "良好的", "扎实的", "基本的", "较强的",
      "一定的", "相关的", "相关", "对", "有");

  /** 尾部泛化词：剥离后剩下的主体才是技能名 */
  private static final List<String> TRAILING_MODIFIERS = List.of(
      "等相关技术", "相关技术", "相关技能", "相关经验", "使用经验", "开发经验", "等主流框架",
      "主流框架", "技术栈", "等技能", "等技术", "等工具", "等框架", "技能", "技术", "经验",
      "能力", "等");

  /** 「等」之后的内容一律视为泛化描述，直接截断 */
  private static final char ENUM_TAIL = '等';

  private static final int MIN_LENGTH = 2;
  private static final int MAX_LENGTH = 24;

  private ResumeSkillNormalizer() {
  }

  /**
   * 归一化一组技能条目内容。
   *
   * @param contents 结构化简历 skills[].content（原文表述）
   * @param limit    最多产出多少条技能名
   * @return 去重后的技能名（保持首次出现顺序）
   */
  static List<String> normalizeAll(List<String> contents, int limit) {
    Map<String, String> unique = new LinkedHashMap<>();
    if (contents == null) {
      return List.of();
    }
    for (String content : contents) {
      for (String candidate : split(content)) {
        String skill = clean(candidate);
        if (skill == null) {
          continue;
        }
        unique.putIfAbsent(skill.toLowerCase(), skill);
        if (unique.size() >= limit) {
          return new ArrayList<>(unique.values());
        }
      }
    }
    return new ArrayList<>(unique.values());
  }

  /** 按分隔符与并列连接词拆分为候选片段 */
  private static List<String> split(String content) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    List<String> parts = new ArrayList<>();
    for (String bySeparator : SEPARATOR.split(content)) {
      for (String byConjunction : CONJUNCTION.split(bySeparator)) {
        parts.add(byConjunction);
      }
    }
    return parts;
  }

  /** 剥离修饰、截断泛化尾巴，返回可用作技能名的片段；不合格返回 null */
  private static String clean(String raw) {
    String text = raw == null ? "" : raw.trim();
    if (text.isEmpty()) {
      return null;
    }

    // 「等」之后是泛化描述（如「MySQL 等主流框架」），直接截断
    int enumIndex = text.indexOf(ENUM_TAIL);
    if (enumIndex > 0) {
      text = text.substring(0, enumIndex).trim();
    }

    text = stripLeading(text);
    text = stripTrailing(text);
    text = trimPunctuation(text);

    if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
      return null;
    }
    // 纯数字/纯符号不是技能名
    if (!text.codePoints().anyMatch(Character::isLetter)) {
      return null;
    }
    return text;
  }

  /** 反复剥离开头修饰词（「熟悉掌握 Redis」这类叠加表述） */
  private static String stripLeading(String text) {
    String current = text;
    boolean changed = true;
    while (changed && !current.isEmpty()) {
      changed = false;
      for (String modifier : LEADING_MODIFIERS) {
        if (current.startsWith(modifier) && current.length() > modifier.length()) {
          current = current.substring(modifier.length()).trim();
          changed = true;
          break;
        }
      }
    }
    return current;
  }

  /** 反复剥离尾部泛化词 */
  private static String stripTrailing(String text) {
    String current = text;
    boolean changed = true;
    while (changed && !current.isEmpty()) {
      changed = false;
      for (String modifier : TRAILING_MODIFIERS) {
        if (current.endsWith(modifier) && current.length() > modifier.length()) {
          current = current.substring(0, current.length() - modifier.length()).trim();
          changed = true;
          break;
        }
      }
    }
    return current;
  }

  /** 去掉首尾残留的标点与空白 */
  private static String trimPunctuation(String text) {
    return text.replaceAll("^[\\s\\p{Punct}、。：:；;，,]+", "")
        .replaceAll("[\\s\\p{Punct}、。：:；;，,]+$", "");
  }
}
