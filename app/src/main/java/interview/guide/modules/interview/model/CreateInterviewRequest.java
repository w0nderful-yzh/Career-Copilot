package interview.guide.modules.interview.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建面试会话请求
 */
public record CreateInterviewRequest(
    String resumeText,      // 简历文本内容（可选，无简历时为通用面试）

    /**
     * 预计时长（分钟，P4Q-2）：面试计划的真实单位。
     *
     * <p>规模（主问题数）与收束判据都由它推导，不再由题数决定；
     * 缺省按 {@link interview.guide.modules.interview.InterviewDefaults} 的默认时长。
     */
    @Min(value = 5, message = "预计时长最少5分钟")
    @Max(value = 120, message = "预计时长最多120分钟")
    Integer plannedDurationMinutes,

    /**
     * 必要覆盖（P4Q-2）：这些话题必须问到才算覆盖完成。
     *
     * <p>按话题 label/key 匹配主问题；空/缺省 = 未声明必要覆盖，不按覆盖完成提前收束。
     * 候选池里不存在的话题在创建后会被剔除——「无实习经历不强行问实习」由这一点保证。
     */
    List<String> requiredTopics,

    /**
     * 仅旧调用方兼容：P4Q-2 起规模由 {@link #plannedDurationMinutes} 推导，
     * 该字段不再影响出题规模；新调用方不要传。
     */
    @Min(value = 3, message = "题目数量最少3题")
    @Max(value = 20, message = "题目数量最多20题")
    Integer questionCount,

    Long resumeId,          // 简历ID（可选，无简历时不传）

    Boolean forceCreate,    // 是否强制创建新会话（忽略未完成的会话），默认为 false

    String llmProvider,     // LLM提供商

    @NotBlank(message = "面试主题不能为空")
    String skillId,         // 面试主题 ID（如 java-backend, frontend, custom 等）

    String difficulty,      // 难度级别: junior / mid / senior

    List<CategoryDTO> customCategories,   // 自定义面试的分类（JD 解析结果）

    String jdText,                         // JD 原文（自定义面试时作为出题依据）

    String requestId,                      // 创建请求幂等键，刷新/重试时复用同一会话

    Boolean adaptive,                      // P4-3 是否启用逐题评估+自适应选题；null=false

    /**
     * 重点考察方向（P3 待收口）：按分类 key 或 label 匹配，裁剪该方向的出题分类。
     *
     * <p>由面试提案 Agent 依据技能画像（低分技能 + 简历已列未考）给出。
     * 空/未命中任何分类时按原方向全量出题（focus 是「重点」而非「只考这些」）。
     * 仅对预设方向生效；JD 自定义方向本身就是 focus，不叠加。
     */
    List<String> focusCategories
) {

    /**
     * 旧契约兼容构造器：题数会被折算成时长（每主问题 4 分钟），
     * 让 Agent 等旧调用方在 P4-8a 改造契约前保持可用。
     */
    public CreateInterviewRequest(String resumeText, int questionCount, Long resumeId,
                                  Boolean forceCreate, String llmProvider, String skillId,
                                  String difficulty, List<CategoryDTO> customCategories,
                                  String jdText, String requestId, Boolean adaptive,
                                  List<String> focusCategories) {
        this(resumeText, null, null, questionCount, resumeId, forceCreate, llmProvider, skillId,
            difficulty, customCategories, jdText, requestId, adaptive, focusCategories);
    }
}
