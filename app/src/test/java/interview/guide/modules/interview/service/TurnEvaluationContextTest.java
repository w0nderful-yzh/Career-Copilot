package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.TurnEvaluationRequest;
import interview.guide.modules.interview.service.TurnEvaluationService.TurnEvalDTO;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * 逐轮评估的上下文供给（P4Q-1 批 3）。
 *
 * <p>这里断言的是「我们喂进去了什么」，而不是模型怎么答：模型输出无法稳定断言，
 * 上下文构造却是确定性的。参照物缺失、裁剪越界、把从未发生的轮次当历史，
 * 都会悄悄毁掉 4.3 的判断（沿用户亲历的事追问、没有新信息就转场）——必须由测试守住。
 */
@DisplayName("逐轮评估的上下文供给")
@ExtendWith(MockitoExtension.class)
class TurnEvaluationContextTest {

  @Mock
  private StructuredOutputInvoker invoker;
  @Mock
  private ChatClient chatClient;

  private TurnEvaluationService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new TurnEvaluationService(
        invoker,
        new DefaultResourceLoader(),
        new TurnEvaluationProperties());
  }

  @Nested
  @DisplayName("简历片段裁剪")
  class ResumeSnippet {

    @Test
    @DisplayName("分类匹配到同名小节时只取那一段（不把整份简历塞进逐轮 prompt）")
    void picksMatchingSection() {
      String resume = """
          # 候选人简历（已确认结构化版本 v1）

          ## 技能
          - 语言：Java（熟练）

          ## 项目经历
          - 订单系统 · 开发 · Java / Kafka
          - Kafka 重平衡排查
          """;

      String snippet = TurnEvaluationService.resumeSnippetFor(resume, "项目经历");

      assertThat(snippet).contains("## 项目经历").contains("Kafka 重平衡排查");
      assertThat(snippet).doesNotContain("## 技能");
    }

    @Test
    @DisplayName("匹配不到小节时取开头（求职意向与最近经历信息密度最高）")
    void fallsBackToHead() {
      String resume = "# 候选人简历\n\n## 技能\n- Java\n";

      assertThat(TurnEvaluationService.resumeSnippetFor(resume, "JVM")).startsWith("# 候选人简历");
    }

    @Test
    @DisplayName("超出预算即截断（逐轮上下文直接吃 P95 预算）")
    void truncatedToBudget() {
      String huge = "# 候选人简历\n" + "很长的经历描述。".repeat(400);

      String snippet = TurnEvaluationService.resumeSnippetFor(huge, null);

      assertThat(snippet.length())
          .isLessThanOrEqualTo(TurnEvaluationService.MAX_RESUME_SNIPPET_CHARS + "…（已截断）".length());
      assertThat(snippet).endsWith("（已截断）");
    }

    @Test
    @DisplayName("没有简历依据时返回 null（由提示词给可读占位，不编造经历）")
    void nullWithoutResume() {
      assertThat(TurnEvaluationService.resumeSnippetFor(null, "Java")).isNull();
      assertThat(TurnEvaluationService.resumeSnippetFor("   ", "Java")).isNull();
    }
  }

  @Nested
  @DisplayName("最近相关问答")
  class RecentTurns {

    @Test
    @DisplayName("只取「已发生」的轮次：候选择问从未发生过，不能当成用户说过的话")
    void onlyAskedTurns() {
      List<InterviewQuestionDTO> questions = List.of(
          answered(0, "Q1: JVM 内存模型？", "JVM", "堆分新生代与老年代"),
          followUpCandidate(1, "F1: 堆为什么分代？", "JVM"),
          InterviewQuestionDTO.createMain(
              2, "Q2: Redis 持久化？", "REDIS", "Redis", null, 3, List.of()));

      List<String> turns = TurnEvaluationService.recentTurnsFor(questions, 2, "JVM");

      assertThat(turns).hasSize(1);
      assertThat(turns.get(0)).contains("JVM 内存模型").contains("新生代");
    }

    @Test
    @DisplayName("最多取 2 轮且取最近的，展示顺序仍是时间正序")
    void capsAndKeepsChronologicalOrder() {
      List<InterviewQuestionDTO> questions = List.of(
          answered(0, "Q1", "JVM", "答1"),
          answered(1, "Q2", "JVM", "答2"),
          answered(2, "Q3", "JVM", "答3"));

      List<String> turns = TurnEvaluationService.recentTurnsFor(questions, 2, "JVM");

      assertThat(turns).hasSize(2);
      assertThat(turns.get(0)).contains("Q1").contains("答1");
      assertThat(turns.get(1)).contains("Q2").contains("答2");
    }

    @Test
    @DisplayName("只取同技能的轮次：跨技能的历史会把无关内容当成参照")
    void filteredBySkill() {
      List<InterviewQuestionDTO> questions = List.of(
          answered(0, "Q1: Redis 持久化？", "Redis", "RDB 和 AOF"));

      assertThat(TurnEvaluationService.recentTurnsFor(questions, 1, "JVM")).isEmpty();
    }
  }

  @Nested
  @DisplayName("提示词变量")
  class PromptVariables {

    @Test
    @DisplayName("三类参照物都进入 user prompt")
    void contextReachesPrompt() throws Exception {
      stubEvaluation();

      service.evaluateTurn(chatClient, new TurnEvaluationRequest(
          question(), "回答内容……",
          "## 项目经历\n- 订单系统 · Kafka 重平衡排查",
          List.of("- 问：Q1 内存模型\n  答：堆分新生代"),
          "订单系统 44 分（2 条证据）"));

      assertThat(capturedUserPrompt())
          .contains("Kafka 重平衡排查")
          .contains("堆分新生代")
          .contains("订单系统 44 分");
    }

    @Test
    @DisplayName("参照物缺失时给出可读占位，而不是留白让模型自己猜")
    void missingContextGetsReadablePlaceholder() throws Exception {
      stubEvaluation();

      service.evaluateTurn(chatClient, TurnEvaluationRequest.of(question(), "回答内容……"));

      assertThat(capturedUserPrompt())
          .contains("没有简历依据")
          .contains("没有已回答的轮次")
          .contains("暂无画像数据");
    }

    /** 用 mock invoker 返回 DTO，不触发真实 LLM */
    private void stubEvaluation() {
      when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(new TurnEvalDTO(80, "GOOD", List.of(), List.of(), "", null));
    }

    private String capturedUserPrompt() {
      ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
      verify(invoker).invoke(any(), any(), userPrompt.capture(), any(), any(), any(), any(), any());
      return userPrompt.getValue();
    }
  }

  private static InterviewQuestionDTO question() {
    return InterviewQuestionDTO.createMain(0,
        "Minor GC 与 Full GC 有什么区别？", "JVM", "JVM", "GC 对比", 3,
        List.of("触发条件", "发生区域"));
  }

  /** 已作答的一轮（答案状态为 ANSWERED，才可能进历史参照） */
  private static InterviewQuestionDTO answered(
      int index, String question, String category, String answer) {
    return InterviewQuestionDTO.createMain(index, question, category.toUpperCase(Locale.ROOT),
            category, null, 3, List.of())
        .withAnswer(answer)
        .withAnswerState(InterviewAnswerEntity.AnswerState.ANSWERED);
  }

  /** 候选择问：在题单里存在，但从未被问到（没有作答记录） */
  private static InterviewQuestionDTO followUpCandidate(
      int index, String question, String category) {
    return InterviewQuestionDTO.createFollowUp(index, question, category.toUpperCase(Locale.ROOT),
        category, 0, 1, "WHY", List.of());
  }
}
