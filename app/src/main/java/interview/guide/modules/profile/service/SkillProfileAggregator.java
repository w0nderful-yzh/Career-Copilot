package interview.guide.modules.profile.service;

import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.model.SkillProfileEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import interview.guide.modules.profile.repository.SkillProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能画像聚合服务。
 *
 * <p>Profile = Evidence 聚合（Core-4：LLM 不改写数值分）。聚合策略为一期最简的
 * 等权均值——任一分数都能通过 evidence 逐条还原，满足可追溯验收要求；
 * 引入来源权重（如面试 > 简历）留到有真实简历证据后再做。
 *
 * <p>幂等性：证据表 (user_id, skill, source_type, source_id) 唯一，重复写入按
 * 更新处理，评估任务重放不会重复计分。写入证据与重聚合在同一事务内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillProfileAggregator {

  /** 当前单用户项目的固定归属（同 agent_conversations 约定） */
  private static final String DEFAULT_USER_ID = ProfileConstants.DEFAULT_USER_ID;

  private final SkillEvidenceRepository evidenceRepository;
  private final SkillProfileRepository profileRepository;

  /**
   * 写入一批证据并重聚合受影响技能。
   *
   * @param evidences 证据列表（skill / sourceType / sourceId / score / occurredAt）
   * @return 受影响并完成重聚合的技能名
   */
  @Transactional(rollbackFor = Exception.class)
  public List<String> applyEvidence(List<SkillEvidenceEntity> evidences) {
    if (evidences == null || evidences.isEmpty()) {
      return List.of();
    }

    // 1. 幂等写入：已存在则更新分数与时间（评估重放场景下分数可能重算）
    for (SkillEvidenceEntity evidence : evidences) {
      evidenceRepository.findByUserIdAndSkillAndSourceTypeAndSourceId(
              evidence.getUserId(), evidence.getSkill(),
              evidence.getSourceType(), evidence.getSourceId())
          .ifPresentOrElse(existing -> {
            existing.setScore(evidence.getScore());
            existing.setOccurredAt(evidence.getOccurredAt());
          }, () -> evidenceRepository.save(evidence));
    }
    evidenceRepository.flush();

    // 2. 重聚合受影响技能
    List<String> skills = evidences.stream()
        .map(SkillEvidenceEntity::getSkill)
        .distinct()
        .toList();
    skills.forEach(this::reaggregateSkill);
    log.info("画像证据已应用: 技能={}, 证据数={}", skills, evidences.size());
    return skills;
  }

  /**
   * 重聚合单个技能：无剩余证据时删除画像行，否则按等权均值更新。
   */
  @Transactional(rollbackFor = Exception.class)
  public void reaggregateSkill(String skill) {
    List<SkillEvidenceEntity> evidences =
        evidenceRepository.findByUserIdAndSkill(DEFAULT_USER_ID, skill);

    if (evidences.isEmpty()) {
      profileRepository.findByUserIdAndSkill(DEFAULT_USER_ID, skill)
          .ifPresent(profileRepository::delete);
      log.info("技能证据已清空，画像行已删除: skill={}", skill);
      return;
    }

    int score = (int) Math.round(
        evidences.stream().mapToInt(SkillEvidenceEntity::getScore).average().orElse(0));
    int count = evidences.size();

    SkillProfileEntity profile = profileRepository
        .findByUserIdAndSkill(DEFAULT_USER_ID, skill)
        .orElseGet(() -> {
          SkillProfileEntity created = new SkillProfileEntity();
          created.setUserId(DEFAULT_USER_ID);
          created.setSkill(skill);
          return created;
        });
    profile.setScore(score);
    profile.setEvidenceCount(count);
    // updatedAt 由实体 @PrePersist/@PreUpdate 维护（最后聚合时间）
    profileRepository.save(profile);
    log.debug("画像已聚合: skill={}, score={}, evidenceCount={}", skill, score, count);
  }

  /**
   * 面试会话删除后的证据级联清理（单个会话）。
   * 轮次证据 sourceId 为 "sessionId:questionIndex"，按前缀匹配清理。
   */
  @Transactional(rollbackFor = Exception.class)
  public void removeInterviewSessionEvidence(String sessionId) {
    List<SkillEvidenceEntity> evidences = evidenceRepository
        .findBySourceTypeAndSourceIdStartingWith(
            EvidenceSourceType.INTERVIEW_TURN, sessionId + ":");
    removeEvidenceAndReaggregate(evidences);
  }

  /**
   * 面试会话删除后的证据级联清理（批量：删除简历时清理多个会话）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void removeInterviewSessionEvidence(List<String> sessionIds) {
    if (sessionIds == null || sessionIds.isEmpty()) {
      return;
    }
    List<SkillEvidenceEntity> evidences = sessionIds.stream()
        .flatMap(id -> evidenceRepository.findBySourceTypeAndSourceIdStartingWith(
            EvidenceSourceType.INTERVIEW_TURN, id + ":").stream())
        .toList();
    removeEvidenceAndReaggregate(evidences);
  }

  /**
   * 删除证据并重聚合受影响技能。
   */
  private void removeEvidenceAndReaggregate(List<SkillEvidenceEntity> evidences) {
    if (evidences.isEmpty()) {
      return;
    }
    Map<String, List<SkillEvidenceEntity>> bySkill = evidences.stream()
        .collect(Collectors.groupingBy(SkillEvidenceEntity::getSkill));
    evidenceRepository.deleteAll(evidences);
    evidenceRepository.flush();
    bySkill.keySet().forEach(this::reaggregateSkill);
    log.info("会话证据已级联清理: 技能={}, 证据数={}", bySkill.keySet(), evidences.size());
  }
}
