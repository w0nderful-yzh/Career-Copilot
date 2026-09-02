package interview.guide.modules.profile.service;

import interview.guide.modules.profile.dto.SkillProfileResponse;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能画像查询服务：画像列表 + 可追溯证据明细。
 *
 * <p>消费方：get_skill_profile Agent Tool（P3-2）与前端画像面板（后续 API 化）。
 * 只读查询，统一 {@code readOnly = true}。
 */
@Service
@RequiredArgsConstructor
public class SkillProfileQueryService {

  private final SkillProfileRepository profileRepository;
  private final SkillEvidenceRepository evidenceRepository;

  /** 全部技能画像 + 各自证据明细（一次取全，Agent/前端共用） */
  @Transactional(readOnly = true)
  public SkillProfileResponse getProfileWithEvidence() {
    List<SkillProfileEntity> profiles =
        profileRepository.findByUserIdOrderByScoreDesc(ProfileConstants.DEFAULT_USER_ID);
    List<SkillProfileResponse.SkillProfileDTO> skills = profiles.stream()
        .map(profile -> new SkillProfileResponse.SkillProfileDTO(
            profile.getSkill(),
            profile.getScore(),
            profile.getEvidenceCount(),
            profile.getUpdatedAt(),
            evidenceRepository.findByUserIdAndSkillOrderByOccurredAtDesc(
                    ProfileConstants.DEFAULT_USER_ID, profile.getSkill())
                .stream()
                .map(SkillProfileQueryService::toEvidenceDTO)
                .toList()))
        .toList();
    return new SkillProfileResponse(skills);
  }

  /** 指定技能的全部证据（occurredAt 倒序） */
  @Transactional(readOnly = true)
  public List<SkillEvidenceEntity> listEvidence(String skill) {
    return evidenceRepository.findByUserIdAndSkillOrderByOccurredAtDesc(
        ProfileConstants.DEFAULT_USER_ID, skill);
  }

  /** 判断来源类型是否有真实数据产出（前端展示「暂无证据」提示用） */
  public static boolean hasSource(EvidenceSourceType type) {
    // 一期只有面试逐题分真实产出；RESUME 待 P2-0、INTERVIEW_SESSION 为冗余证据
    return type == EvidenceSourceType.INTERVIEW_TURN;
  }

  private static SkillProfileResponse.EvidenceDTO toEvidenceDTO(SkillEvidenceEntity entity) {
    return new SkillProfileResponse.EvidenceDTO(
        entity.getSourceType(),
        entity.getSourceId(),
        entity.getScore(),
        entity.getOccurredAt());
  }
}
