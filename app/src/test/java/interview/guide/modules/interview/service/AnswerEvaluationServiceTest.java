package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerEvaluationServiceTest {

  @Mock
  private UnifiedEvaluationService unifiedEvaluationService;

  @Mock
  private InterviewPersistenceService persistenceService;

  @Mock
  private InterviewSkillService skillService;

  @Test
  @DisplayName("题目自带评分依据时不再拼接 Skill 参考基线")
  void shouldSkipSkillReferenceWhenQuestionsHaveReferences() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService,
        persistenceService,
        skillService
    );
    InterviewQuestionDTO question = InterviewQuestionDTO.fromQuestionBank(
        0,
        "什么是索引下推？",
        "MYSQL",
        "MySQL",
        "索引优化",
        "参考答案",
        List.of("评分要点"),
        "评分规则",
        "来源片段"
    );
    InterviewQuestionDTO unasked = InterviewQuestionDTO.fromQuestionBank(
        1, "未问题", "MYSQL", "MySQL", "无关候选", "不应进入上下文",
        List.of("无关要点"), "无关规则", "无关来源");
    when(unifiedEvaluationService.evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), any()
    )).thenReturn(report());

    // P4-1：报告吃「候选素材 + 实际轨迹」两份输入——素材供参考答案，轨迹是评分对象
    InterviewTurnDTO answered = new InterviewTurnDTO(question.questionId(), 1,
        question.questionIndex(), question.question(), question.category(), null, "回答",
        InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
        InterviewTurnDTO.ACTION_FINISH_EXHAUSTED, null, List.of(), null);
    InterviewReportDTO actual = service.evaluateInterview(
        null, "session1", "简历", List.of(question, unasked), List.of(answered));

    ArgumentCaptor<String> referenceCaptor = ArgumentCaptor.forClass(String.class);
    verify(unifiedEvaluationService).evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), referenceCaptor.capture()
    );
    verify(skillService, never()).buildEvaluationReferenceSectionSafe(any());
    verify(persistenceService, never()).findBySessionId(any());
    assertThat(referenceCaptor.getValue())
        .contains("参考答案")
        .contains("评分要点")
        .contains("评分规则")
        .doesNotContain("不应进入上下文")
        .doesNotContain("来源片段");
    assertThat(actual.referenceAnswers().getFirst().referenceAnswer()).isEqualTo("参考答案");
  }

  @Test
  @DisplayName("报告只评分真实作答，跳过轮次保留在轨迹但不进入总分")
  @SuppressWarnings("unchecked")
  void shouldExcludeSkippedTurnsFromEvaluation() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService, persistenceService, skillService);
    InterviewQuestionDTO skippedQuestion = InterviewQuestionDTO.create(
        0, "跳过题", "JAVA", "Java").withQuestionId("q-skip");
    InterviewQuestionDTO answeredQuestion = InterviewQuestionDTO.create(
        1, "作答题", "JAVA", "Java").withQuestionId("q-answer");
    List<InterviewTurnDTO> turns = List.of(
        new InterviewTurnDTO("q-skip", 1, 0, "跳过题", "Java", null, null,
            InterviewAnswerEntity.AnswerState.SKIPPED, null, null,
            InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null),
        new InterviewTurnDTO("q-answer", 2, 1, "作答题", "Java", null, "有效答案",
            InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
            InterviewTurnDTO.ACTION_FINISH_EXHAUSTED, null, List.of(), null));
    ArgumentCaptor<List<QaRecord>> recordsCaptor =
        ArgumentCaptor.forClass(List.class);
    when(unifiedEvaluationService.evaluate(
        nullable(ChatClient.class), eq("session2"), any(), eq(""), any()
    )).thenReturn(new EvaluationReport(
        "session2", 1, 80, List.of(), List.of(), "", List.of(), List.of(), List.of()));

    service.evaluateInterview(
        null, "session2", "", List.of(skippedQuestion, answeredQuestion), turns);

    verify(unifiedEvaluationService).evaluate(
        nullable(ChatClient.class), eq("session2"), recordsCaptor.capture(), eq(""), any());
    assertThat(recordsCaptor.getValue())
        .singleElement()
        .satisfies(record -> {
          assertThat(record.questionIndex()).isEqualTo(1);
          assertThat(record.userAnswer()).isEqualTo("有效答案");
        });
  }

  private EvaluationReport report() {
    return new EvaluationReport(
        "session1",
        1,
        80,
        List.of(new EvaluationReport.CategoryScore("MySQL", 80, 1)),
        List.of(new EvaluationReport.QuestionEvaluation(
            0,
            "什么是索引下推？",
            "MySQL",
            "回答",
            80,
            "不错"
        )),
        "整体不错",
        List.of("基础扎实"),
        List.of("继续深入"),
        List.of(new EvaluationReport.ReferenceAnswer(
            0,
            "什么是索引下推？",
            "模型参考",
            List.of("模型要点")
        ))
    );
  }
}
