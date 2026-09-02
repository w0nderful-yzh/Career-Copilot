package interview.guide.modules.profile.repository;

import interview.guide.modules.profile.model.SkillProfileEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 技能画像 Repository
 */
@Repository
public interface SkillProfileRepository extends JpaRepository<SkillProfileEntity, Long> {

  Optional<SkillProfileEntity> findByUserIdAndSkill(String userId, String skill);

  List<SkillProfileEntity> findByUserIdOrderByScoreDesc(String userId);

  /** 删除指定技能的画像（证据清空且不再有新证据时调用） */
  void deleteByUserIdAndSkill(String userId, String skill);
}
