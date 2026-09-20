package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewProgressDTO;
import interview.guide.modules.interview.model.InterviewProgressDTO.ProgressQuestion;
import interview.guide.modules.interview.model.InterviewProgressDTO.ProgressTurn;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 面试进展读取（P4-10）：把会话读取 + 逐轮评估同一套推导收成一个「给 Agent 看的视图」。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>复用现有接口</b>：进展、覆盖、轨迹全部来自 {@code InterviewSessionService.getSession}
 *       与 {@code TurnEvaluationService} 的推导函数（与逐轮评估、收束硬边界同源），
 *       不新增第二份事实来源，也不落任何快照。</li>
 *   <li><b>只读</b>：不推进会话、不写缓存、不触发评估——Agent 问一句不该改变面试状态。</li>
 *   <li><b>按需拉取</b>：Agent 需要时才调，不让每一轮面试都接回主 Graph（P4-10 的边界）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class InterviewProgressService {

    /** 轮次列表里单条回答的裁剪长度：解释「他大概说了什么」够用，全文走单轮详情 */
    static final int ANSWER_SNIPPET_CHARS = 300;

    private final InterviewSessionService sessionService;
    private final InterviewQuestionService questionService;

    /**
     * 读取面试进展。
     *
     * @param sessionId  会话 ID
     * @param questionId 可选：指定题目标识，只回这一轮的完整详情
     * @return 进展视图；会话不存在时抛 {@link ErrorCode#INTERVIEW_SESSION_NOT_FOUND}
     */
    public InterviewProgressDTO progressOf(String sessionId, String questionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
        List<InterviewQuestionDTO> candidates = session.candidates() == null
            ? List.of() : session.candidates();
        List<InterviewTurnDTO> turns = session.turns() == null ? List.of() : session.turns();
        InterviewQuestionDTO current = session.currentQuestion();

        InterviewPlan plan = session.plannedDurationMinutes() != null
            ? new InterviewPlan(session.plannedDurationMinutes(), session.requiredTopics())
            : null;

        // 覆盖 / 预算 / 合法候选与逐轮评估同源：Agent 的解释与 Java 的收束判据不会分叉
        String coverageSummary = TurnEvaluationService.coverageSummaryFor(candidates, turns, current, plan);
        String budgetSummary = TurnEvaluationService.budgetSummaryFor(plan, session.remainingSeconds(),
            TurnEvaluationService.remainingFollowUpsFor(candidates, turns, current,
                questionService.getFollowUpBudget()));
        List<String> legalCandidates = TurnEvaluationService.legalCandidatesFor(candidates, turns, current);

        return new InterviewProgressDTO(
            session.sessionId(),
            session.status() != null ? session.status().name() : null,
            session.endReason(),
            session.plannedDurationMinutes(),
            session.consumedSeconds(),
            session.remainingSeconds(),
            session.requiredTopics(),
            coverageSummary,
            budgetSummary,
            legalCandidates,
            current == null ? null : toQuestion(current),
            turns.stream().map(turn -> toTurn(turn, ANSWER_SNIPPET_CHARS)).toList(),
            findTurn(turns, questionId),
            turns.size(),
            satisfiedRequiredTopicCount(candidates, turns, plan));
    }

    private ProgressTurn findTurn(List<InterviewTurnDTO> turns, String questionId) {
        if (questionId == null || questionId.isBlank()) {
            return null;
        }
        return turns.stream()
            .filter(turn -> questionId.equals(turn.questionId()))
            .findFirst()
            .map(turn -> toTurn(turn, null))
            .orElse(null);
    }

    private static ProgressQuestion toQuestion(InterviewQuestionDTO question) {
        return new ProgressQuestion(
            question.questionId(),
            question.question(),
            question.topic(),
            question.category(),
            question.isFollowUp(),
            question.followUpIndex(),
            question.difficulty(),
            question.expectedPoints());
    }

    /** @param answerLimit 回答裁剪长度；null = 不裁剪（单轮详情要全文） */
    private static ProgressTurn toTurn(InterviewTurnDTO turn, Integer answerLimit) {
        String answer = turn.userAnswer();
        if (answer != null && answerLimit != null && answer.length() > answerLimit) {
            answer = answer.substring(0, answerLimit) + "…（已截断，需要全文请按题目标识取单轮详情）";
        }
        return new ProgressTurn(
            turn.ordinal(),
            turn.questionId(),
            turn.question(),
            turn.topic(),
            turn.category(),
            turn.answerState() != null ? turn.answerState().name() : null,
            answer,
            turn.score(),
            turn.feedback(),
            turn.decidedAction(),
            turn.referenceAnswer(),
            turn.keyPoints(),
            turn.occurredAt());
    }

    /**
     * 已满足的必要覆盖话题数：判据与实际轨迹一致（问过即算有考察材料，不代表答对）。
     *
     * <p>口径与 {@code TurnEvaluationService.coverageSummaryFor} 的逐项状态保持一样——
     * 这里只是为了给 Agent 一个可引用的数字，不用另一套判断。
     */
    private static int satisfiedRequiredTopicCount(List<InterviewQuestionDTO> candidates,
                                                   List<InterviewTurnDTO> turns, InterviewPlan plan) {
        if (plan == null || plan.requiredTopics().isEmpty()) {
            return 0;
        }
        Set<String> askedIds = turns.stream()
            .map(InterviewTurnDTO::questionId)
            .filter(id -> id != null)
            .collect(java.util.stream.Collectors.toSet());
        int satisfied = 0;
        for (String topic : plan.requiredTopics()) {
            boolean covered = candidates.stream()
                .filter(InterviewQuestionDTO::isMain)
                .filter(question -> askedIds.contains(question.questionId()))
                .anyMatch(question -> question.matchesTopic(topic));
            if (covered) {
                satisfied++;
            }
        }
        return satisfied;
    }
}
