package interview.guide.modules.interview.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.profile.service.SkillProfileAggregator;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPersistenceServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private SkillProfileAggregator profileAggregator;

  @Mock
  private InterviewSessionCache sessionCache;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("知识库面试保存时写入 interviewCategory")
  void shouldSaveInterviewCategoryForKnowledgeBaseSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid1", null, 1, List.of(), "dashscope",
        "knowledge-base", "mid", "KNOWLEDGE_BASE", 9L, "MySQL");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isEqualTo("MySQL");
    assertThat(saved.getKnowledgeBaseId()).isEqualTo(9L);
    assertThat(saved.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
  }

  @Test
  @DisplayName("普通面试保存时 interviewCategory 保持 null")
  void shouldKeepInterviewCategoryNullForNormalSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid2", null, 1, List.of(), "dashscope", "java-backend", "mid");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isNull();
    assertThat(saved.getSourceType()).isEqualTo("NORMAL");
    assertThat(saved.getKnowledgeBaseId()).isNull();
  }

  @Test
  @DisplayName("删除会话时同步失效 Redis 缓存（防幽灵会话残留）")
  void deleteSessionEvictsCache() {
    InterviewPersistenceService service = newService();
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId("ghost-session");
    when(sessionRepository.findBySessionId("ghost-session")).thenReturn(Optional.of(entity));

    service.deleteSessionBySessionId("ghost-session");

    verify(sessionRepository).delete(entity);
    verify(sessionCache).deleteSession("ghost-session");
  }

  @Test
  @DisplayName("评估完成时同步会话缓存状态，避免前端一直显示「评估中」（P4Q-4）")
  void evaluationCompletedSyncsCachedSessionStatus() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("sid2");
    when(sessionRepository.findBySessionId("sid2")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    newService().updateEvaluateStatus("sid2", AsyncTaskStatus.COMPLETED, null);

    verify(sessionCache).updateSessionStatus(
        "sid2", InterviewSessionDTO.SessionStatus.EVALUATED);
  }

  @Test
  @DisplayName("评估失败不顺带改会话状态：报告缺失由前端重试，不伪装成已评估")
  void evaluationFailedDoesNotTouchSessionStatus() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("sid3");
    when(sessionRepository.findBySessionId("sid3")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    newService().updateEvaluateStatus("sid3", AsyncTaskStatus.FAILED, "模型超时");

    verify(sessionCache, never()).updateSessionStatus(any(), any());
    assertThat(session.getEvaluateError()).isEqualTo("模型超时");
  }

  private InterviewPersistenceService newService() {
    return new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        resumeRepository,
        objectMapper,
        profileAggregator,
        sessionCache
    );
  }
}
