package interview.guide.modules.agenttool.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Agent Tool 的**类型化请求模型**（ARCH-1：契约单一事实源）。
 *
 * <p>这份模型是 Tool 入参契约的唯一事实源：
 * <ul>
 *   <li>Java 侧按它反序列化与校验入参（未知字段直接拒绝，类型错误给出明确错误码）；</li>
 *   <li>JSON Schema 由 {@code AgentToolSchemaExporter} 从它导出到 {@code docs/contracts/agent-tools.json}，
 *       再交给 Python 侧做调用前校验；</li>
 *   <li>{@code /api/agent/tools}（Tool Discovery）暴露的 inputSchema 也改为由它生成，
 *       不再维护第二份人肉字符串。</li>
 * </ul>
 *
 * <p><b>改动约定</b>：改这里的字段或约束后，必须重新导出 schema
 * （`AGENT_TOOLS_SCHEMA_WRITE=true ./gradlew :app:test --tests "*AgentToolContractTest*"`），
 * 否则 CI 会因漂移检查失败并给出该提示。
 *
 * <p><b>required 的判据</b>：带 {@link NotNull} / {@link NotBlank} / {@link NotEmpty} 的字段为必填；
 * 其余字段可缺省。可选字段不要用来掩盖「必要上下文缺失」——缺失时应在业务层给出可见原因。
 */
public final class AgentToolRequests {

  private AgentToolRequests() {}

  /** get_resume_list：无参数 */
  public record GetResumeList() {}

  /** get_skill_profile：无参数 */
  public record GetSkillProfile() {}

  /** get_resume_version：优化子图取数入口 */
  public record GetResumeVersion(
      @NotNull @Positive @ToolParam("简历 ID") Long resumeId,
      @Positive @ToolParam("指定版本号；缺省取最新 ACTIVE 版本") Integer version) {}

  /** get_resume_analysis：分析结果可能尚未就绪，由调用方处理 RESUME_ANALYSIS_NOT_FOUND */
  public record GetResumeAnalysis(
      @NotNull @Positive @ToolParam("简历 ID") Long resumeId) {}

  /** get_resume：简历原文通道（显式文本用途，见 CreateInterview.resumeText 的取舍说明） */
  public record GetResume(
      @NotNull @Positive @ToolParam("简历 ID") Long resumeId,
      @Positive @ToolParam("服务端截断上限（字符），缺省 20000；截断在 Java 侧完成（Token 纪律）")
          Integer maxChars) {}

  /** get_job：JD 全文 */
  public record GetJob(
      @NotNull @Positive @ToolParam("JD ID") Long jobId) {}

  /** get_interview_history：不带 resumeId 时返回全部 */
  public record GetInterviewHistory(
      @Positive @ToolParam("按简历过滤；缺省返回全部面试") Long resumeId) {}

  /** get_interview_report：单场面试完整报告 */
  public record GetInterviewReport(
      @NotBlank @ToolParam("面试会话 ID") String sessionId) {}

  /** list_knowledge_bases：无参数 */
  public record ListKnowledgeBases() {}

  /** search_knowledge：RAG 问答 */
  public record SearchKnowledge(
      @NotEmpty @ToolParam("检索目标知识库 ID 列表，至少一个")
          List<@NotNull @Positive Long> knowledgeBaseIds,
      @NotBlank @ToolParam("待检索的问题") String question) {}

  /** list_skills：无参数 */
  public record ListSkills() {}

  /** create_interview：CONFIRM_WRITE，用户确认后执行 */
  public record CreateInterview(
      @NotBlank @ToolParam("面试方向 skillId（来自 list_skills）") String skillId,
      @ToolParam("难度：junior / mid / senior；缺省 mid") String difficulty,
      @Min(5) @Max(120) @ToolParam("预计时长（分钟，5-120）；缺省 20")
          Integer plannedDurationMinutes,
      @ToolParam("必要覆盖的话题 key；只保留本场候选池真实存在的主问题话题")
          List<String> requiredTopics,
      @Positive @ToolParam("目标简历 ID。与 resumeText 二选一：传了 ID 由 Java 解析简历来源与版本，"
          + "调用方不必也不应传原文") Long resumeId,
      @ToolParam("显式简历原文（仅手动面试 / 知识库面试等无 ID 场景使用）。传了 resumeId 时忽略本字段")
          String resumeText,
      @ToolParam("忽略未完成会话复用、强制新建；缺省 false") Boolean forceCreate,
      @ToolParam("创建请求幂等键：同一次用户确认的网络重试应复用同一值，避免重复建会话")
          String requestId,
      @ToolParam("重点考察的分类 key；未命中任何分类时按原方向全量出题")
          List<String> focusCategories) {}

  /** apply_resume_patches：CONFIRM_WRITE，用户在提案块勾选后执行 */
  public record ApplyResumePatches(
      @NotNull @Positive @ToolParam("优化提案 ID") Long proposalId,
      @ToolParam("勾选的 Patch ID 列表；缺省表示全部应用") List<String> patchIds) {}
}
