package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试计划（P4Q-2）：以「预计时长 + 必要覆盖」表达，取代题数配额。
 *
 * <p>为什么不用题数：题数把「约 20 分钟、实习和项目优先」这类真实意图压扁成一个整数，
 * 于是回答质量好与差都问同样多的题，时间到了题还没问完、题问完了时间还剩一大截。
 * 时长才是用户真正给的约束。
 *
 * @param plannedDurationMinutes 预计时长（分钟）：规模与收束判据都由它推导
 * @param requiredTopics         必要覆盖的话题（label/key）。
 *                               **空 = 未声明必要覆盖**——此时「覆盖完成」不作为收束条件，
 *                               面试按候选与预算自然收束；只有显式声明的必要覆盖才会触发
 *                               COVERAGE_SATISFIED（否则默认口径会在主问题问完后跳过追问，
 *                               把「还有价值的深挖」提前掐掉）
 */
public record InterviewPlan(
    int plannedDurationMinutes,
    List<String> requiredTopics
) {

    /** 未显式给出时长时的默认计划 */
    public static final int DEFAULT_DURATION_MINUTES = 20;

    /** 每个主问题的预算（分钟）：据此把时长换算成主问题规模 */
    public static final int MINUTES_PER_MAIN_QUESTION = 4;

    public static final int MIN_DURATION_MINUTES = 5;
    public static final int MAX_DURATION_MINUTES = 120;

    public InterviewPlan {
        requiredTopics = requiredTopics == null ? List.of() : List.copyOf(requiredTopics);
    }

    /** 主问题规模：由时长推导并夹取到合理区间（3-12 个主题） */
    public static int mainCountFor(int plannedDurationMinutes) {
        return Math.max(3, Math.min(12, plannedDurationMinutes / MINUTES_PER_MAIN_QUESTION));
    }

    /**
     * 组装计划。
     *
     * @param plannedDurationMinutes 新契约的时长；null 时回落旧契约
     * @param requiredTopics         必要覆盖；null = 全部主问题话题
     * @param legacyQuestionCount    旧契约的题数：按「每主问题 4 分钟」折算成时长，
     *                               让 Agent 等旧调用方在 P4-8a 改造前不至于丢掉规模意图
     */
    public static InterviewPlan of(Integer plannedDurationMinutes, List<String> requiredTopics,
                                   Integer legacyQuestionCount) {
        int minutes = plannedDurationMinutes != null
            ? plannedDurationMinutes
            : (legacyQuestionCount != null
                ? legacyQuestionCount * MINUTES_PER_MAIN_QUESTION
                : DEFAULT_DURATION_MINUTES);
        return new InterviewPlan(
            Math.max(MIN_DURATION_MINUTES, Math.min(MAX_DURATION_MINUTES, minutes)),
            requiredTopics);
    }

    /** 时间预算（秒） */
    public int budgetSeconds() {
        return plannedDurationMinutes * 60;
    }
}
