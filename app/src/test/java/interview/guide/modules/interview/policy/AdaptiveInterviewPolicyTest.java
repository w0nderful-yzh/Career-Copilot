package interview.guide.modules.interview.policy;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-3 自适应选题策略：主问题 + 内嵌追问池的 Selection Before Generation。
 * 覆盖：答好进追问、答不上中断换主问题、追问池耗尽切主问题、末题结束。
 */
@DisplayName("自适应面试选题策略（P4-3）")
class AdaptiveInterviewPolicyTest {

  /** 构造 2 个主问题，各带追问的候选池（P4-1：父链用稳定标识） */
  private static List<InterviewQuestionDTO> twoTopicSession() {
    InterviewQuestionDTO first = InterviewQuestionDTO.createMain(
        0, "Q1: JVM 内存模型？", "JVM", "JVM", "内存", 3, List.of("堆", "栈"));
    InterviewQuestionDTO second = InterviewQuestionDTO.createMain(
        3, "Q2: Redis 持久化？", "REDIS", "Redis", "持久化", 3, List.of("RDB", "AOF"));
    List<InterviewQuestionDTO> list = new ArrayList<>();
    list.add(first);
    list.add(InterviewQuestionDTO.createFollowUp(1, "F1a: 堆区如何分代？", "JVM", "JVM", first.questionId(), 1, "DEPTH", List.of("young")));
    list.add(InterviewQuestionDTO.createFollowUp(2, "F1b: Full GC 触发条件？", "JVM", "JVM", first.questionId(), 2, "SCENARIO", List.of("old")));
    list.add(second);
    list.add(InterviewQuestionDTO.createFollowUp(4, "F2a: AOF 重写？", "REDIS", "Redis", second.questionId(), 1, "DEPTH", List.of("rewrite")));
    return list;
  }

  /**
   * 测试用便捷调用：把「池内第 answeredIndex 个候选」当作刚答完的题，
   * 已问集合由它之前（含它）的候选取代——生产代码只按**标识**判断，这里只是为了少写样板。
   */
  private static InterviewQuestionDTO selectNext(List<InterviewQuestionDTO> candidates,
                                                 int answeredIndex, TurnEvaluation evaluation) {
    if (answeredIndex < 0) {
      return AdaptiveInterviewPolicy.selectNext(candidates, Set.of(), null, evaluation);
    }
    InterviewQuestionDTO answered = candidates.get(answeredIndex);
    Set<String> asked = candidates.stream().limit(answeredIndex + 1L)
        .map(InterviewQuestionDTO::questionId).collect(Collectors.toSet());
    return AdaptiveInterviewPolicy.selectNext(candidates, asked, answered, evaluation);
  }

  private static TurnEvaluation eval(AnswerState state) {
    return new TurnEvaluation(TurnEvaluation.defaultScoreFor(state), 0.5,
        List.of(), List.of(), state, "", true);
  }

  @Test
  @DisplayName("尚未开始取首题；无题目返回 null")
  void startsAtFirstQuestion() {
    List<InterviewQuestionDTO> empty = List.of();
    assertThat(selectNext(empty, -1, null)).isNull();

    List<InterviewQuestionDTO> questions = twoTopicSession();
    assertThat(selectNext(questions, -1, null).question())
        .isEqualTo("Q1: JVM 内存模型？");
  }

  @Test
  @DisplayName("主问题答得好 → 进入其追问池第一条")
  void goodMainEntersFollowUpGroup() {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    InterviewQuestionDTO next = selectNext(questions, 0, eval(AnswerState.GOOD));
    assertThat(next.question()).isEqualTo("F1a: 堆区如何分代？");
    assertThat(next.isFollowUp()).isTrue();
  }

  @Test
  @DisplayName("主问题答不上/答错 → 中断追问组，切到下一主问题")
  void poorMainSkipsFollowUpsToNextMain() {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    for (AnswerState weak : List.of(AnswerState.NO_ANSWER, AnswerState.WRONG, AnswerState.WEAK)) {
      InterviewQuestionDTO next = selectNext(questions, 0, eval(weak));
      assertThat(next.isFollowUp()).as("state=%s 不应进入追问", weak).isFalse();
      assertThat(next.question()).isEqualTo("Q2: Redis 持久化？");
    }
  }

  @Test
  @DisplayName("追问答得好且组内有余量 → 继续追问；耗尽 → 切下一主问题")
  void followUpChainConsumesGroupThenMovesOn() {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    InterviewQuestionDTO second = selectNext(questions, 1, eval(AnswerState.GOOD));
    assertThat(second.question()).isEqualTo("F1b: Full GC 触发条件？");
    // 追问池耗尽（已是组内最后一条）
    InterviewQuestionDTO next = selectNext(questions, 2, eval(AnswerState.GOOD));
    assertThat(next.isFollowUp()).isFalse();
    assertThat(next.question()).isEqualTo("Q2: Redis 持久化？");
  }

  @Test
  @DisplayName("追问答不上 → 中断并切下一主问题（不再留在组内）")
  void poorFollowUpMovesToNextMain() {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    InterviewQuestionDTO next = selectNext(questions, 1, eval(AnswerState.NO_ANSWER));
    assertThat(next.isFollowUp()).isFalse();
    assertThat(next.question()).isEqualTo("Q2: Redis 持久化？");
  }

  @Test
  @DisplayName("最后一个主问题追问耗尽/中断 → 返回 null（面试结束）")
  void lastTopicExhaustionEndsInterview() {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    // 答完 Q2(index 3)，答得好 → F2a(index 4)
    InterviewQuestionDTO f2a = selectNext(questions, 3, eval(AnswerState.GOOD));
    assertThat(f2a.question()).isEqualTo("F2a: AOF 重写？");
    // F2a 后组内无剩余 → 结束
    assertThat(selectNext(questions, 4, eval(AnswerState.GOOD))).isNull();
    // Q2 答不上 → 无后续主问题 → 结束
    assertThat(selectNext(questions, 3, eval(AnswerState.WRONG))).isNull();
  }

  @ParameterizedTest
  @MethodSource("unavailableEvaluations")
  @DisplayName("评估缺失或失败时跳过整个追问组，末个主题直接结束")
  void unavailableEvaluationStopsFollowUps(TurnEvaluation evaluation) {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    for (int index : List.of(0, 1)) {
      InterviewQuestionDTO next = selectNext(questions, index, evaluation);
      assertThat(next.question()).isEqualTo("Q2: Redis 持久化？");
      assertThat(next.isFollowUp()).isFalse();
    }
    assertThat(selectNext(questions, 3, evaluation)).isNull();
  }

  private static Stream<TurnEvaluation> unavailableEvaluations() {
    return Stream.of(null, TurnEvaluation.unknownFallback());
  }

  @Test
  @DisplayName("正常的部分正确、良好与优秀回答仍可追问，跳过与明确不会均换主问题")
  void keepsEvaluatedAndShortCircuitSemantics() {
    List<InterviewQuestionDTO> questions = twoTopicSession();
    for (AnswerState state : List.of(AnswerState.PARTIAL, AnswerState.GOOD, AnswerState.EXCELLENT)) {
      assertThat(selectNext(questions, 0, eval(state)).isFollowUp()).isTrue();
    }
    for (TurnEvaluation evaluation : List.of(TurnEvaluation.skipped(), TurnEvaluation.noAnswer())) {
      assertThat(selectNext(questions, 0, evaluation).questionIndex())
          .isEqualTo(3);
    }
  }
}
