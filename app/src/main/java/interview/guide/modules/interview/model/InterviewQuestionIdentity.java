package interview.guide.modules.interview.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试候选素材的身份规则（P4-1）。
 *
 * <p>把这些规则集中在一处，是为了让「身份」在四个地方口径一致：
 * 候选池读取、实际轮次回填（迁移里的 {@code legacy-<questionIndex>}）、报告回填、当前题定位。
 * 分散实现最容易出现的结果是「一侧用下标、一侧用标识」，于是旧会话忽然读不出来。
 */
public final class InterviewQuestionIdentity {

    private InterviewQuestionIdentity() {
    }

    /**
     * 补齐缺失的题目标识：旧数据（P4-1 之前的 questions_json）按下标派生。
     *
     * <p>派生规则必须与迁移里回填 {@code interview_answers.question_id} 的规则完全一致，
     * 否则旧会话的答案行与题目对不上。
     */
    public static List<InterviewQuestionDTO> withDerivedIds(List<InterviewQuestionDTO> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> idByLegacyIndex = new LinkedHashMap<>();
        for (InterviewQuestionDTO question : questions) {
            if (question == null) {
                continue;
            }
            String id = question.questionId() != null && !question.questionId().isBlank()
                ? question.questionId()
                : InterviewQuestionDTO.legacyIdFor(question.questionIndex());
            idByLegacyIndex.put(question.questionIndex(), id);
        }

        List<InterviewQuestionDTO> normalized = new ArrayList<>(questions.size());
        for (InterviewQuestionDTO question : questions) {
            if (question == null) {
                continue;
            }
            String id = question.questionId() != null && !question.questionId().isBlank()
                ? question.questionId()
                : InterviewQuestionDTO.legacyIdFor(question.questionIndex());
            InterviewQuestionDTO result = id.equals(question.questionId())
                ? question
                : question.withQuestionId(id);
            if (result.isFollowUp() && result.parentQuestionId() == null
                && result.parentQuestionIndex() != null) {
                result = result.withParentQuestionId(
                    idByLegacyIndex.get(result.parentQuestionIndex()));
            }
            normalized.add(result);
        }
        return normalized;
    }

    /** 下标 → 题目标识（报告回填、旧路径兼容用） */
    public static Map<Integer, String> idByIndex(List<InterviewQuestionDTO> questions) {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        for (InterviewQuestionDTO question : withDerivedIds(questions)) {
            mapping.put(question.questionIndex(), question.questionId());
        }
        return mapping;
    }

    /** 按标识取题（「当前题」定位与推进校验都走它，不再用下标） */
    public static Optional<InterviewQuestionDTO> byId(List<InterviewQuestionDTO> questions, String questionId) {
        if (questionId == null) {
            return Optional.empty();
        }
        return withDerivedIds(questions).stream()
            .filter(question -> questionId.equals(question.questionId()))
            .findFirst();
    }

    /**
     * 重排候选池的展示顺序（合并两批题单时用）。
     *
     * <p>标识与父链不参与重排：它们本来就是稳定身份，顺序变了也不该跟着变
     * （这正是 P4-1 要的结果——旧实现必须同步偏移 parentQuestionIndex，漏一处就串题）。
     */
    public static List<InterviewQuestionDTO> renumber(List<InterviewQuestionDTO> questions) {
        List<InterviewQuestionDTO> normalized = withDerivedIds(questions);
        List<InterviewQuestionDTO> renumbered = new ArrayList<>(normalized.size());
        for (int index = 0; index < normalized.size(); index++) {
            renumbered.add(normalized.get(index).withIndex(index));
        }
        return renumbered;
    }
}
