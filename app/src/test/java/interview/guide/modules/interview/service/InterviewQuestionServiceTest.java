package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewQuestionService.FollowUpDTO;
import interview.guide.modules.interview.service.InterviewQuestionService.QuestionDTO;
import interview.guide.modules.interview.service.InterviewQuestionService.QuestionListDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P4-1 题库结构化：生成 schema 字段（difficulty/expectedPoints/followUpType）
 * 到 InterviewQuestionDTO 的转换与归一。只测纯转换，不触发 LLM。
 */
@DisplayName("面试问题生成转换（P4-1 题库结构化）")
class InterviewQuestionServiceTest {

  /**
   * 轻量构造：模板从真实 classpath 资源加载，其余依赖 mock，不触发任何 LLM 调用。
   * 这样纯转换逻辑（convertToQuestions / mergeQuestionBatches）可以直接测。
   */
  private static InterviewQuestionService newService() {
    return newService(1);
  }

  /** followUpCount 决定每个主问题保留几条追问（默认 1），需要验证多条追问时显式指定 */
  private static InterviewQuestionService newService(int followUpCount) {
    InterviewQuestionProperties properties = new InterviewQuestionProperties();
    properties.setFollowUpCount(followUpCount);
    try {
      return new InterviewQuestionService(
          mock(StructuredOutputInvoker.class),
          mock(InterviewSkillService.class),
          properties,
          new DefaultResourceLoader(),
          mock(LlmProviderRegistry.class),
          mock(PromptSanitizer.class));
    } catch (Exception e) {
      throw new IllegalStateException("构造 InterviewQuestionService 失败", e);
    }
  }

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
        1, "线上频繁 Full GC 如何排查？", "JVM", "JVM", main.questionId(), 1,
        InterviewQuestionDTO.FOLLOW_UP_SCENARIO, List.of("日志", "heap dump"));
    assertThat(followUp.isFollowUp()).isTrue();
    // P4-1：父链用**稳定标识**表达，不再依赖数组下标
    assertThat(followUp.parentQuestionId()).isEqualTo(main.questionId());
    assertThat(followUp.followUpType()).isEqualTo(InterviewQuestionDTO.FOLLOW_UP_SCENARIO);
    assertThat(followUp.expectedPoints()).containsExactly("日志", "heap dump");
    // 追问不带整体难度（决策引擎依据主问题难度与作答质量决定是否加难）
    assertThat(followUp.difficulty()).isNull();
  }

  @Test
  @DisplayName("withIndex / withFocus 保留结构化字段：素材只被重排与标注，不承载作答")
  void copyMethodsPreserveStructuredFields() {
    InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
        0, "HashMap 扩容机制？", "JAVA", "Java", "扩容", 4,
        List.of("负载因子", "rehash"));

    InterviewQuestionDTO reindexed = main.withIndex(5);
    assertThat(reindexed.difficulty()).isEqualTo(4);
    assertThat(reindexed.expectedPoints()).containsExactly("负载因子", "rehash");
    assertThat(reindexed.questionId()).as("重排不改变稳定标识").isEqualTo(main.questionId());

    InterviewQuestionDTO focused = main.withFocus("项目经历", null);
    assertThat(focused.category()).isEqualTo("项目经历");
    assertThat(focused.difficulty()).isEqualTo(4);
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

  @Test
  @DisplayName("合并简历题与方向题：重排索引但完整保留 difficulty/expectedPoints/followUpType")
  void mergeQuestionBatchesPreservesStructuredMetadata() {
    // 简历题（前半段）：一条主问题
    InterviewQuestionDTO resumeMain = InterviewQuestionDTO.createMain(
        0, "简历里写了订单幂等，具体怎么做的？", "PROJECT", "项目",
        "订单幂等设计", 3, List.of("唯一索引", "去重表"));

    // 方向题（后半段）：主问题 + 追问，带完整结构化元数据
    InterviewQuestionDTO directionMain = InterviewQuestionDTO.createMain(
        0, "Minor GC 与 Full GC 的区别？", "JVM", "JVM", "GC 对比", 4, List.of("分代", "STW"));
    InterviewQuestionDTO directionFollowUp = InterviewQuestionDTO.createFollowUp(
        1, "线上频繁 Full GC 怎么排查？", "JVM", "JVM", directionMain.questionId(), 1,
        InterviewQuestionDTO.FOLLOW_UP_SCENARIO, List.of("jstat", "heap dump"));

    List<InterviewQuestionDTO> merged = InterviewQuestionService.mergeQuestionBatches(
        List.of(resumeMain), List.of(directionMain, directionFollowUp));

    assertThat(merged).hasSize(3);

    // 索引按合并后位置重排
    assertThat(merged).extracting(InterviewQuestionDTO::questionIndex).containsExactly(0, 1, 2);
    // P4-1：父链是稳定标识，合并重排后**不需要**跟着偏移，也不会挂到简历题上
    assertThat(merged.get(2).parentQuestionId()).isEqualTo(directionMain.questionId());

    // 元数据不得丢失：这正是此前用 create(...) 重建造成的缺陷
    assertThat(merged.get(0).difficulty()).isEqualTo(3);
    assertThat(merged.get(0).expectedPoints()).containsExactly("唯一索引", "去重表");

    assertThat(merged.get(1).difficulty()).isEqualTo(4);
    assertThat(merged.get(1).expectedPoints()).containsExactly("分代", "STW");

    assertThat(merged.get(2).isFollowUp()).isTrue();
    assertThat(merged.get(2).followUpType()).isEqualTo(InterviewQuestionDTO.FOLLOW_UP_SCENARIO);
    assertThat(merged.get(2).expectedPoints()).containsExactly("jstat", "heap dump");
    // 追问不仅元数据要保留，内容与分类也必须原样
    assertThat(merged.get(2).question()).isEqualTo("线上频繁 Full GC 怎么排查？");
    // P4Q-6：技能名恒为稳定标识（不拼「（追问N）」），追问身份走独立元数据——
    // 拼进技能名会经 answers.category 变成画像伪技能
    assertThat(merged.get(2).category()).isEqualTo("JVM");
    assertThat(merged.get(2).followUpIndex()).isEqualTo(1);
    assertThat(merged.get(1).followUpIndex()).as("主问题的追问序号为空").isNull();
  }

  @Test
  @DisplayName("出题转换：追问并入主问题技能，序号写入独立元数据（P4Q-6）")
  void followUpUsesStableSkillAndSeparateOrdinal() {
    FollowUpDTO classFollowUp = new FollowUpDTO(
        "堆为什么分代？", InterviewQuestionDTO.FOLLOW_UP_WHY, List.of("分代假设"));
    FollowUpDTO secondFollowUp = new FollowUpDTO(
        "线上 OOM 怎么定位？", InterviewQuestionDTO.FOLLOW_UP_SCENARIO, List.of("heap dump"));

    List<InterviewQuestionDTO> questions = newService(2).convertToQuestions(
        new InterviewQuestionService.QuestionListDTO(List.of(
            new InterviewQuestionService.QuestionDTO(
                "JVM 内存模型？", "JVM", "JVM", "运行时数据区", 4, List.of("堆", "栈"),
                List.of(classFollowUp, secondFollowUp)))),
        3);

    assertThat(questions).hasSize(3);
    InterviewQuestionDTO main = questions.get(0);
    InterviewQuestionDTO firstFollowUp = questions.get(1);
    InterviewQuestionDTO secondFollowUpQuestion = questions.get(2);

    assertThat(main.category()).isEqualTo("JVM");
    assertThat(main.followUpIndex()).isNull();
    // 两条追问与主问题同属一个技能，序号各自独立
    assertThat(List.of(firstFollowUp, secondFollowUpQuestion))
        .allSatisfy(question -> assertThat(question.category()).isEqualTo("JVM"));
    assertThat(firstFollowUp.followUpIndex()).isEqualTo(1);
    assertThat(secondFollowUpQuestion.followUpIndex()).isEqualTo(2);
    assertThat(firstFollowUp.parentQuestionId()).isEqualTo(main.questionId());
    assertThat(secondFollowUpQuestion.parentQuestionId()).isEqualTo(main.questionId());
    // 技能名里不得再出现追问序号（回归护栏）
    assertThat(questions)
        .allSatisfy(question -> assertThat(question.category()).doesNotContain("追问"));
  }

  @Test
  @DisplayName("合并时任一侧为空时原样返回另一侧（不复制、不改索引）")
  void mergeQuestionBatchesHandlesEmptySide() {
    InterviewQuestionDTO main = InterviewQuestionDTO.createMain(
        0, "HashMap 扩容机制？", "JAVA", "Java", "扩容", 4, List.of("负载因子"));

    assertThat(InterviewQuestionService.mergeQuestionBatches(List.of(main), List.of()))
        .containsExactly(main);
    assertThat(InterviewQuestionService.mergeQuestionBatches(List.of(), List.of(main)))
        .containsExactly(main);
  }

  @Test
  @DisplayName("withIndex 保留全部结构化字段，只改索引")
  void withIndexOnlyChangesIndexes() {
    InterviewQuestionDTO followUp = InterviewQuestionDTO.createFollowUp(
        1, "追问内容", "JVM", "JVM", "q-main", 1,
        InterviewQuestionDTO.FOLLOW_UP_WHY, List.of("原理"));

    InterviewQuestionDTO reindexed = followUp.withIndex(7);

    assertThat(reindexed.questionIndex()).isEqualTo(7);
    assertThat(reindexed.parentQuestionId()).as("只改顺序，不动身份与父链").isEqualTo("q-main");
    assertThat(reindexed.followUpType()).isEqualTo(InterviewQuestionDTO.FOLLOW_UP_WHY);
    assertThat(reindexed.expectedPoints()).containsExactly("原理");
    assertThat(reindexed.question()).isEqualTo("追问内容");
  }
}
