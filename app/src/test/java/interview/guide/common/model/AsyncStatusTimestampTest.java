package interview.guide.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("异步状态更新时间")
class AsyncStatusTimestampTest {

  private static final LocalDateTime OLD_TIME = LocalDateTime.of(2020, 1, 1, 0, 0);

  @Test
  @DisplayName("简历分析状态变化时刷新服务端时间")
  void resumeStatusRefreshesTimestamp() {
    ResumeEntity resume = new ResumeEntity();
    resume.setAnalyzeStatusUpdatedAt(OLD_TIME);
    resume.setAnalyzeStatus(AsyncTaskStatus.PROCESSING);

    assertThat(resume.getAnalyzeStatusUpdatedAt()).isAfter(OLD_TIME);
  }

  @Test
  @DisplayName("知识库向量化状态变化时刷新服务端时间")
  void vectorStatusRefreshesTimestamp() {
    KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setVectorStatusUpdatedAt(OLD_TIME);
    knowledgeBase.setVectorStatus(VectorStatus.PROCESSING);

    assertThat(knowledgeBase.getVectorStatusUpdatedAt()).isAfter(OLD_TIME);
  }

  @Test
  @DisplayName("面试评估状态变化时刷新服务端时间")
  void interviewEvaluationStatusRefreshesTimestamp() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setEvaluateStatusUpdatedAt(OLD_TIME);
    session.setEvaluateStatus(AsyncTaskStatus.PROCESSING);

    assertThat(session.getEvaluateStatusUpdatedAt()).isAfter(OLD_TIME);
  }
}
