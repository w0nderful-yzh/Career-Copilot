package interview.guide.modules.interview.policy;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;

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
 *   <li>PARTIAL / GOOD / EXCELLENT：进入该主问题的追问组消费下一条；</li>
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
        List<InterviewQuestionDTO> pool = InterviewQuestionIdentity.withDerivedIds(candidates);
        Set<String> asked = askedIds == null ? Set.of() : new HashSet<>(askedIds);

        if (answered == null) {
            return firstUnasked(pool, asked).orElse(null);
        }

        boolean stopFollowUp = shouldStopFollowUp(evaluation);

        if (answered.isFollowUp()) {
            // 刚答完追问：答得好且组内还有未问的追问 → 继续深挖；否则切下一主问题
            if (!stopFollowUp) {
                Optional<InterviewQuestionDTO> next =
                    firstUnaskedInGroup(pool, asked, answered.parentQuestionId());
                if (next.isPresent()) {
                    return next.get();
                }
            }
            return nextMainAfter(pool, asked, answered).orElse(null);
        }

        // 刚答完主问题：答得够好 → 进入其追问组首条；答不上 → 跳过深挖直接下一主问题
        if (!stopFollowUp) {
            Optional<InterviewQuestionDTO> first =
                firstUnaskedInGroup(pool, asked, answered.questionId());
            if (first.isPresent()) {
                return first.get();
            }
        }
        return nextMainAfter(pool, asked, answered).orElse(null);
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
