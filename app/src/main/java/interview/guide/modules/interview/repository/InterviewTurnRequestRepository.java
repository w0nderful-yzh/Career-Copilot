package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewTurnRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 逐轮提交幂等记录 Repository（P4-9a）
 */
@Repository
public interface InterviewTurnRequestRepository extends JpaRepository<InterviewTurnRequestEntity, Long> {

    /**
     * 按「会话 + 请求标识」查已处理过的请求（重放时返回原结果）
     */
    Optional<InterviewTurnRequestEntity> findBySessionIdAndRequestId(String sessionId, String requestId);
}
