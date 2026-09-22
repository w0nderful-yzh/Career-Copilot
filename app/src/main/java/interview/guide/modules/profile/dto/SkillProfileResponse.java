package interview.guide.modules.profile.dto;

import interview.guide.modules.profile.model.EvidenceSourceType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能画像查询结果：聚合分 + 可追溯证据明细 + 简历声明。
 *
 * <p>两类内容语义不同，刻意分开返回：
 * <ul>
 *   <li>{@code skills}：**有分**技能（分数 = 有分证据均值，可逐条还原）；
 *   <li>{@code declaredSkills}：**只有简历声明、尚无任何评分证据**的技能。
 *       它们没有分数（给未验证的技能编分违反 Core-4），但信息量最大——
 *       正是下一场面试最该重点考察的部分。
 * </ul>
 *
 * <p>一次调用返回全部内容，避免 Agent 按技能多次往返。
 */
public record SkillProfileResponse(
    List<SkillProfileDTO> skills,
    List<DeclaredSkillDTO> declaredSkills
) {

  public record SkillProfileDTO(
      String skill,
      int score,
      int evidenceCount,
      LocalDateTime updatedAt,
      List<EvidenceDTO> evidences
  ) {}

  /**
   * 一条证据。
   *
   * <p>{@code score} 为 null 表示**声明型证据**（简历列出该技能，尚无评分），
   * 它不出现在均值里；{@code sourceId} 面试为 "sessionId:questionIndex"、简历为 resumeId。
   */
  public record EvidenceDTO(
      EvidenceSourceType sourceType,
      String sourceId,
      Integer score,
      LocalDateTime occurredAt
  ) {}

  /** 简历已列、尚无评分证据的技能（"待验证"） */
  public record DeclaredSkillDTO(
      String skill,
      /** 来源简历 ID（字符串形，与 INTERVIEW_TURN 的 sourceId 保持一致） */
      String resumeId,
      LocalDateTime declaredAt
  ) {}
}
