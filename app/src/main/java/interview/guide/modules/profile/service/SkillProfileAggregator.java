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
   * 重聚合单个技能：无剩余**有分**证据时删除画像行，否则按等权均值更新。
   *
   * <p>声明型证据（score 为 null，如简历列出的技能）不参与均值——画像分必须能由有分证据
   * 逐条还原；某技能只有声明证据时不产生画像行（它属于 declaredSkills，由查询侧单独返回）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void reaggregateSkill(String skill) {
    List<SkillEvidenceEntity> scored =
        evidenceRepository.findByUserIdAndSkill(DEFAULT_USER_ID, skill).stream()
            .filter(evidence -> evidence.getScore() != null)
            .toList();

    if (scored.isEmpty()) {
      profileRepository.findByUserIdAndSkill(DEFAULT_USER_ID, skill)
          .ifPresent(profileRepository::delete);
      log.info("技能已无有分证据，画像行已删除: skill={}", skill);
      return;
    }

    int score = (int) Math.round(
        scored.stream().mapToInt(SkillEvidenceEntity::getScore).average().orElse(0));
    int count = scored.size();

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
   * 用一批简历声明**整体替换**某份简历的 RESUME 证据。
   *
   * <p>整体替换而非增量：重新解析或用户纠正后，旧的技能条目可能已不存在，
   * 增量写入会留下永远清不掉的幽灵技能。
   *
   * @param resumeId     简历 ID（作为 RESUME 证据的 sourceId）
   * @param declarations 声明证据（score 必须为 null）
   */
  @Transactional(rollbackFor = Exception.class)
  public void replaceResumeDeclarations(Long resumeId, List<SkillEvidenceEntity> declarations) {
    removeResumeEvidence(resumeId);
    if (declarations == null || declarations.isEmpty()) {
      return;
    }
    evidenceRepository.saveAll(declarations);
    evidenceRepository.flush();
    List<String> skills = declarations.stream()
        .map(SkillEvidenceEntity::getSkill)
        .distinct()
        .toList();
    // 声明本身不影响分数，但技能集合可能变化（例如某技能原本只有声明、现在要删行）
    skills.forEach(this::reaggregateSkill);
    log.info("简历声明证据已写入: resumeId={}, 技能数={}", resumeId, skills.size());
  }

  /**
   * 清理某份简历的全部 RESUME 声明证据（重新解析前 / 简历删除时）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void removeResumeEvidence(Long resumeId) {
    List<SkillEvidenceEntity> evidences = evidenceRepository.findBySourceTypeAndSourceId(
        EvidenceSourceType.RESUME, String.valueOf(resumeId));
    if (evidences.isEmpty()) {
      return;
    }
    List<String> skills = evidences.stream()
        .map(SkillEvidenceEntity::getSkill)
        .distinct()
        .toList();
    evidenceRepository.deleteAll(evidences);
    evidenceRepository.flush();
    skills.forEach(this::reaggregateSkill);
    log.info("简历声明证据已清理: resumeId={}, 技能数={}", resumeId, skills.size());
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
