package interview.guide.modules.resume.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumeParseStructuredService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简历分析消费者：评分与结构化解析并行执行（P 阶段优化）。
 *
 * <p>验证：正常流程两者都会被调用；解析失败被吞掉不影响评分结果保存；
 * 评分保存不等待解析完成（并行语义）。
 */
@ExtendWith(MockitoExtension.class)
class AnalyzeStreamConsumerTest {

  @Mock
  private RedisService redisService;
  @Mock
  private ResumeGradingService gradingService;
  @Mock
  private ResumePersistenceService persistenceService;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private ResumeParseStructuredService structuredParseService;
  @Mock
  private ResumeVersionService versionService;

  @InjectMocks
  private AnalyzeStreamConsumer consumer;

  private static final Long RESUME_ID = 1L;
  private static final String CONTENT = "姓名：张三\n技能：Java、Spring";

  private AnalyzeStreamConsumer.AnalyzePayload payload() {
    return new AnalyzeStreamConsumer.AnalyzePayload(RESUME_ID, CONTENT);
  }

  private ResumeEntity resumeEntity() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(RESUME_ID);
    return resume;
  }

  private ResumeAnalysisResponse analysis() {
    return new ResumeAnalysisResponse(
        82,
        new ScoreDetail(20, 16, 21, 12, 13),
        "整体较好",
        List.of("项目描述清晰"),
        List.of(new Suggestion("内容", "高", "项目缺乏量化", "补充量化指标")),
        CONTENT
    );
  }

  private ResumeParseStructuredService.ResumeParseResult parseResult() {
    ResumeContentJson content = new ResumeContentJson(
        new ResumeContentJson.BasicInfo("张三", "13800000000", "a@b.com", "杭州", "Java 后端"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of()
    );
    return new ResumeParseStructuredService.ResumeParseResult(content, List.of());
  }

  @Test
  @DisplayName("正常流程：评分结果保存且结构化版本创建")
  void processBusiness_savesAnalysisAndCreatesVersion() {
    when(resumeRepository.existsById(RESUME_ID)).thenReturn(true);
    when(resumeRepository.findById(RESUME_ID)).thenReturn(Optional.of(resumeEntity()));
    when(gradingService.analyzeResume(CONTENT)).thenReturn(analysis());
    when(structuredParseService.parse(CONTENT)).thenReturn(parseResult());

    assertThatCode(() -> consumer.processBusiness(payload())).doesNotThrowAnyException();

    verify(persistenceService).saveAnalysis(any(ResumeEntity.class), eq(analysis()));
    verify(versionService).createImportVersion(eq(RESUME_ID), any());
  }

  @Test
  @DisplayName("结构化解析失败不影响评分结果保存")
  void processBusiness_swallowsParseFailure() {
    when(resumeRepository.existsById(RESUME_ID)).thenReturn(true);
    when(resumeRepository.findById(RESUME_ID)).thenReturn(Optional.of(resumeEntity()));
    when(gradingService.analyzeResume(CONTENT)).thenReturn(analysis());
    when(structuredParseService.parse(CONTENT))
        .thenThrow(new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "解析失败"));

    assertThatCode(() -> consumer.processBusiness(payload())).doesNotThrowAnyException();

    verify(persistenceService).saveAnalysis(any(ResumeEntity.class), eq(analysis()));
    verify(versionService, never()).createImportVersion(any(), any());
  }

  @Test
  @DisplayName("并行语义：评分保存不等待解析完成")
  void processBusiness_savesAnalysisBeforeParseFinishes() throws Exception {
    when(resumeRepository.existsById(RESUME_ID)).thenReturn(true);
    when(resumeRepository.findById(RESUME_ID)).thenReturn(Optional.of(resumeEntity()));
    when(gradingService.analyzeResume(CONTENT)).thenReturn(analysis());

    // 解析线程阻塞：验证评分保存先行，再放行解析完成
    CountDownLatch parseStarted = new CountDownLatch(1);
    CountDownLatch releaseParse = new CountDownLatch(1);
    when(structuredParseService.parse(CONTENT)).thenAnswer(invocation -> {
      parseStarted.countDown();
      releaseParse.await(5, TimeUnit.SECONDS);
      return parseResult();
    });

    AtomicReference<Throwable> workerError = new AtomicReference<>();
    Thread worker = new Thread(() -> {
      try {
        consumer.processBusiness(payload());
      } catch (Throwable e) {
        workerError.set(e);
      }
    });
    worker.start();

    // 解析尚未完成时，评分保存应已发生
    assertThat(parseStarted.await(5, TimeUnit.SECONDS)).isTrue();
    verify(persistenceService, timeout(2000)).saveAnalysis(any(ResumeEntity.class), eq(analysis()));
    verify(versionService, never()).createImportVersion(any(), any());

    releaseParse.countDown();
    worker.join(5000);
    assertThat(workerError.get()).isNull();
    verify(versionService).createImportVersion(eq(RESUME_ID), any());
  }
}
