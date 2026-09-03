package interview.guide.modules.interview.policy;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;

import java.util.List;

/**
 * 自适应面试选题策略（P4-3，Selection Before Generation 一期落地）。
 *
 * 数据结构：题单为「主问题 + 紧跟其预置追问池」的线性列表（创建时 LLM 一次生成），
 * 本策略在作答推进中按评估结果与池余量选下一题，不实时生成新题。
 *
 * 规则（LLM 判语义 answerState，代码控边界）：
 * - NO_ANSWER / WRONG / WEAK：中断追问组，切到下一主问题（不对答不上来的人继续施压）；
 * - PARTIAL / GOOD / EXCELLENT：进入该主问题的追问池消费下一追问；
 * - 追问预算 = 池内剩余追问数（每组天然 ≤ followUpCount 条，出题阶段已限）；
 * - 出题去重：同一题只问一次（线性推进 + 仅未问的下一条可被选中）；
 * - 所有主问题作答完毕（或最后一个主问题的追问组被中断且无后续主问题）→ 返回 null = 面试结束。
 */
public final class AdaptiveInterviewPolicy {

    private AdaptiveInterviewPolicy() {
    }

    /**
     * 选择下一题。
     *
     * @param questions      全部已生成题目（主问题与追问同列表，线性索引语义）
     * @param answeredIndex  刚答完的题索引；-1 表示尚未开始（取首题）
     * @param lastEvaluation 刚答完那题的评估；可能为 null（如恢复场景无评估可用，此时按"可深挖"推进）
     * @return 下一题；null 表示面试结束（主问题已全部作答）
     */
    public static InterviewQuestionDTO selectNext(
        List<InterviewQuestionDTO> questions, int answeredIndex, TurnEvaluation lastEvaluation) {

        if (answeredIndex < 0) {
            return questions.stream().findFirst().orElse(null);
        }
        InterviewQuestionDTO current = findByIdx(questions, answeredIndex);
        if (current == null) {
            return null;
        }

        boolean stopFollowUp = shouldStopFollowUp(lastEvaluation);

        if (current.isFollowUp()) {
            // 刚答完追问：答得好且组内还有追问 → 继续深挖；否则切下一主问题
            if (!stopFollowUp) {
                InterviewQuestionDTO next = findNextInGroup(questions, current.parentQuestionIndex(),
                    answeredIndex);
                if (next != null) {
                    return next;
                }
            }
            return nextMainAfter(questions, answeredIndex);
        }

        // 刚答完主问题：答得够好 → 进入其追问组首条；答不上 → 跳过深挖直接下一主问题
        if (!stopFollowUp) {
            InterviewQuestionDTO first = findNextInGroup(questions, current.questionIndex(), answeredIndex);
            if (first != null) {
                return first;
            }
        }
        return nextMainAfter(questions, answeredIndex);
    }

    /** 答不上/答错/偏弱：不再深挖当前主题（代码控边界，见设计文档 §18） */
    private static boolean shouldStopFollowUp(TurnEvaluation evaluation) {
        if (evaluation == null) {
            return false;
        }
        AnswerState state = evaluation.answerState();
        return state == AnswerState.NO_ANSWER || state == AnswerState.WRONG || state == AnswerState.WEAK;
    }

    /** 主问题 mainIdx 的追问池中，比 answeredIndex 更靠后的一条（组内顺序消费） */
    private static InterviewQuestionDTO findNextInGroup(List<InterviewQuestionDTO> questions, int mainIdx,
                                                        int answeredIndex) {
        for (InterviewQuestionDTO q : questions) {
            if (q.isFollowUp() && q.parentQuestionIndex() != null
                && q.parentQuestionIndex() == mainIdx && q.questionIndex() > answeredIndex) {
                return q;
            }
        }
        return null;
    }

    /** answeredIndex 之后的第一个主问题（跳过其间未消费的追问） */
    private static InterviewQuestionDTO nextMainAfter(List<InterviewQuestionDTO> questions, int answeredIndex) {
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp() && q.questionIndex() > answeredIndex) {
                return q;
            }
        }
        return null;
    }

    private static InterviewQuestionDTO findByIdx(List<InterviewQuestionDTO> questions, int idx) {
        for (InterviewQuestionDTO q : questions) {
            if (q.questionIndex() == idx) {
                return q;
            }
        }
        return null;
    }
}
