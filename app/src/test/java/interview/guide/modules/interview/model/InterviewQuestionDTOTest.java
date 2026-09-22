package interview.guide.modules.interview.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("面试候选话题匹配")
class InterviewQuestionDTOTest {

  @Test
  @DisplayName("必要覆盖同时支持分类 key 与展示名")
  void matchesCategoryKeyAndDisplayName() {
    InterviewQuestionDTO question = InterviewQuestionDTO.createMain(
        0, "介绍一个你主导的项目", "PROJECT", "项目经历", null, 3, List.of());

    assertThat(question.matchesTopic("PROJECT")).isTrue();
    assertThat(question.matchesTopic("项目经历")).isTrue();
    assertThat(question.matchesTopic("INTERNSHIP")).isFalse();
  }
}
