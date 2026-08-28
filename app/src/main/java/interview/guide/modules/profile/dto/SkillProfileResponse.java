package interview.guide.modules.profile.dto;

import interview.guide.modules.profile.model.EvidenceSourceType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能画像查询结果：聚合分 + 可追溯证据明细。
 *
 * <p>一次调用返回全部技能与各自证据，避免 Agent 按技能多次往返；
 * 证据条数与聚合分一致（score = 证据均值），满足「任一分数可追溯」验收。
 */
public record SkillProfileResponse(
    List<SkillProfileDTO> skills
) {

  public record SkillProfileDTO(
      String skill,
      int score,
      int evidenceCount,
      LocalDateTime updatedAt,
      List<EvidenceDTO> evidences
  ) {}

  public record EvidenceDTO(
      EvidenceSourceType sourceType,
      String sourceId,
      int score,
      LocalDateTime occurredAt
  ) {}
}
