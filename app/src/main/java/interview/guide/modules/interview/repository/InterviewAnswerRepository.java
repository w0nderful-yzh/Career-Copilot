package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试答案Repository
 */
@Repository
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity, Long> {
    
    /**
     * 根据会话查找所有答案
     */
    List<InterviewAnswerEntity> findBySessionOrderByQuestionIndex(InterviewSessionEntity session);
    
    /**
     * 根据会话ID查找所有答案
     */
    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(Long sessionId);
    
    /**
     * 根据会话 sessionId 字符串查找所有答案
     */
    List<InterviewAnswerEntity> findBySession_SessionIdOrderByQuestionIndex(String sessionId);

    /**
     * 根据会话 sessionId 与**题目标识**查找单条轮次（P4-1：身份入口，upsert 走这里）
     */
    Optional<InterviewAnswerEntity> findBySession_SessionIdAndQuestionId(String sessionId, String questionId);

    /**
     * 真实发生顺序（P4-1）：只在 {@code turnOrdinal} 非空的轮次里取最大值。
     *
     * <p>报告补写的「未考察」行不占序号——它们不属于面试轨迹。
     * 调用方须在写事务内使用，保证并发下序号不重复。
     */
    @Query("""
        select coalesce(max(a.turnOrdinal), 0) from InterviewAnswerEntity a
         where a.session.sessionId = :sessionId
        """)
    int findMaxTurnOrdinal(@Param("sessionId") String sessionId);

    /**
     * 面试轨迹（P4-1）：只取真实发生过的轮次，按发生顺序返回。
     *
     * <p>{@code turnOrdinal} 为空的报告补写行会被排除——「未考察不进入实际轨迹」的落点。
     */
    @Query("""
        select a from InterviewAnswerEntity a
         where a.session.sessionId = :sessionId and a.turnOrdinal is not null
         order by a.turnOrdinal asc
        """)
    List<InterviewAnswerEntity> findTurnsBySessionId(@Param("sessionId") String sessionId);


    /**
     * 按答案状态与分数筛选（P4Q-5 历史修复）：只挑「已标记作答但得 0 分」的候选做语义复核。
     *
     * <p>真正答错的答案也在候选里，但它们会被判为非跳过而保持原状，因此候选集合小且安全。
     */
    List<InterviewAnswerEntity> findByAnswerStateAndScore(
        InterviewAnswerEntity.AnswerState answerState, Integer score);

    /**
     * 非真实作答的答案（跳过 / 明确不会 / 未作答），并连带取出会话用于追溯 sourceId。
     * join fetch 避免逐条懒加载会话（N+1）。
     */
    @Query("select a from InterviewAnswerEntity a join fetch a.session where a.answerState <> :answerState")
    List<InterviewAnswerEntity> findByAnswerStateNot(
        @Param("answerState") InterviewAnswerEntity.AnswerState answerState);
}
