package interview.guide.modules.job.repository;

import interview.guide.modules.job.model.JobDescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDescriptionRepository extends JpaRepository<JobDescriptionEntity, Long> {
}
