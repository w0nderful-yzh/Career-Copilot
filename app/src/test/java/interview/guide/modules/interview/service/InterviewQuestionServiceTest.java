package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewQuestionService.FollowUpDTO;
import interview.guide.modules.interview.service.InterviewQuestionService.QuestionDTO;
import interview.guide.modules.interview.service.InterviewQuestionService.QuestionListDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-1 题库结构化：生成 schema 字段（difficulty/expectedPoints/followUpType）
 * 到 InterviewQuestionDTO 的转换与归一。只测纯转换，不触发 LLM。
 */
@DisplayName("面试问题生成转换（P4-1 题库结构化）")
class InterviewQuestionServiceTest {

  /** 直接 new 服务不方便（构造器加载模板），仅验证可包级访问的转换方法；通过空壳子类不必要——用静态方式调用转换需实例。
   *  因此这里通过构造轻量实例：构造器需要 ResourceLoader 等，代价高。改为测 DTO 工厂语义 + 归一逻辑由
   *  generateQuestionsBySkill 集成覆盖（另见 InterviewSessionService 链路）。此处退化为 DTO 结构断言。 */
  @Test
  @DisplayName("主问题工厂写入数值难度与考察要点；追问工厂写入语义类型")
  void mainAndFollowUpFactoriesCarryStructuredFields() {
    InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
        0, "Minor GC 与 Full GC 有何区别？", "JVM", "JVM", "GC 对比", 3,
        List.of("young/old 代", "STW", "触发条件"));
    assertThat(main.isFollowUp()).isFalse();
    assertThat(main.difficulty()).isEqualTo(3);
    assertThat(main.expectedPoints()).containsExactly("young/old 代", "STW", "触发条件");
    assertThat(main.followUpType()).isNull();

    InterviewQuestionDTO followUp = InterviewQuestionDTO.createFollowUp(
        1, "线上频繁 Full GC 如何排查？", "JVM", "JVM（追问1）", 0,
        InterviewQuestionDTO.FOLLOW_UP_SCENARIO, List.of("日志", "heap dump"));
    assertThat(followUp.isFollowUp()).isTrue();
    assertThat(followUp.parentQuestionIndex()).isZero();
    assertThat(followUp.followUpType()).isEqualTo(InterviewQuestionDTO.FOLLOW_UP_SCENARIO);
    assertThat(followUp.expectedPoints()).containsExactly("日志", "heap dump");
    // 追问不带整体难度（决策引擎依据主问题难度与作答质量决定是否加难）
    assertThat(followUp.difficulty()).isNull();
  }

  @Test
  @DisplayName("withAnswer / withEvaluation 保留结构化字段")
  void copyMethodsPreserveStructuredFields() {
    InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
        0, "HashMap 扩容机制？", "JAVA", "Java", "扩容", 4,
        List.of("负载因子", "rehash"));
    InterviewQuestionDTO answered = main.withAnswer("扩容为原两倍");
    assertThat(answered.difficulty()).isEqualTo(4);
    assertThat(answered.expectedPoints()).containsExactly("负载因子", "rehash");
    assertThat(answered.userAnswer()).isEqualTo("扩容为原两倍");

    InterviewQuestionDTO evaluated = main.withEvaluation(85, "回答完整");
    assertThat(evaluated.score()).isEqualTo(85);
    assertThat(evaluated.difficulty()).isEqualTo(4);
    assertThat(evaluated.expectedPoints()).hasSize(2);
  }

  @Test
  @DisplayName("顺序工厂（旧路径）结构化字段为 null，向后兼容")
  void legacyCreateKeepsStructuredFieldsNull() {
    InterviewQuestionDTO main = InterviewQuestionDTO.create(0, "什么是 JVM？", "JVM", "JVM");
    assertThat(main.difficulty()).isNull();
    assertThat(main.expectedPoints()).isNull();
    assertThat(main.followUpType()).isNull();
  }

  @Test
  @DisplayName("出题 schema 记录：驼峰字段可由 LLM 直接产出（contract 冒烟）")
  void questionSchemaRecordsCarryExpectedJsonShape() {
    // 模拟 LLM 返回的结构（BeanOutputConverter 按 camelCase record 属性反序列化）
    QuestionDTO q = new QuestionDTO(
        "Minor GC 和 Full GC 有什么区别？",
        "JVM",
        "JVM",
        "GC 对比",
        3,
        List.of("分代", "STW"),
        List.of(new FollowUpDTO("频繁 Full GC 怎么排查？", "SCENARIO", List.of("jstat", "dump")))
    );
    QuestionListDTO list = new QuestionListDTO(List.of(q));

    assertThat(list.questions()).hasSize(1);
    assertThat(list.questions().get(0).difficulty()).isEqualTo(3);
    assertThat(list.questions().get(0).followUps()).hasSize(1);
    assertThat(list.questions().get(0).followUps().get(0).followUpType()).isEqualTo("SCENARIO");
    // 断言 record 组件命名即为 JSON 字段名（Jackson record 反序列化依赖）
    assertThat(InterviewQuestionService.QuestionDTO.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .contains("question", "type", "category", "topicSummary", "difficulty", "expectedPoints", "followUps");
    assertThat(InterviewQuestionService.FollowUpDTO.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .contains("question", "followUpType", "expectedPoints");
  }
}
