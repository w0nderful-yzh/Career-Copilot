package interview.guide.modules.interview.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class InterviewQuestionIdentityTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("旧会话按原题号派生标识，并把追问父索引迁成父标识")
  void derivesLegacyIdentityAndParentLink() throws Exception {
    String legacyJson = """
        [
          {"questionIndex":4,"question":"主问题","type":"JAVA","category":"Java","isFollowUp":false},
          {"questionIndex":7,"question":"追问","type":"JAVA","category":"Java","isFollowUp":true,
           "parentQuestionIndex":4,"followUpIndex":1}
        ]
        """;

    List<InterviewQuestionDTO> parsed = objectMapper.readValue(legacyJson, new TypeReference<>() {});
    List<InterviewQuestionDTO> normalized = InterviewQuestionIdentity.withDerivedIds(parsed);

    assertThat(normalized).extracting(InterviewQuestionDTO::questionId)
        .containsExactly("legacy-4", "legacy-7");
    assertThat(normalized.get(1).parentQuestionId()).isEqualTo("legacy-4");
    assertThat(objectMapper.writeValueAsString(normalized))
        .as("新缓存与 API 不再写出旧父索引")
        .doesNotContain("parentQuestionIndex");
  }
}
