package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.TurnEvaluationRequest;
import interview.guide.modules.interview.service.TurnEvaluationService.TurnEvalDTO;
import java.util.ArrayList;
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
        new TurnEvaluationProperties(),
        new StructuredOutputProperties());
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
    @DisplayName("只取真实发生过的轮次：候选素材里的选择题不在轨迹里，自然不会当成用户说过的话")
    void onlyAskedTurns() {
      List<InterviewTurnDTO> turns = List.of(
          answeredTurn(1, "Q1: JVM 内存模型？", "JVM", "堆分新生代与老年代"));

      List<String> rendered = TurnEvaluationService.recentTurnsFor(turns, "JVM");

      assertThat(rendered).hasSize(1);
      assertThat(rendered.get(0)).contains("JVM 内存模型").contains("新生代");
    }

    @Test
    @DisplayName("最多取 2 轮且取最近的，展示顺序仍是时间正序")
    void capsAndKeepsChronologicalOrder() {
      // 调用方传进来的轨迹已排除当前轮，因此这里取最后两轮
      List<InterviewTurnDTO> turns = List.of(
          answeredTurn(1, "Q1", "JVM", "答1"),
          answeredTurn(2, "Q2", "JVM", "答2"),
          answeredTurn(3, "Q3", "JVM", "答3"));

      List<String> rendered = TurnEvaluationService.recentTurnsFor(turns, "JVM");

      assertThat(rendered).hasSize(2);
      assertThat(rendered.get(0)).contains("Q2").contains("答2");
      assertThat(rendered.get(1)).contains("Q3").contains("答3");
    }

    @Test
    @DisplayName("只取同技能的轮次：跨技能的历史会把无关内容当成参照")
    void filteredBySkill() {
      List<InterviewTurnDTO> turns = List.of(
          answeredTurn(1, "Q1: Redis 持久化？", "Redis", "RDB 和 AOF"));

      assertThat(TurnEvaluationService.recentTurnsFor(turns, "JVM")).isEmpty();
    }

    @Test
    @DisplayName("跳过与未作答的轮次不作为历史参照（没有作答内容就没有参照价值）")
    void skipsNonAnswers() {
      List<InterviewTurnDTO> turns = List.of(
          new InterviewTurnDTO("q1", 1, 0, "Q1: JVM 内存模型？", "JVM", null, null,
              InterviewAnswerEntity.AnswerState.SKIPPED, null, null,
              InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null));

      assertThat(TurnEvaluationService.recentTurnsFor(turns, "JVM")).isEmpty();
    }
  }

  @Nested
  @DisplayName("提示词变量")
  class PromptVariables {

    @Test
    @DisplayName("三类参照物与覆盖 / 预算 / 合法候选都进入 user prompt")
    void contextReachesPrompt() throws Exception {
      stubEvaluation();

      service.evaluateTurn(chatClient, new TurnEvaluationRequest(
          question(), "回答内容……",
          "## 项目经历\n- 订单系统 · Kafka 重平衡排查",
          List.of("- 问：Q1 内存模型\n  答：堆分新生代"),
          "订单系统 44 分（2 条证据）",
          "- 必要覆盖：JVM=本轮正在考察",
          "- 时间预算：剩余约 11 分钟\n- 追问预算：当前话题组最多还可追问 1 条",
          List.of("[q-f1] 追问｜JVM｜难度3：堆为什么分代？")));

      assertThat(capturedUserPrompt())
          .contains("Kafka 重平衡排查")
          .contains("堆分新生代")
          .contains("订单系统 44 分")
          .contains("必要覆盖：JVM=本轮正在考察")
          .contains("剩余约 11 分钟")
          .contains("[q-f1] 追问｜JVM");
    }

    @Test
    @DisplayName("参照物缺失时给出可读占位，而不是留白让模型自己猜")
    void missingContextGetsReadablePlaceholder() throws Exception {
      stubEvaluation();

      service.evaluateTurn(chatClient, TurnEvaluationRequest.of(question(), "回答内容……"));

      assertThat(capturedUserPrompt())
          .contains("没有简历依据")
          .contains("没有已回答的轮次")
          .contains("暂无画像数据")
          .contains("没有提供覆盖上下文")
          .contains("没有提供时间与追问预算")
          .contains("没有可继续问的候选");
    }

    /** 用 mock invoker 返回 DTO，不触发真实 LLM */
    private void stubEvaluation() {
      when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(new TurnEvalDTO(80, "GOOD", List.of(), List.of(), "", null));
    }

    private String capturedUserPrompt() {
      ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
      verify(invoker).invoke(any(), any(), userPrompt.capture(), any(), any(), any(), any(), any(),
          any());
      return userPrompt.getValue();
    }
  }

  @Nested
  @DisplayName("覆盖摘要")
  class CoverageSummary {

    private final InterviewQuestionDTO jvm = InterviewQuestionDTO.createMain(0,
        "JVM 内存模型？", "JVM", "JVM", "运行时数据区", 3, List.of()).withQuestionId("q-jvm");
    private final InterviewQuestionDTO project = InterviewQuestionDTO.createMain(1,
        "介绍你的项目", "PROJECT", "项目经历", "项目深挖", 3, List.of()).withQuestionId("q-proj");
    private final InterviewQuestionDTO redis = InterviewQuestionDTO.createMain(2,
        "Redis 持久化？", "REDIS", "Redis", "RDB/AOF", 3, List.of()).withQuestionId("q-redis");
    private final List<InterviewQuestionDTO> pool = List.of(jvm, project, redis);

    @Test
    @DisplayName("必要覆盖按实际轨迹判定：问过才算已覆盖，当前题记正在考察")
    void requiredTopicsFromTrajectory() {
      String summary = TurnEvaluationService.coverageSummaryFor(pool,
          List.of(answered("q-jvm", "JVM")), project,
          new InterviewPlan(20, List.of("JVM", "项目经历", "Redis")));

      assertThat(summary)
          .contains("JVM=已覆盖")
          .contains("项目经历=本轮正在考察")
          .contains("Redis=未覆盖");
    }

    @Test
    @DisplayName("覆盖状态变化会改变摘要：同一候选池问过 JVM 后它从「尚未问」移到「已问」")
    void coverageChangesWithTrajectory() {
      String before = TurnEvaluationService.coverageSummaryFor(pool, List.of(), project, null);
      String after = TurnEvaluationService.coverageSummaryFor(pool,
          List.of(answered("q-jvm", "JVM")), redis, null);

      assertThat(before).contains("尚未问的话题：JVM");
      assertThat(after).contains("已问话题：JVM 1 轮");
      assertThat(after).doesNotContain("尚未问的话题：JVM");
    }

    @Test
    @DisplayName("跳过与未作答的轮次单独标注（问过与答了是两件事）")
    void unansweredTurnsAreMarked() {
      List<InterviewTurnDTO> turns = List.of(
          answered("q-jvm", "JVM"),
          skipped("q-proj", "项目经历"));

      String summary = TurnEvaluationService.coverageSummaryFor(pool, turns, redis, null);

      assertThat(summary)
          .contains("JVM 1 轮")
          .contains("项目经历 1 轮（1 轮未作答）")
          .doesNotContain("尚未问的话题：JVM")
          .doesNotContain("尚未问的话题：项目经历");
    }

    @Test
    @DisplayName("未声明必要覆盖或旧会话无计划时明确标注，不暗示会按覆盖收束")
    void noRequiredTopicsIsMarked() {
      assertThat(TurnEvaluationService.coverageSummaryFor(pool, List.of(), jvm,
          new InterviewPlan(20, List.of())))
          .contains("本场未声明必要覆盖");
      assertThat(TurnEvaluationService.coverageSummaryFor(pool, List.of(), jvm, null))
          .contains("旧会话未记录计划");
    }

    @Test
    @DisplayName("没有候选素材时返回 null（由提示词给可读占位，不编造覆盖状态）")
    void noCandidatesReturnsNull() {
      assertThat(TurnEvaluationService.coverageSummaryFor(List.of(), List.of(), jvm,
          new InterviewPlan(20, List.of()))).isNull();
    }
  }

  @Nested
  @DisplayName("时间与追问预算")
  class BudgetSummary {

    @Test
    @DisplayName("剩余时间与追问额度都进入摘要（只计答题时间）")
    void remainingTimeAndFollowUpBudget() {
      String summary = TurnEvaluationService.budgetSummaryFor(
          new InterviewPlan(20, List.of()), 660, 2);

      assertThat(summary)
          .contains("剩余约 11 分钟")
          .contains("预计 20 分钟")
          .contains("已用约 9 分钟")
          .contains("最多还可追问 2 条");
    }

    @Test
    @DisplayName("预算变化会改变摘要：时间紧、追问额度用尽时明确要求转向")
    void budgetChangeChangesSummary() {
      String rich = TurnEvaluationService.budgetSummaryFor(
          new InterviewPlan(20, List.of()), 900, 1);
      String tight = TurnEvaluationService.budgetSummaryFor(
          new InterviewPlan(20, List.of()), 90, 0);

      assertThat(rich).contains("剩余约 15 分钟").contains("最多还可追问 1 条");
      assertThat(tight).contains("剩余约 2 分钟").contains("没有剩余追问");
    }

    @Test
    @DisplayName("旧会话没有计划时明确标注不按时间收束，不编造默认预算")
    void legacySessionHasNoFakeBudget() {
      String summary = TurnEvaluationService.budgetSummaryFor(null, null, 1);

      assertThat(summary)
          .contains("旧会话未记录预计时长")
          .contains("不按时间收束")
          .contains("最多还可追问 1 条");
    }

    @Test
    @DisplayName("没有任何预算数据时返回 null，由提示词给占位")
    void noDataReturnsNull() {
      assertThat(TurnEvaluationService.budgetSummaryFor(null, null, null)).isNull();
    }
  }

  @Nested
  @DisplayName("本轮合法候选")
  class LegalCandidates {

    private final InterviewQuestionDTO jvmMain = InterviewQuestionDTO.createMain(0,
        "JVM 内存模型？", "JVM", "JVM", "运行时数据区", 3, List.of()).withQuestionId("q-jvm");
    private final InterviewQuestionDTO jvmFollowUp = InterviewQuestionDTO.createFollowUp(1,
        "堆为什么分代？", "JVM", "JVM", "q-jvm", 1,
        InterviewQuestionDTO.FOLLOW_UP_WHY, List.of()).withQuestionId("q-jvm-f1");
    private final InterviewQuestionDTO jvmFollowUp2 = InterviewQuestionDTO.createFollowUp(2,
        "TLAB 是什么？", "JVM", "JVM", "q-jvm", 2,
        InterviewQuestionDTO.FOLLOW_UP_DEPTH, List.of()).withQuestionId("q-jvm-f2");
    private final InterviewQuestionDTO otherFollowUp = InterviewQuestionDTO.createFollowUp(3,
        "项目里你负责哪块？", "PROJECT", "项目经历", "q-proj", 1,
        InterviewQuestionDTO.FOLLOW_UP_DEPTH, List.of()).withQuestionId("q-proj-f1");
    private final InterviewQuestionDTO redisMain = InterviewQuestionDTO.createMain(4,
        "Redis 持久化？", "REDIS", "Redis", "RDB/AOF", 3, List.of()).withQuestionId("q-redis");

    private final List<InterviewQuestionDTO> pool =
        List.of(jvmMain, jvmFollowUp, jvmFollowUp2, otherFollowUp, redisMain);

    @Test
    @DisplayName("排除已问与当前题，包含本组剩余追问与尚未问过的主问题")
    void excludesAskedAndCurrent() {
      List<String> candidates = TurnEvaluationService.legalCandidatesFor(
          pool, List.of(), jvmMain);

      assertThat(candidates)
          .anyMatch(line -> line.startsWith("[q-jvm-f1]") && line.contains("追问"))
          .anyMatch(line -> line.startsWith("[q-redis]"))
          .noneMatch(line -> line.contains("[q-jvm]"))
          .noneMatch(line -> line.contains("q-proj-f1"));
    }

    @Test
    @DisplayName("其他追问组的预置追问不在合法候选里（策略到不了）")
    void otherGroupsFollowUpsAreNotLegal() {
      List<String> candidates = TurnEvaluationService.legalCandidatesFor(
          pool, List.of(), jvmMain);

      assertThat(candidates).noneMatch(line -> line.contains("q-proj-f1"));
    }

    @Test
    @DisplayName("答完追问后仍以同一组计算剩余追问与主问题去处")
    void followUpAnsweredStillUsesItsGroup() {
      List<String> candidates = TurnEvaluationService.legalCandidatesFor(
          pool, List.of(answered("q-jvm", "JVM")), jvmFollowUp);

      assertThat(candidates)
          .noneMatch(line -> line.contains("q-jvm-f1"))
          .anyMatch(line -> line.contains("q-jvm-f2"))
          .anyMatch(line -> line.contains("q-redis"));
    }

    @Test
    @DisplayName("追问额度用尽后只剩主问题候选")
    void followUpBudgetExhaustedLeavesMains() {
      List<String> candidates = TurnEvaluationService.legalCandidatesFor(
          pool, List.of(), jvmFollowUp);

      assertThat(candidates).noneMatch(line -> line.contains("[q-jvm-f1]"));
      assertThat(candidates).anyMatch(line -> line.contains("[q-redis]"));
    }

    @Test
    @DisplayName("超出上限时截断为前若干条并说明剩余数量")
    void cappedWithExplicitRemainder() {
      List<InterviewQuestionDTO> many = new ArrayList<>();
      for (int index = 0; index < 9; index++) {
        many.add(InterviewQuestionDTO.createMain(index, "Q" + index, "JVM", "JVM",
            null, 3, List.of()).withQuestionId("q-" + index));
      }

      List<String> candidates = TurnEvaluationService.legalCandidatesFor(
          many, List.of(), many.get(0));

      assertThat(candidates).hasSize(TurnEvaluationService.MAX_LEGAL_CANDIDATES + 1);
      assertThat(candidates.get(TurnEvaluationService.MAX_LEGAL_CANDIDATES))
          .contains("还有 2 条候选未列出");
    }

    @Test
    @DisplayName("没有候选时为空（由提示词给可读占位）")
    void noCandidatesIsEmpty() {
      assertThat(TurnEvaluationService.legalCandidatesFor(List.of(), List.of(), jvmMain))
          .isEmpty();
    }
  }

  private static InterviewQuestionDTO question() {
    return InterviewQuestionDTO.createMain(0,
        "Minor GC 与 Full GC 有什么区别？", "JVM", "JVM", "GC 对比", 3,
        List.of("触发条件", "发生区域"));
  }

  /** 已作答的一轮（P4-1：历史参照来自实际轨迹，不再从候选素材里推断） */
  private static InterviewTurnDTO answeredTurn(
      int ordinal, String question, String category, String answer) {
    return new InterviewTurnDTO("q" + ordinal, ordinal, ordinal - 1, question,
        category, null, answer, InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
        InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null);
  }

  /** 已问过的真实轮次（按题目标识引用；category 同时充当话题兜底） */
  private static InterviewTurnDTO answered(String questionId, String category) {
    return new InterviewTurnDTO(questionId, 1, 0, "问题", category, null, "回答",
        InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
        InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null);
  }

  /** 问过但跳过的轮次：覆盖摘要要把它与「答了」区分开 */
  private static InterviewTurnDTO skipped(String questionId, String category) {
    return new InterviewTurnDTO(questionId, 2, 1, "问题", category, null, null,
        InterviewAnswerEntity.AnswerState.SKIPPED, null, null,
        InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null);
  }
}
