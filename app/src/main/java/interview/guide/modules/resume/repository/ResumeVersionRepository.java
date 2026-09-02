package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 简历版本 Repository
 */
@Repository
public interface ResumeVersionRepository extends JpaRepository<ResumeVersionEntity, Long> {

  /** 指定简历的全部版本（新版本在前） */
  List<ResumeVersionEntity> findByResumeIdOrderByVersionDesc(Long resumeId);

  /** 最新版本（取数默认入口） */
  Optional<ResumeVersionEntity> findFirstByResumeIdOrderByVersionDesc(Long resumeId);

  /** 按版本号精确定位 */
  Optional<ResumeVersionEntity> findByResumeIdAndVersion(Long resumeId, Integer version);

  /** 指定简历的最大版本号（分配新版本号用） */
  Optional<ResumeVersionEntity> findTopByResumeIdOrderByVersionDesc(Long resumeId);
}
