package interview.guide.modules.profile.repository;

import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 技能证据 Repository
 */
@Repository
public interface SkillEvidenceRepository extends JpaRepository<SkillEvidenceEntity, Long> {

  /** 幂等写入用：同来源同技能只保留一条 */
  Optional<SkillEvidenceEntity> findByUserIdAndSkillAndSourceTypeAndSourceId(
      String userId, String skill, EvidenceSourceType sourceType, String sourceId);

  /** 聚合指定技能的全部证据 */
  List<SkillEvidenceEntity> findByUserIdAndSkill(String userId, String skill);

  /** 指定技能的证据明细（追溯展示用，时间倒序） */
  List<SkillEvidenceEntity> findByUserIdAndSkillOrderByOccurredAtDesc(String userId, String skill);

  /** 聚合全部技能涉及的证据（重聚合时按 skill 分组） */
  List<SkillEvidenceEntity> findByUserId(String userId);

  /** 会话级联清理用：轮次证据 sourceId 形如 "sessionId:questionIndex"，按前缀匹配 */
  List<SkillEvidenceEntity> findBySourceTypeAndSourceIdStartingWith(
      EvidenceSourceType sourceType, String sourceIdPrefix);

  /** 声明型证据的整体替换/清理用：RESUME 证据的 sourceId 即 resumeId */
  List<SkillEvidenceEntity> findBySourceTypeAndSourceId(
      EvidenceSourceType sourceType, String sourceId);

  /** 声明型证据查询：某技能下按来源类型过滤（区分「有分」与「仅简历声明」） */
  List<SkillEvidenceEntity> findByUserIdAndSourceType(String userId, EvidenceSourceType sourceType);
}
