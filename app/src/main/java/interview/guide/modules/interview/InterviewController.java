package interview.guide.modules.interview;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewTurnRequests;
import interview.guide.modules.interview.model.SessionListItemDTO;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 面试控制器
 * 提供模拟面试相关的API接口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
public class InterviewController {
    
    private final InterviewSessionService sessionService;
    private final InterviewHistoryService historyService;
    private final InterviewPersistenceService persistenceService;
    private final interview.guide.modules.profile.service.SkillProfileImpactService profileImpactService;
    
    /**
     * 列出所有面试会话（用于面试记录页）
     */
    @GetMapping("/api/interview/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        List<SessionListItemDTO> items = persistenceService.findAll().stream()
            .map(SessionListItemDTO::from)
            .toList();
        return Result.success(items);
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话，题目数量: {}", request.questionCount());
        InterviewSessionDTO session = sessionService.createSession(request);
        return Result.success(session);
    }
    
    /**
     * 获取会话信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        return Result.success(session);
    }
    
    /**
     * 获取当前问题
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
    }
    
    /**
     * 提交答案（P4-9a：带请求标识与预期会话版本）。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewTurnRequests.SubmitAnswerBody body) {
        log.info("提交答案: 会话{}, 题目{}, 版本={}, requestId={}",
            sessionId, body.questionId(), body.expectedVersion(), body.requestId());
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, body.questionId(),
            body.questionIndex(), body.answer(), body.requestId(), body.expectedVersion());
        return Result.success(sessionService.submitAnswer(request));
    }
    
    /**
     * 生成面试报告
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        log.info("生成面试报告: {}", sessionId);
        InterviewReportDTO report = sessionService.generateReport(sessionId);
        return Result.success(report);
    }
    
    /**
     * 查找未完成的面试会话
     * GET /api/interview/sessions/unfinished/{resumeId}
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        return Result.success(sessionService.findUnfinishedSessionOrThrow(resumeId));
    }
    
    /**
     * 跳过当前题（P4Q-5 一等动作，P4-9a 与提交共用同一条推进链路）。
     *
     * <p>不调模型、不追问、不计分、不产生画像证据——与「答错」严格区分。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/skip")
    public Result<SubmitAnswerResponse> skipQuestion(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewTurnRequests.SkipBody body) {
        log.info("跳过当前题: 会话{}, 题目{}, 版本={}, requestId={}",
            sessionId, body.questionId(), body.expectedVersion(), body.requestId());
        return Result.success(sessionService.skipQuestion(sessionId, body.questionId(),
            body.questionIndex(), body.requestId(), body.expectedVersion()));
    }

    /**
     * 调整剩余时间预算（P4Q-2）：当轮生效。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/budget")
    public Result<Void> updateBudget(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewTurnRequests.BudgetBody body) {
        log.info("调整面试预算: 会话{}, 剩余 {} 分钟", sessionId, body.remainingMinutes());
        sessionService.updateBudget(sessionId, body.remainingMinutes());
        return Result.success(null);
    }

    /**
     * 显式难度调整（P4Q-3c）：只改本场难度偏好，与跳过/预算同级的确定性节奏动作（不等模型）。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/pace")
    public Result<Void> updatePace(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewTurnRequests.PaceBody body) {
        log.info("调整面试难度偏好: 会话{}, difficulty={}", sessionId, body.difficulty());
        sessionService.updatePace(sessionId, body.difficulty());
        return Result.success(null);
    }

    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("暂存答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        sessionService.saveAnswer(request);
        return Result.success(null);
    }
    
    /**
     * 提前交卷（P4-9a：与逐轮推进同一并发边界，可通过请求标识安全重试）
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(
            @PathVariable String sessionId,
            @RequestBody(required = false) InterviewTurnRequests.CompleteBody body) {
        log.info("提前交卷: {}, requestId={}", sessionId, body != null ? body.requestId() : null);
        sessionService.completeInterview(sessionId,
            body != null ? body.requestId() : null,
            body != null ? body.expectedVersion() : null);
        return Result.success(null);
    }
    
    /**
     * 重试生成面试报告（P4Q-4）。
     *
     * <p>评估失败或长时间未完成时的用户重试入口；已有报告时幂等返回当前会话。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/evaluate/retry")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> retryEvaluation(@PathVariable String sessionId) {
        log.info("重试生成面试报告: {}", sessionId);
        return Result.success(sessionService.retryEvaluation(sessionId));
    }

    /**
     * 获取面试会话详情
     * GET /api/interview/sessions/{sessionId}/details
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
        return Result.success(detail);
    }

    /**
     * 本场面试带来的画像变化（P3 待收口）。
     *
     * <p>结果卡展示「这场让我哪项变了」，每条变化都带可追溯的逐题证据与时间；
     * 差分由证据重算，无额外存储。P4-6b 的 Copilot 建议话术将来也消费同一端点。
     */
    @GetMapping("/api/interview/sessions/{sessionId}/profile-impact")
    public Result<interview.guide.modules.profile.dto.SkillProfileImpactResponse> getProfileImpact(
            @PathVariable String sessionId) {
        return Result.success(profileImpactService.impactOf(sessionId));
    }
    
    /**
     * 导出面试报告为PDF
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
            String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf", 
                StandardCharsets.UTF_8);
            
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
        } catch (Exception e) {
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 删除面试会话
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("删除面试会话: {}", sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        return Result.success(null);
    }
}
