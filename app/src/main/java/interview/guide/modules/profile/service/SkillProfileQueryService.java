package interview.guide.modules.profile.service;

import interview.guide.modules.profile.dto.SkillProfileResponse;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能画像查询服务：画像列表 + 可追溯证据明细 + 简历声明。
 *
 * <p>消费方：get_skill_profile Agent Tool（P3-2）、面试提案选 focus（P3 待收口）、
 * 前端画像面板。只读查询，统一 {@code readOnly = true}。
 */
@Service
@RequiredArgsConstructor
public class SkillProfileQueryService {

  private final SkillProfileRepository profileRepository;
  private final SkillEvidenceRepository evidenceRepository;

  /** 全部技能画像 + 各自证据明细 + 简历声明（一次取全，Agent/前端共用） */
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
    return new SkillProfileResponse(skills, declaredSkills(profiles));
  }

  /** 指定技能的全部证据（occurredAt 倒序） */
  @Transactional(readOnly = true)
  public List<SkillEvidenceEntity> listEvidence(String skill) {
    return evidenceRepository.findByUserIdAndSkillOrderByOccurredAtDesc(
        ProfileConstants.DEFAULT_USER_ID, skill);
  }

  /**
   * 技能画像**不含证据明细**：给只需要「技能 → 分数」的消费方用（如逐轮评估的画像基线）。
   *
   * <p>刻意与 {@link #getProfileWithEvidence()} 分开：后者对每个技能各查一次证据（N+1），
   * 在每轮问答里调用代价过高；这里只查一次画像表。
   */
  @Transactional(readOnly = true)
  public List<SkillProfileResponse.SkillProfileDTO> listProfiles() {
    return profileRepository.findByUserIdOrderByScoreDesc(ProfileConstants.DEFAULT_USER_ID)
        .stream()
        .map(profile -> new SkillProfileResponse.SkillProfileDTO(
            profile.getSkill(),
            profile.getScore(),
            profile.getEvidenceCount(),
            profile.getUpdatedAt(),
            List.of()))
        .toList();
  }

  /**
   * 简历已列、尚无评分证据的技能。
   *
   * <p>判据是「该技能存在 RESUME 声明证据，但不在 skill_profiles 里」——skill_profiles
   * 只会为有分证据建行，所以不在其中就代表从未被考过。技能名比较大小写不敏感：
   * 简历原文与面试 category 的大小写习惯经常不一致（"java" vs "Java"），
   * 否则同一技能会同时出现在两个列表里。
   */
  private List<SkillProfileResponse.DeclaredSkillDTO> declaredSkills(
      List<SkillProfileEntity> profiles) {
    Set<String> scoredSkills = profiles.stream()
        .map(profile -> profile.getSkill().toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());

    return evidenceRepository
        .findByUserIdAndSourceType(ProfileConstants.DEFAULT_USER_ID, EvidenceSourceType.RESUME)
        .stream()
        .filter(evidence -> !scoredSkills.contains(
            evidence.getSkill().toLowerCase(Locale.ROOT)))
        .sorted(Comparator.comparing(SkillEvidenceEntity::getSkill))
        .map(evidence -> new SkillProfileResponse.DeclaredSkillDTO(
            evidence.getSkill(), evidence.getSourceId(), evidence.getOccurredAt()))
        .toList();
  }

  private static SkillProfileResponse.EvidenceDTO toEvidenceDTO(SkillEvidenceEntity entity) {
    return new SkillProfileResponse.EvidenceDTO(
        entity.getSourceType(),
        entity.getSourceId(),
        entity.getScore(),
        entity.getOccurredAt());
  }
}
