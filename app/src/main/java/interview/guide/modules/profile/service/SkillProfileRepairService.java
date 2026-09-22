package interview.guide.modules.profile.service;

import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 画像历史数据修复（P4Q-6）。
 *
 * <p>为什么需要单独的修复例程：缺陷期间产生的证据已经落库（dev 库里真实存在
 * `Java（追问1）`、`MySQL（追问1）` 等 4 条伪技能证据），只改出题侧不会让历史数据变对。
 *
 * <p>**可重跑且不重复计分**：合并时以 (userId, skill, sourceType, sourceId) 为准——
 * 基技能已有同源证据时直接丢弃伪技能那条，而不是改用技能名造成重复计分；
 * 再次执行时查询结果为空，是纯粹的空操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillProfileRepairService {

  private static final String DEFAULT_USER_ID = ProfileConstants.DEFAULT_USER_ID;

  private final SkillEvidenceRepository evidenceRepository;
  private final SkillProfileAggregator aggregator;

  /**
   * 把「追问序号拼进技能名」的历史证据合并回稳定技能，并重算画像。
   *
   * @return 本次修复的实际动作与受影响技能，供调用方核对
   */
  @Transactional(rollbackFor = Exception.class)
  public RepairReport mergeFollowUpPseudoSkills() {
    List<SkillEvidenceEntity> candidates =
        evidenceRepository.findFollowUpSuffixedSkills(DEFAULT_USER_ID);

    List<SkillEvidenceEntity> toRename = new ArrayList<>();
    List<SkillEvidenceEntity> toDrop = new ArrayList<>();
    Set<String> affectedSkills = new LinkedHashSet<>();
    Set<String> pseudoSkills = new LinkedHashSet<>();

    for (SkillEvidenceEntity evidence : candidates) {
      String pseudoSkill = evidence.getSkill();
      if (!SkillNameNormalizer.isFollowUpPseudoSkill(pseudoSkill)) {
        // LIKE 粗筛的漏网者（技能名里恰好含「追问」但不是后缀形式）：不动
        continue;
      }
      String baseSkill = SkillNameNormalizer.normalize(pseudoSkill);
      pseudoSkills.add(pseudoSkill);
      affectedSkills.add(baseSkill);

      Optional<SkillEvidenceEntity> existing = evidenceRepository
          .findByUserIdAndSkillAndSourceTypeAndSourceId(
              DEFAULT_USER_ID, baseSkill, evidence.getSourceType(), evidence.getSourceId());
      if (existing.isPresent()) {
        // 基技能已有同一来源的证据：丢弃伪技能那条（保证不重复计分）
        toDrop.add(evidence);
      } else {
        toRename.add(evidence);
      }
    }

    if (toDrop.isEmpty() && toRename.isEmpty()) {
      log.info("画像修复：未发现追问后缀伪技能，无需处理");
      return new RepairReport(candidates.size(), 0, 0, List.of());
    }

    // 先删再改：避免「改名后与既有行撞唯一约束」的中间态
    if (!toDrop.isEmpty()) {
      evidenceRepository.deleteAll(toDrop);
      evidenceRepository.flush();
    }
    toRename.forEach(evidence -> evidence.setSkill(SkillNameNormalizer.normalize(evidence.getSkill())));
    if (!toRename.isEmpty()) {
      evidenceRepository.saveAll(toRename);
      evidenceRepository.flush();
    }

    // 重算：基技能吸收合并后的证据；伪技能已无证据，其画像行由聚合器删除
    Set<String> toReaggregate = new LinkedHashSet<>(affectedSkills);
    toReaggregate.addAll(pseudoSkills);
    toReaggregate.forEach(aggregator::reaggregateSkill);

    log.info("画像修复完成：候选={}, 合并={}, 丢弃重复={}, 重算技能={}",
        candidates.size(), toRename.size(), toDrop.size(), toReaggregate);
    return new RepairReport(candidates.size(), toRename.size(), toDrop.size(),
        List.copyOf(toReaggregate));
  }

  /**
   * 修复结果报告。
   *
   * @param scanned            粗筛出的候选证据条数
   * @param merged             改名为稳定技能的条数
   * @param droppedDuplicates  因基技能已有同源证据而丢弃的条数（不计分）
   * @param reaggregatedSkills 本次重算过画像的技能（含被清空的伪技能）
   */
  public record RepairReport(
      int scanned,
      int merged,
      int droppedDuplicates,
      List<String> reaggregatedSkills
  ) {}
}
