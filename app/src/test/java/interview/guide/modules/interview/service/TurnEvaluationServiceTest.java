package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import interview.guide.modules.interview.service.TurnEvaluationService.TurnEvalDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4-2 逐题轻量评估：NO_ANSWER 短路、模型输出归一（score/state/coverage）、失败回落。
 * 用 mock StructuredOutputInvoker 返回 DTO，不触发真实 LLM。
 */
@DisplayName("逐题轻量评估（P4-2）")
@ExtendWith(MockitoExtension.class)
class TurnEvaluationServiceTest {

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
        new TurnEvaluationProperties()
    );
  }

  private static InterviewQuestionDTO question() {
    return InterviewQuestionDTO.createMain(0,
        "Minor GC 与 Full GC 有什么区别？", "JVM", "JVM", "GC 对比", 3,
        List.of("触发条件", "发生区域", "STW"));
  }

  @Test
  @DisplayName("空回答与「不会」类短语直接短路 NO_ANSWER，不调用 LLM")
  void shortAnswersShortCircuitWithoutLlm() {
    for (String answer : List.of("", "   ", "不会", "不知道", "忘了", "跳过", "i don't know", "没复习")) {
      TurnEvaluation evaluation = service.evaluateTurn(chatClient, question(), answer);
      assertThat(evaluation.answerState()).isEqualTo(AnswerState.NO_ANSWER);
      assertThat(evaluation.score()).isZero();
      assertThat(evaluation.evaluatedByLlm()).isFalse();
    }
    verify(invoker, never()).invoke(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("模型输出归一：状态合法则按状态默认分补齐缺失分数，coverage 由要点列表计算")
  void normalizesModelOutputWhenScoreMissing() throws Exception {
    TurnEvalDTO dto = new TurnEvalDTO(null, "PARTIAL", List.of("触发条件"), List.of("发生区域", "STW"), "GC 触发细节");
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(dto);

    TurnEvaluation evaluation = service.evaluateTurn(chatClient, question(), "Young 代满会触发 Minor GC……");

    assertThat(evaluation.answerState()).isEqualTo(AnswerState.PARTIAL);
    assertThat(evaluation.score()).isEqualTo(55); // PARTIAL 默认分
    assertThat(evaluation.coverage()).isEqualTo(1.0 / 3.0);
    assertThat(evaluation.coveredPoints()).containsExactly("触发条件");
    assertThat(evaluation.missingPoints()).containsExactly("发生区域", "STW");
    assertThat(evaluation.recommendedFocus()).isEqualTo("GC 触发细节");
    assertThat(evaluation.evaluatedByLlm()).isTrue();
  }

  @Test
  @DisplayName("分数越界时夹取到 0-100，状态缺失时按分数推导")
  void clampsScoreAndDerivesStateWhenStateMissing() throws Exception {
    TurnEvalDTO high = new TurnEvalDTO(120, null, List.of(), List.of(), "");
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(high);
    TurnEvaluation evaluation = service.evaluateTurn(chatClient, question(), "非常完整的回答……");
    assertThat(evaluation.score()).isEqualTo(100);
    assertThat(evaluation.answerState()).isEqualTo(AnswerState.EXCELLENT);

    TurnEvalDTO negative = new TurnEvalDTO(-5, null, List.of(), List.of(), "");
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(negative);
    TurnEvaluation low = service.evaluateTurn(chatClient, question(), "回答错误……");
    assertThat(low.score()).isZero();
    assertThat(low.answerState()).isEqualTo(AnswerState.WRONG);
  }

  @Test
  @DisplayName("score 与 answerState 同时存在时以分数为准，状态自洽不需要强行改写")
  void keepsScoreAndStateWhenBothPresent() throws Exception {
    TurnEvalDTO dto = new TurnEvalDTO(88, "EXCELLENT", List.of("触发条件", "发生区域", "STW"), List.of(), "");
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(dto);
    TurnEvaluation evaluation = service.evaluateTurn(chatClient, question(), "回答很完整……");
    assertThat(evaluation.score()).isEqualTo(88);
    assertThat(evaluation.answerState()).isEqualTo(AnswerState.EXCELLENT);
    assertThat(evaluation.coverage()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("LLM 调用失败时不抛出，回落中性 PARTIAL（不阻塞答题）")
  void fallsBackWhenLlmFails() throws Exception {
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "模型不可用"));
    TurnEvaluation evaluation = service.evaluateTurn(chatClient, question(), "回答内容……");
    assertThat(evaluation.answerState()).isEqualTo(AnswerState.PARTIAL);
    assertThat(evaluation.score()).isEqualTo(50);
    assertThat(evaluation.evaluatedByLlm()).isFalse();
  }

  @Test
  @DisplayName("未提供期望要点时无要点判定，coverage 回落中性")
  void noExpectedPointsYieldsNeutralCoverage() throws Exception {
    InterviewQuestionDTO bare = InterviewQuestionDTO.create(0, "简单介绍下 JVM？", "JVM", "JVM");
    TurnEvalDTO dto = new TurnEvalDTO(80, "GOOD", List.of(), List.of(), "");
    when(invoker.invoke(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(dto);
    TurnEvaluation evaluation = service.evaluateTurn(chatClient, bare, "回答……");
    assertThat(evaluation.score()).isEqualTo(80);
    assertThat(evaluation.answerState()).isEqualTo(AnswerState.GOOD);
    assertThat(evaluation.coverage()).isEqualTo(0.5);
  }

  @Test
  @DisplayName("静态短路词表归一：大小写与前后空格")
  void shortCircuitNormalization() {
    assertThat(TurnEvaluationService.isNoAnswerPhrase("不会")).isTrue();
    assertThat(TurnEvaluationService.isNoAnswerPhrase(" I DON'T KNOW ")).isTrue();
    assertThat(TurnEvaluationService.isNoAnswerPhrase("Skip")).isTrue();
    assertThat(TurnEvaluationService.isNoAnswerPhrase("我不太确定，试着说说")).isFalse();
  }
}
