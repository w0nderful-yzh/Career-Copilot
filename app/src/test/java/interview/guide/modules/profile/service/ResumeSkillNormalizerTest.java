package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 简历技能条目归一化（P3 待收口）。
 *
 * <p>结构化解析刻意不改写原文，所以 skills[].content 常是一整句话
 * （「熟悉 Java、Spring、MySQL 等主流框架」）；直接当技能名会让画像里出现句子。
 */
@DisplayName("简历技能条目归一化")
class ResumeSkillNormalizerTest {

  @Test
  @DisplayName("按顿号/斜杠/连接词拆分并剥离熟练度修饰")
  void splitsAndStripsModifiers() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("熟悉 Java、Spring、MySQL 等主流框架"), 40);

    assertThat(skills).containsExactly("Java", "Spring", "MySQL");
  }

  @Test
  @DisplayName("斜杠与连接词同样作为分隔符")
  void splitsOnSlashAndConjunctions() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("Java/Python", "Redis 和 Kafka", "Docker以及K8s"), 40);

    assertThat(skills).containsExactly("Java", "Python", "Redis", "Kafka", "Docker", "K8s");
  }

  @Test
  @DisplayName("不按空格拆分：Spring Boot / Spring Cloud 是单个技能名")
  void doesNotSplitOnSpace() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("熟练掌握 Spring Boot、Spring Cloud"), 40);

    assertThat(skills).containsExactly("Spring Boot", "Spring Cloud");
  }

  @Test
  @DisplayName("去掉尾部泛化词（技术栈/经验/技能等）")
  void stripsTrailingGenericWords() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("了解消息队列技术栈", "掌握分布式经验", "使用过单元测试技能"), 40);

    assertThat(skills).containsExactly("消息队列", "分布式", "单元测试");
  }

  @Test
  @DisplayName("去重且大小写不敏感，保留首次出现的写法")
  void deduplicatesCaseInsensitively() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("Java、java", "JAVA"), 40);

    assertThat(skills).containsExactly("Java");
  }

  @Test
  @DisplayName("丢弃空片段、纯数字与过短/过长片段")
  void dropsInvalidTokens() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("、", "2023", "C", "这是一条特别特别长的技能描述超过了长度上限应当被丢弃"), 40);

    assertThat(skills).isEmpty();
  }

  @Test
  @DisplayName("遵守上限，防止超长罗列淹没画像")
  void respectsLimit() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(
        List.of("Redis、Kafka、MySQL、Docker"), 2);

    assertThat(skills).containsExactly("Redis", "Kafka");
  }

  @Test
  @DisplayName("剥掉尾部泛化词后过短的片段会被丢弃（「A技能」剥成「A」）")
  void dropsTokenThatBecomesTooShortAfterStripping() {
    List<String> skills = ResumeSkillNormalizer.normalizeAll(List.of("A技能、Go"), 40);

    assertThat(skills).containsExactly("Go");
  }

  @Test
  @DisplayName("空输入返回空列表，不抛错")
  void handlesEmptyInput() {
    assertThat(ResumeSkillNormalizer.normalizeAll(null, 40)).isEmpty();
    assertThat(ResumeSkillNormalizer.normalizeAll(List.of("", "  "), 40)).isEmpty();
  }
}
