package interview.guide.modules.interview.policy;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionIdentity;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import interview.guide.modules.interview.model.TurnEvaluation.RecommendedAction;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 自适应面试选题策略（P4-3 一期；P4-1 改为按标识选；P4-4b 收口 Selection-before-Generation）。
 *
 * <p>数据结构：候选池为「主问题 + 紧跟其预置追问」的素材池。本策略在作答推进中按评估结果、
 * 追问预算与池余量选下一题；<b>有合适候选时选择，没有合适候选时接纳一条受限生成的短追问</b>
 * （生成题只有被 Java 校验接纳、由调用方持久化后才成为正式下一题）。
 *
 * <p>P4-4b：候选容量不等于追问预算——池里未问追问可能多于还能追问的条数。运行期追问预算由
 * {@code maxFollowUpsPerGroup} 与「本组已问追问数」共同决定。
 *
 * 规则（LLM 判语义，代码控边界）：
 * <ul>
 *   <li>NO_ANSWER / WRONG / WEAK / 评估缺失 / 跳过：中断追问组，切下一主问题；</li>
 *   <li>stopDeepDive（P4Q-3c）：用户要求停止深挖当前话题，软转场但保留本轮已给技术证据；</li>
 *   <li>追问预算用尽：即便模型建议追问也不再消费本组候选；</li>
 *   <li>模型建议（动作 / 候选标识 / 生成题 / 承接语）必须通过 Java 边界校验；非法退回确定性选择；</li>
 *   <li>难度偏好（P4Q-3c）：转下一主问题时在候选池内偏好接近该难度的主问题（软偏好）；</li>
 *   <li>没有可问的候选 → 返回 null，调用方据「候选耗尽」收尾。</li>
 * </ul>
 */
public final class AdaptiveInterviewPolicy {

    private AdaptiveInterviewPolicy() {
    }

    /** 难度枚举 → 数值基线（1-5），与出题侧一致，供软偏好比较 */
    private static int difficultyBase(String preference) {
        if (preference == null) {
            return 0;
        }
        return switch (preference.trim().toLowerCase(Locale.ROOT)) {
            case "junior" -> 2;
            case "mid" -> 3;
            case "senior" -> 4;
            default -> 0;
        };
    }

    /**
     * 一条被接纳的受限生成追问（P4-4b）。策略只负责「能不能生成」的边界判定与内容校验，
     * 具体题目标识 / 序号 / 类型 / 代次由调用方（会话服务）补齐后持久化。
     *
     * @param question      生成题干（已由评估侧裁剪）
     * @param expectedPoint 考察点
     * @param answerBasis   引用的候选人原话 / 待验证点
     */
    public record GeneratedFollowUp(
        String question,
        String expectedPoint,
        String answerBasis
    ) {}

    /**
     * Java 校验后的逐轮决定。
     *
     * @param nextQuestion          实际要展示的下一题（既有候选）；null 表示收束或走生成
     * @param reason                实际决定依据（会持久化，不保存未接纳的模型说法）
     * @param transitionMessage     可展示承接语；只有模型建议被原样接纳时才保留
     * @param recommendationAccepted 模型建议的动作与题目标识是否都通过硬边界校验
     * @param finishRecommended     模型是否建议结束且 Java 已允许按覆盖收束
     * @param generated             被接纳的受限生成追问（P4-4b）；非生成时 null
     */
    public record Decision(
        InterviewQuestionDTO nextQuestion,
        String reason,
        String transitionMessage,
        boolean recommendationAccepted,
        boolean finishRecommended,
        GeneratedFollowUp generated
    ) {
        /** 兼容 P4-4b 之前的 5 参构造：无受限生成 */
        public Decision(InterviewQuestionDTO nextQuestion, String reason, String transitionMessage,
                        boolean recommendationAccepted, boolean finishRecommended) {
            this(nextQuestion, reason, transitionMessage, recommendationAccepted, finishRecommended, null);
        }
    }

    /**
     * 选择下一题（兼容旧签名：不额外限制每组追问条数、无难度偏好）。
     */
    public static InterviewQuestionDTO selectNext(List<InterviewQuestionDTO> candidates,
                                                  Set<String> askedIds,
                                                  InterviewQuestionDTO answered,
                                                  TurnEvaluation evaluation) {
        return decideNext(candidates, askedIds, answered, evaluation, false).nextQuestion();
    }

    /** 兼容 P4-4b 之前的 5 参 decideNext：无限追问预算、无难度偏好 */
    public static Decision decideNext(List<InterviewQuestionDTO> candidates,
                                      Set<String> askedIds,
                                      InterviewQuestionDTO answered,
                                      TurnEvaluation evaluation,
                                      boolean mayFinishForCoverage) {
        return decideNext(candidates, askedIds, answered, evaluation, mayFinishForCoverage, 0, null);
    }

    /**
     * 一次语义调用的建议进入 Java 决策边界（P4Q-3b / P4-4b / P4Q-3c）。
     *
     * @param maxFollowUpsPerGroup 每组追问硬上限；&lt;= 0 表示不额外限制（按未问候选）
     * @param difficultyPreference 本场难度偏好（junior/mid/senior），软偏好下一主问题
     */
    public static Decision decideNext(List<InterviewQuestionDTO> candidates,
                                      Set<String> askedIds,
                                      InterviewQuestionDTO answered,
                                      TurnEvaluation evaluation,
                                      boolean mayFinishForCoverage,
                                      int maxFollowUpsPerGroup,
                                      String difficultyPreference) {
        List<InterviewQuestionDTO> pool = InterviewQuestionIdentity.withDerivedIds(candidates);
        Set<String> asked = askedIds == null ? Set.of() : new HashSet<>(askedIds);

        if (answered == null) {
            return new Decision(firstUnasked(pool, asked).orElse(null), "开始首个候选问题", "",
                false, false);
        }

        if (shouldStopFollowUp(evaluation)) {
            return moveToNextMain(pool, asked, answered, stopReason(evaluation), difficultyPreference);
        }

        // P4Q-3c：用户明确要求停止深挖当前话题——软转场，保留本轮已提取的技术证据
        if (evaluation.stopDeepDive()) {
            return moveToNextMain(pool, asked, answered,
                "用户要求停止深挖当前话题，保留已给技术证据并转场", difficultyPreference);
        }

        boolean followUpBudgetLeft = hasFollowUpBudget(pool, asked, answered, maxFollowUpsPerGroup);
        RecommendedAction recommendation = evaluation.recommendedAction();

        if (recommendation == RecommendedAction.FINISH) {
            if (mayFinishForCoverage && evaluation.missingPoints().isEmpty()) {
                return new Decision(null, recommendationReason(evaluation, "必要覆盖已完成且没有关键缺口"),
                    evaluation.transitionMessage(), true, true);
            }
            return advanceDecision(pool, asked, answered, evaluation, followUpBudgetLeft,
                difficultyPreference, "结束建议未满足覆盖边界，继续选择合法候选");
        }

        if (recommendation == RecommendedAction.NEXT_MAIN) {
            InterviewQuestionDTO recommended = recommendedCandidate(pool, asked, evaluation);
            if (recommended != null && recommended.isMain()) {
                return accepted(recommended, evaluation);
            }
            return moveToNextMain(pool, asked, answered,
                "模型建议的主问题不可用，按未问主问题顺序转场", difficultyPreference);
        }

        // FOLLOW_UP：先看有没有匹配缺口的合法候选；没有再尝试受限生成
        if (recommendation == RecommendedAction.FOLLOW_UP) {
            InterviewQuestionDTO recommended = recommendedCandidate(pool, asked, evaluation);
            if (followUpBudgetLeft && hasKeyGap(evaluation)
                && isFollowUpInAnsweredGroup(recommended, answered)) {
                return accepted(recommended, evaluation);
            }
            // 无合适候选：若有携带考察点与回答依据的生成追问，走受限生成校验
            if (generatedAccepted(evaluation, pool, answered, followUpBudgetLeft)) {
                return acceptedGenerated(evaluation);
            }
            return advanceDecision(pool, asked, answered, evaluation, followUpBudgetLeft,
                difficultyPreference, "追问建议缺少关键缺口或候选不属于当前话题，按代码边界推进");
        }

        if (recommendation == RecommendedAction.FOLLOW_UP_GENERATED) {
            if (generatedAccepted(evaluation, pool, answered, followUpBudgetLeft)) {
                return acceptedGenerated(evaluation);
            }
            return advanceDecision(pool, asked, answered, evaluation, followUpBudgetLeft,
                difficultyPreference, "受限生成缺少考察点/回答依据或超出追问预算，按代码边界推进");
        }

        return advanceDecision(pool, asked, answered, evaluation, followUpBudgetLeft,
            difficultyPreference, "模型未给出可执行的动作与候选标识，按评估缺口保守推进");
    }

    /**
     * 保守推进：有关键缺口且仍有追问预算时，选本组首个未问追问；否则转下一主问题。
     * 用于「模型建议不合法」与「未给动作」两类回退，避免机械消费候选池。
     */
    private static Decision advanceDecision(List<InterviewQuestionDTO> pool, Set<String> asked,
                                            InterviewQuestionDTO answered, TurnEvaluation evaluation,
                                            boolean followUpBudgetLeft, String difficultyPreference,
                                            String fallbackReason) {
        if (followUpBudgetLeft && hasKeyGap(evaluation)) {
            Optional<InterviewQuestionDTO> followUp = firstUnaskedInGroup(pool, asked, groupId(answered));
            if (followUp.isPresent()) {
                return new Decision(followUp.get(), fallbackReason, "", false, false);
            }
        }
        return moveToNextMain(pool, asked, answered, fallbackReason, difficultyPreference);
    }

    private static Decision moveToNextMain(List<InterviewQuestionDTO> pool, Set<String> asked,
                                           InterviewQuestionDTO answered, String reason,
                                           String difficultyPreference) {
        return new Decision(
            nextMainAfter(pool, asked, answered, difficultyPreference).orElse(null), reason, "",
            false, false);
    }

    private static Decision accepted(InterviewQuestionDTO recommended, TurnEvaluation evaluation) {
        return new Decision(recommended, recommendationReason(evaluation, "模型建议已通过 Java 边界校验"),
            evaluation.transitionMessage(), true, false);
    }

    /** 接纳受限生成：下一题由调用方按生成字段构造并持久化，这里只回传经过校验的内容 */
    private static Decision acceptedGenerated(TurnEvaluation evaluation) {
        GeneratedFollowUp generated = new GeneratedFollowUp(
            evaluation.generatedFollowUp(),
            evaluation.generatedExpectedPoint(),
            evaluation.generatedAnswerBasis());
        return new Decision(null,
            recommendationReason(evaluation, "没有合适的预置候选，基于回答生成一条短追问"),
            evaluation.transitionMessage(), true, false, generated);
    }

    /**
     * 受限生成校验（P4-4b）：追问预算 > 0、携带考察点与逐字引用回答的依据、题干非空且不与既有候选重复。
     */
    private static boolean generatedAccepted(TurnEvaluation evaluation,
                                             List<InterviewQuestionDTO> pool,
                                             InterviewQuestionDTO answered,
                                             boolean followUpBudgetLeft) {
        if (!followUpBudgetLeft || !hasKeyGap(evaluation)) {
            return false;
        }
        String text = evaluation.generatedFollowUp();
        if (text == null || text.isBlank()) {
            return false;
        }
        // 生成题必须能追溯到回答原话 / 待验证点与考察点，否则退回选择或转场
        if (isBlank(evaluation.generatedAnswerBasis()) || isBlank(evaluation.generatedExpectedPoint())) {
            return false;
        }
        String normalized = normalize(text);
        for (InterviewQuestionDTO question : pool) {
            if (normalize(question.question()).equals(normalized)) {
                return false;
            }
        }
        // 只允许挂到当前话题组内深挖
        return groupId(answered) != null;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    /** 本组是否还有追问预算：已问追问数 < 每组上限（上限 <= 0 时不额外限制） */
    private static boolean hasFollowUpBudget(List<InterviewQuestionDTO> pool, Set<String> asked,
                                             InterviewQuestionDTO answered, int maxFollowUpsPerGroup) {
        if (maxFollowUpsPerGroup <= 0) {
            return true;
        }
        String groupId = groupId(answered);
        if (groupId == null) {
            return false;
        }
        long askedInGroup = pool.stream()
            .filter(InterviewQuestionDTO::isFollowUp)
            .filter(question -> groupId.equals(question.parentQuestionId()))
            .filter(question -> asked.contains(question.questionId()))
            .count();
        return askedInGroup < maxFollowUpsPerGroup;
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
     * 已答完那一题之后的第一个未问主问题；带难度偏好时优先接近该难度的主问题（P4Q-3c 软偏好）。
     *
     * <p>「之后」按池顺序（展示顺序），但判据是**标识未问过**而不是下标比大小。难度偏好只是
     * 在同样未问的主问题里换一个个位数的偏好选择，不改变「未问才问、问过不再问」的硬约束。
     */
    private static Optional<InterviewQuestionDTO> nextMainAfter(List<InterviewQuestionDTO> pool,
                                                                Set<String> asked,
                                                                InterviewQuestionDTO answered,
                                                                String difficultyPreference) {
        int base = difficultyBase(difficultyPreference);
        int answeredIndex = indexOf(pool, answered.questionId());
        List<InterviewQuestionDTO> forward = pool.stream()
            .filter(InterviewQuestionDTO::isMain)
            .filter(question -> !asked.contains(question.questionId()))
            .filter(question -> answeredIndex < 0 || question.questionIndex() > answeredIndex)
            .toList();
        List<InterviewQuestionDTO> anyUnasked = pool.stream()
            .filter(InterviewQuestionDTO::isMain)
            .filter(question -> !asked.contains(question.questionId()))
            .toList();
        if (base > 0) {
            Optional<InterviewQuestionDTO> preferred = closestByDifficulty(forward, base)
                .or(() -> closestByDifficulty(anyUnasked, base));
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return forward.stream().findFirst()
            .or(() -> anyUnasked.stream().findFirst());
    }

    private static Optional<InterviewQuestionDTO> closestByDifficulty(List<InterviewQuestionDTO> mains,
                                                                      int base) {
        return mains.stream()
            .filter(question -> question.difficulty() != null)
            .filter(question -> Math.abs(question.difficulty() - base) <= 1)
            .min((left, right) -> Integer.compare(
                Math.abs(left.difficulty() - base), Math.abs(right.difficulty() - base)));
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
