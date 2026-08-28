package interview.guide.modules.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewEvidenceExtractorTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @InjectMocks
  private InterviewEvidenceExtractor extractor;

  private InterviewSessionEntity sessionWithAnswers(List<InterviewAnswerEntity> answers,
                                                    LocalDateTime completedAt) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("abc123");
    session.setCompletedAt(completedAt);
    session.setAnswers(new java.util.ArrayList<>(answers));
    return session;
  }

  private InterviewAnswerEntity answered(int index, String category, String answer, Integer score) {
    InterviewAnswerEntity entity = new InterviewAnswerEntity();
    entity.setQuestionIndex(index);
    entity.setCategory(category);
    entity.setUserAnswer(answer);
    entity.setScore(score);
    return entity;
  }

  @Nested
  @DisplayName("证据提取规则")
  class Extraction {

    @Test
    @DisplayName("真实作答的题目提取为 INTERVIEW_TURN 证据，sourceId 为 sessionId:idx")
    void extractsAnsweredTurns() {
      LocalDateTime completedAt = LocalDateTime.of(2026, 8, 28, 12, 0);
      when(sessionRepository.findBySessionId("abc123")).thenReturn(Optional.of(
          sessionWithAnswers(List.of(
              answered(0, "MySQL", "InnoDB 使用 B+ 树", 88),
              answered(1, "JVM", "G1 分 Region", 55)), completedAt)));

      List<SkillEvidenceEntity> evidences = extractor.extract("abc123");

      assertThat(evidences).hasSize(2);
      SkillEvidenceEntity first = evidences.get(0);
      assertThat(first.getSkill()).isEqualTo("MySQL");
      assertThat(first.getSourceType()).isEqualTo(EvidenceSourceType.INTERVIEW_TURN);
      assertThat(first.getSourceId()).isEqualTo("abc123:0");
      assertThat(first.getScore()).isEqualTo(88);
      assertThat(first.getOccurredAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("未作答或无评分的题目不算证据（未考不等于不会）")
    void skipsUnansweredOrNullScore() {
      when(sessionRepository.findBySessionId("abc123")).thenReturn(Optional.of(
          sessionWithAnswers(List.of(
              answered(0, "MySQL", null, 0),
              answered(1, "MySQL", "", 0),
              answered(2, "JVM", "有回答", null)), null)));

      List<SkillEvidenceEntity> evidences = extractor.extract("abc123");

      assertThat(evidences).isEmpty();
    }

    @Test
    @DisplayName("category 为空的答案被丢弃")
    void skipsBlankCategory() {
      when(sessionRepository.findBySessionId("abc123")).thenReturn(Optional.of(
          sessionWithAnswers(List.of(answered(0, " ", "有回答", 80)), null)));

      assertThat(extractor.extract("abc123")).isEmpty();
    }

    @Test
    @DisplayName("会话不存在时返回空列表")
    void missingSessionReturnsEmpty() {
      when(sessionRepository.findBySessionId("missing")).thenReturn(Optional.empty());

      assertThat(extractor.extract("missing")).isEmpty();
    }
  }
}
