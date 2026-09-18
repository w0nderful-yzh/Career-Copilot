package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
public class AnswerEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;

    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService,
                                   InterviewPersistenceService persistenceService,
                                   InterviewSkillService skillService) {
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.persistenceService = persistenceService;
        this.skillService = skillService;
    }

    /**
     * 评估完整面试并生成报告
     */
    /**
     * 评估一场面试并生成报告（P4-1 起素材与轨迹分开传入）。
     *
     * <p>**评估对象只有实际轨迹**：没问过的候选素材不参与评分，也不该在报告里被当成「答得差」。
     * 候选素材只用于取参考答案与要点（出题时写好的素材信息）。
     *
     * @param candidates 候选素材（取参考答案 / 要点）
     * @param turns      实际轨迹（报告逐题条目的来源；序号用真实发生顺序）
     */
    public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> candidates,
                                                 List<InterviewTurnDTO> turns) {
        log.info("开始评估面试: {}, 实际轮次={}, 候选素材={}", sessionId, turns.size(), candidates.size());

        try {
            // 转为通用问答记录：序号用**真实发生顺序**，与前端「第 N 题」一致
            List<InterviewTurnDTO> scoreableTurns = turns.stream()
                // 跳过 / 明确不会 / 空提交属于真实轨迹，但不是技术答案，不能以 0 分拉低总分。
                .filter(InterviewTurnDTO::countsAsAnswer)
                .toList();
            List<QaRecord> qaRecords = scoreableTurns.stream()
                // QaRecord / InterviewReportDTO 沿用 0 起 questionIndex；展示层再 +1。
                // turn.ordinal 是 1 起真实顺序，不能直接传入，否则首轮会被显示成「问题2」。
                .map(turn -> new QaRecord(turn.displayOrdinal() - 1, turn.question(), turn.category(),
                    turn.userAnswer()))
                .toList();
            Set<String> scoreableIds = scoreableTurns.stream()
                .map(InterviewTurnDTO::questionId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            // 参考基线也只取被评分的实际轮次；未问候选不能进入评估上下文。
            List<InterviewQuestionDTO> questions = candidates.stream()
                .filter(question -> scoreableIds.contains(question.questionId()))
                .toList();

            String referenceContext = buildQuestionReferenceContext(questions);
            if (referenceContext.isBlank()) {
                referenceContext = skillService.buildEvaluationReferenceSectionSafe(
                    persistenceService.findBySessionId(sessionId)
                        .map(s -> s.getSkillId())
                        .orElse(null)
                );
            }

            // 调用通用评估服务
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatClient, sessionId, qaRecords, resumeText, referenceContext
            );

            // 转为文字面试专用 DTO
            return withQuestionReferences(toInterviewReportDTO(report), scoreableTurns, questions);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：" + e.getMessage());
        }
    }

    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            report.categoryScores().stream()
                .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                .toList(),
            report.questionDetails().stream()
                .map(qe -> new QuestionEvaluation(qe.questionIndex(), qe.question(), qe.category(),
                    qe.userAnswer(), qe.score(), qe.feedback()))
                .toList(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            report.referenceAnswers().stream()
                .map(ra -> new ReferenceAnswer(ra.questionIndex(), ra.question(),
                    ra.referenceAnswer(), ra.keyPoints()))
                .toList()
        );
    }

    /**
     * 把候选素材里的参考答案 / 要点补进报告条目。
     *
     * <p>匹配走「报告序号（真实发生顺序）→ 轨迹 → 题目标识 → 候选素材」这条链：
     * 报告序号与候选池顺序不再有任何隐含对应（P4-1）。
     */
    private InterviewReportDTO withQuestionReferences(InterviewReportDTO report,
                                                      List<InterviewTurnDTO> turns,
                                                      List<InterviewQuestionDTO> candidates) {
        Map<Integer, String> questionIdByOrdinal = new HashMap<>();
        for (InterviewTurnDTO turn : turns) {
            questionIdByOrdinal.put(turn.displayOrdinal() - 1, turn.questionId());
        }
        List<InterviewReportDTO.ReferenceAnswer> references = report.referenceAnswers().stream()
            .map(reference -> {
                InterviewQuestionDTO question = InterviewQuestionIdentity
                    .byId(candidates, questionIdByOrdinal.get(reference.questionIndex()))
                    .orElse(null);
                if (question == null || question.referenceAnswer() == null
                    || question.referenceAnswer().isBlank()) {
                    return reference;
                }
                return new InterviewReportDTO.ReferenceAnswer(
                    reference.questionIndex(),
                    reference.question(),
                    question.referenceAnswer(),
                    question.keyPoints() != null ? question.keyPoints() : List.of()
                );
            })
            .toList();
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            report.categoryScores(),
            report.questionDetails(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            references
        );
    }

    private String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions) {
        StringBuilder sb = new StringBuilder();
        for (InterviewQuestionDTO question : questions) {
            if (!hasQuestionReference(question)) {
                continue;
            }
            sb.append("问题").append(question.questionIndex() + 1).append(": ")
                .append(question.question()).append('\n');
            appendIfPresent(sb, "参考答案", question.referenceAnswer());
            if (question.keyPoints() != null && !question.keyPoints().isEmpty()) {
                sb.append("评分要点: ").append(String.join("；", question.keyPoints())).append('\n');
            }
            appendIfPresent(sb, "评分规则", question.scoringRubric());
            sb.append('\n');
        }
        return sb.toString();
    }

    private boolean hasQuestionReference(InterviewQuestionDTO question) {
        return hasText(question.referenceAnswer())
            || hasText(question.scoringRubric())
            || (question.keyPoints() != null && !question.keyPoints().isEmpty());
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (hasText(value)) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
