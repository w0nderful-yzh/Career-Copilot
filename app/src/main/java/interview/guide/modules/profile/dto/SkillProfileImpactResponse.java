package interview.guide.modules.profile.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一场面试带来的画像变化（P3 待收口）。
 *
 * <p>回答的是「这场面试让我哪项变了、凭什么变的」——分数变化必须能落回具体证据，
 * 而证据本身已带来源（场次 + 题号）与时间，前端据此提供追溯入口。
 *
 * <p>差分不需要额外存储：{@code before} = 排除本场证据后的均值，
 * {@code after} = 含本场证据的均值，两者都能由 skill_evidence 直接重算。
 */
public record SkillProfileImpactResponse(
    String sessionId,
    /** 本场考到的技能；按技能名排序（稳定的返回顺序，展示排序由前端决定） */
    List<SkillImpactDTO> skills
) {

  public record SkillImpactDTO(
      String skill,
      /** 本场之前的分（排除本场证据）；null = 该技能在本场首次被考到 */
      Integer beforeScore,
      /** 含本场证据后的分 */
      int afterScore,
      /** 0 表示无变化；beforeScore 为 null（首次考到）时也返回 0，由前端渲染成「新增」 */
      int delta,
      List<SessionEvidenceDTO> sessionEvidences
  ) {}

  /**
   * 本场贡献的一条证据。
   *
   * @param sourceId        evidence.sourceId：{@code "sessionId:questionKey"}，供追溯
   * @param questionKey     题目稳定标识（P4-1）；旧数据是下标或 {@code legacy-<下标>}
   * @param questionOrdinal 真实发生顺序（1 起），用于展示「第 N 题」；解析不到时为 null
   */
  public record SessionEvidenceDTO(
      String sourceId,
      String questionKey,
      Integer questionOrdinal,
      int score,
      LocalDateTime occurredAt
  ) {}
}
