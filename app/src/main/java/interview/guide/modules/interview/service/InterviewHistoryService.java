package interview.guide.modules.interview.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {

    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情
     */
    @Transactional(readOnly = true)
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();

        // 解析JSON字段
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {});
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});

        // P4-1：答案列表 = **实际轨迹**（未问过的候选不在这里；未考察由报告覆盖范围表达）
        List<InterviewAnswerEntity> turns = interviewPersistenceService.findTurnEntitiesBySessionId(sessionId);
        List<InterviewDetailDTO.AnswerDetailDTO> answerList = buildAnswerDetailList(turns);

        // 使用 MapStruct 组装最终 DTO
        return interviewMapper.toDetailDTO(
            session,
            questions,
            strengths,
            improvements,
            referenceAnswers,
            answerList
        );
    }

    /**
     * 构建答案详情列表（P4-1：只列**真实发生过**的轮次，按发生顺序）。
     *
     * <p>此前按候选池逐题遍历、未回答的补空行，于是「候选择问」也会出现在轨迹里；
     * 现在轨迹就是轨迹——报告负责说明覆盖与未考察。
     */
    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
        List<InterviewAnswerEntity> turns
    ) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return interviewMapper.toAnswerDetailDTOList(turns, this::extractKeyPoints);
    }

    /**
     * 从 JSON 提取 keyPoints
     */
    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    /**
     * 通用 JSON 解析方法
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.error("解析 JSON 失败", e);
            return null;
        }
    }

    /**
     * 导出面试报告为PDF
     */
    public byte[] exportInterviewPdf(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}
