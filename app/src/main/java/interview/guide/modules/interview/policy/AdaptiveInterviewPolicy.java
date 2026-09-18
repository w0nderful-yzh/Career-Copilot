package interview.guide.modules.interview.policy;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import interview.guide.modules.interview.model.TurnEvaluation.RecommendedAction;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 自适应面试选题策略（P4-3，Selection Before Generation 一期落地；P4-1 改为按标识选）。
 *
 * <p>数据结构：候选池为「主问题 + 紧跟其预置追问」的列表（创建时一次生成），
 * 本策略在作答推进中按评估结果与池余量选下一题，不实时生成新题。
 *
 * <p><b>P4-1 起不再用数组下标决定路径</b>：路径由「题目标识 + 是否已经问过」决定，
 * 因此题单顺序调整、追加候选、旧会话回放都不会改变「下一题应该问谁」。
 *
 * 规则（LLM 判语义 answerState，代码控边界）：
 * <ul>
 *   <li>NO_ANSWER / WRONG / WEAK：中断追问组，切到下一主问题（不对答不上来的人继续施压）；</li>
 *   <li>评估缺失 / 失败：同样中断追问组，不把未知质量当成「部分正确」继续深挖；</li>
 *   <li>PARTIAL / GOOD / EXCELLENT：只有评估给出明确缺口与追问方向时才继续深挖；</li>
 *   <li>模型给出的动作、候选标识与承接语必须通过 Java 边界校验；非法建议退回确定性选择；</li>
 *   <li>追问预算 = 池内剩余追问数（每组天然 ≤ followUpCount 条，出题阶段已限）；</li>
 *   <li>同一题只问一次：已问过的标识不会再次被选中；</li>
 *   <li>没有可问的候选 → 返回 null。**这不等于「考察完成」**，调用方据此记录
 *       「候选耗尽」这一真实原因（覆盖是否充分是另一回事，见 P4Q-2）。</li>
 * </ul>
 */
public final class AdaptiveInterviewPolicy {

    private AdaptiveInterviewPolicy() {
    }

    /**
     * Java 校验后的逐轮决定。
     *
     * @param nextQuestion          实际要展示的下一题；null 表示收束
     * @param reason                实际决定依据（会持久化，不保存未接纳的模型说法）
     * @param transitionMessage     可展示承接语；只有模型建议被原样接纳时才保留
     * @param recommendationAccepted 模型建议的动作与题目标识是否都通过硬边界校验
     * @param finishRecommended     模型是否建议结束且 Java 已允许按覆盖收束
     */
    public record Decision(
        InterviewQuestionDTO nextQuestion,
        String reason,
        String transitionMessage,
        boolean recommendationAccepted,
        boolean finishRecommended
    ) {}

    /**
     * 选择下一题。
     *
     * @param candidates  候选素材（主问题与追问同池）
     * @param askedIds    已经问过的题目标识（实际轨迹）
     * @param answered    刚答完的那一题；null 表示尚未开始（取首个未问候选）
     * @param evaluation  刚答完那题的评估；可能为 null（无评估依据时跳过当前追问组）
     * @return 下一题；null 表示候选已问尽（调用方按「候选耗尽」收尾）
     */
    public static InterviewQuestionDTO selectNext(List<InterviewQuestionDTO> candidates,
                                                  Set<String> askedIds,
                                                  InterviewQuestionDTO answered,
                                                  TurnEvaluation evaluation) {
        return decideNext(candidates, askedIds, answered, evaluation, false).nextQuestion();
    }

    /**
     * 一次语义调用的建议进入 Java 决策边界（P4Q-3b）。
     *
     * <p>模型负责判断「还有没有关键缺口、该追问还是转场」并给出候选标识；代码负责确认：
     * 候选确实合法、追问属于当前问题组、题目没问过、弱回答不继续施压、覆盖未完成时不能结束。
     */
    public static Decision decideNext(List<InterviewQuestionDTO> candidates,
                                      Set<String> askedIds,
                                      InterviewQuestionDTO answered,
                                      TurnEvaluation evaluation,
                                      boolean mayFinishForCoverage) {
        List<InterviewQuestionDTO> pool = InterviewQuestionIdentity.withDerivedIds(candidates);
        Set<String> asked = askedIds == null ? Set.of() : new HashSet<>(askedIds);

        if (answered == null) {
            return new Decision(firstUnasked(pool, asked).orElse(null), "开始首个候选问题", "",
                false, false);
        }

        if (shouldStopFollowUp(evaluation)) {
            return moveToNextMain(pool, asked, answered, stopReason(evaluation));
        }

        RecommendedAction recommendation = evaluation.recommendedAction();
        if (recommendation == RecommendedAction.FINISH) {
            if (mayFinishForCoverage && evaluation.missingPoints().isEmpty()) {
                return new Decision(null, recommendationReason(evaluation, "必要覆盖已完成且没有关键缺口"),
                    evaluation.transitionMessage(), true, true);
            }
            return fallbackDecision(pool, asked, answered, evaluation,
                "结束建议未满足覆盖边界，继续选择合法候选");
        }

        if (recommendation == RecommendedAction.NEXT_MAIN) {
            InterviewQuestionDTO recommended = recommendedCandidate(pool, asked, evaluation);
            if (recommended != null && recommended.isMain()) {
                return accepted(recommended, evaluation);
            }
            return moveToNextMain(pool, asked, answered,
                "模型建议的主问题不可用，按未问主问题顺序转场");
        }

        if (recommendation == RecommendedAction.FOLLOW_UP) {
            InterviewQuestionDTO recommended = recommendedCandidate(pool, asked, evaluation);
            if (hasKeyGap(evaluation) && isFollowUpInAnsweredGroup(recommended, answered)) {
                return accepted(recommended, evaluation);
            }
            return fallbackDecision(pool, asked, answered, evaluation,
                "追问建议缺少关键缺口或候选不属于当前话题，按代码边界推进");
        }

        return fallbackDecision(pool, asked, answered, evaluation,
            "模型未给出可执行的动作与候选标识，按评估缺口保守推进");
    }

    private static Decision fallbackDecision(List<InterviewQuestionDTO> pool, Set<String> asked,
                                             InterviewQuestionDTO answered,
                                             TurnEvaluation evaluation, String fallbackReason) {
        if (hasKeyGap(evaluation)) {
            Optional<InterviewQuestionDTO> followUp = firstUnaskedInGroup(
                pool, asked, groupId(answered));
            if (followUp.isPresent()) {
                return new Decision(followUp.get(), fallbackReason, "", false, false);
            }
        }
        return moveToNextMain(pool, asked, answered, fallbackReason);
    }

    private static Decision moveToNextMain(List<InterviewQuestionDTO> pool, Set<String> asked,
                                           InterviewQuestionDTO answered, String reason) {
        return new Decision(nextMainAfter(pool, asked, answered).orElse(null), reason, "",
            false, false);
    }

    private static Decision accepted(InterviewQuestionDTO recommended, TurnEvaluation evaluation) {
        return new Decision(recommended, recommendationReason(evaluation, "模型建议已通过 Java 边界校验"),
            evaluation.transitionMessage(), true, false);
    }

    private static String recommendationReason(TurnEvaluation evaluation, String fallback) {
        if (evaluation.decisionReason() != null && !evaluation.decisionReason().isBlank()) {
            return evaluation.decisionReason();
        }
        String focus = evaluation.recommendedFocus();
        if (focus != null && !focus.isBlank()) {
            return fallback + "：" + focus;
        }
        return fallback;
    }

    private static InterviewQuestionDTO recommendedCandidate(List<InterviewQuestionDTO> pool,
                                                             Set<String> asked,
                                                             TurnEvaluation evaluation) {
        String id = evaluation.recommendedQuestionId();
        if (id == null || id.isBlank() || asked.contains(id)) {
            return null;
        }
        return InterviewQuestionIdentity.byId(pool, id).orElse(null);
    }

    /** 追问必须有明确缺口与对应方向；只凭分数中等不能机械消费追问池 */
    private static boolean hasKeyGap(TurnEvaluation evaluation) {
        return evaluation != null
            && evaluation.missingPoints() != null
            && !evaluation.missingPoints().isEmpty()
            && evaluation.recommendedFocus() != null
            && !evaluation.recommendedFocus().isBlank();
    }

    private static boolean isFollowUpInAnsweredGroup(InterviewQuestionDTO candidate,
                                                      InterviewQuestionDTO answered) {
        return candidate != null && candidate.isFollowUp()
            && groupId(answered) != null
            && groupId(answered).equals(candidate.parentQuestionId());
    }

    private static String groupId(InterviewQuestionDTO answered) {
        return answered.isFollowUp() ? answered.parentQuestionId() : answered.questionId();
    }

    private static String stopReason(TurnEvaluation evaluation) {
        if (evaluation == null || !evaluation.evaluatedByLlm()
            || evaluation.answerState() == AnswerState.UNKNOWN) {
            return "本轮评估不可用，保守转入下一主问题";
        }
        if (evaluation.skipRequested()) {
            return "用户跳过当前问题，转入下一主问题";
        }
        return switch (evaluation.answerState()) {
            case NO_ANSWER -> "用户明确不会，停止当前话题深挖";
            case WRONG, WEAK -> "当前回答基础不足，停止追问并转场";
            default -> "当前话题不适合继续追问";
        };
    }

    /** 只有有效评估明确支持深挖时才追问，缺失与失败均保守换主问题 */
    private static boolean shouldStopFollowUp(TurnEvaluation evaluation) {
        if (evaluation == null || !evaluation.evaluatedByLlm() || evaluation.skipRequested()) {
            return true;
        }
        AnswerState state = evaluation.answerState();
        return state != AnswerState.PARTIAL && state != AnswerState.GOOD && state != AnswerState.EXCELLENT;
    }

    /** 池内第一个未问过的候选（按池顺序） */
    private static Optional<InterviewQuestionDTO> firstUnasked(List<InterviewQuestionDTO> pool,
                                                               Set<String> asked) {
        return pool.stream().filter(question -> !asked.contains(question.questionId())).findFirst();
    }

    /** 主问题 mainId 的追问组里，第一个还没问过的追问（按池顺序） */
    private static Optional<InterviewQuestionDTO> firstUnaskedInGroup(List<InterviewQuestionDTO> pool,
                                                                      Set<String> asked,
                                                                      String mainId) {
        if (mainId == null) {
            return Optional.empty();
        }
        return pool.stream()
            .filter(InterviewQuestionDTO::isFollowUp)
            .filter(question -> mainId.equals(question.parentQuestionId()))
            .filter(question -> !asked.contains(question.questionId()))
            .findFirst();
    }

    /**
     * 已答完那一题之后的第一个未问主问题。
     *
     * <p>「之后」仍按池顺序（展示顺序），但判据是**标识未问过**而不是下标比大小：
     * 顺序调整只会改变提问先后，不会让某题被跳过或被问第二遍。
     */
    private static Optional<InterviewQuestionDTO> nextMainAfter(List<InterviewQuestionDTO> pool,
                                                                Set<String> asked,
                                                                InterviewQuestionDTO answered) {
        int answeredIndex = indexOf(pool, answered.questionId());
        return pool.stream()
            .filter(InterviewQuestionDTO::isMain)
            .filter(question -> !asked.contains(question.questionId()))
            .filter(question -> answeredIndex < 0 || question.questionIndex() > answeredIndex)
            .findFirst()
            .or(() -> pool.stream()
                .filter(InterviewQuestionDTO::isMain)
                .filter(question -> !asked.contains(question.questionId()))
                .findFirst());
    }

    private static int indexOf(List<InterviewQuestionDTO> pool, String questionId) {
        for (InterviewQuestionDTO question : pool) {
            if (question.questionId() != null && question.questionId().equals(questionId)) {
                return question.questionIndex();
            }
        }
        return -1;
    }
}
