package interview.guide.modules.interview.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    Optional<InterviewSessionEntity> findByRequestId(String requestId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);

    /**
     * 推进一轮（作答 / 跳过）的条件更新（P4-9a）。
     *
     * <p>这是逐轮提交的**唯一并发闸门**：只有「版本 = 提交方看到的版本」且「待答题 = 提交的那一题」
     * 且「会话仍在进行中」时才会命中一行。影响 0 行意味着会话已被其他请求推进（或已结束），
     * 调用方必须拒绝本次提交——晚到的模型结果因此无法回退索引，也无法把已结束的会话写回进行中。
     *
     * <p>注意 {@code clearAutomatically}：批量更新绕过持久化上下文，更新后必须重新加载实体，
     * 否则后续读到的还是旧版本号。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
           SET s.turnVersion = s.turnVersion + 1,
               s.currentQuestionId = :newQuestionId,
               s.currentQuestionIndex = :newIndex,
               s.status = :newStatus,
               s.completedAt = :completedAt
         WHERE s.sessionId = :sessionId
           AND s.turnVersion = :expectedVersion
           AND s.currentQuestionId = :expectedQuestionId
           AND s.status IN :activeStatuses
        """)
    int applyTurn(@Param("sessionId") String sessionId,
                  @Param("expectedVersion") int expectedVersion,
                  @Param("expectedQuestionId") String expectedQuestionId,
                  @Param("newQuestionId") String newQuestionId,
                  @Param("newIndex") int newIndex,
                  @Param("newStatus") SessionStatus newStatus,
                  @Param("completedAt") LocalDateTime completedAt,
                  @Param("activeStatuses") List<SessionStatus> activeStatuses);

    /**
     * 推进最后一轮并进入评估的条件更新（P4-9a）。
     *
     * <p>与 {@link #applyTurn} 同一套并发闸门，额外把「评估请求」也放进同一个短事务：
     * 状态置 COMPLETED、评估状态置 PENDING、评估代次 +1。这样就不存在「答完但没入队评估」
     * 或「入队了但状态还是进行中」的半状态——投递 Stream 消息放在事务提交之后。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
           SET s.turnVersion = s.turnVersion + 1,
               s.currentQuestionId = :newQuestionId,
               s.currentQuestionIndex = :newIndex,
               s.status = :completedStatus,
               s.completedAt = :completedAt,
               s.endReason = :endReason,
               s.evaluateStatus = :pending,
               s.evaluateError = NULL,
               s.evaluateEpoch = s.evaluateEpoch + 1
         WHERE s.sessionId = :sessionId
           AND s.turnVersion = :expectedVersion
           AND s.currentQuestionId = :expectedQuestionId
           AND s.status IN :activeStatuses
        """)
    int applyTurnRequestingEvaluation(@Param("sessionId") String sessionId,
                                      @Param("expectedVersion") int expectedVersion,
                                      @Param("expectedQuestionId") String expectedQuestionId,
                                      @Param("newQuestionId") String newQuestionId,
                                      @Param("newIndex") int newIndex,
                                      @Param("completedStatus") SessionStatus completedStatus,
                                      @Param("pending") AsyncTaskStatus pending,
                                      @Param("endReason") String endReason,
                                      @Param("completedAt") LocalDateTime completedAt,
                                      @Param("activeStatuses") List<SessionStatus> activeStatuses);

    /**
     * 用户提前交卷的条件更新（P4-9a）：与逐轮推进共用同一并发边界（版本 + 进行中状态）。
     * 索引不动，只置 COMPLETED 并请求评估。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
           SET s.turnVersion = s.turnVersion + 1,
               s.status = :completedStatus,
               s.completedAt = :completedAt,
               s.endReason = :endReason,
               s.evaluateStatus = :pending,
               s.evaluateError = NULL,
               s.evaluateEpoch = s.evaluateEpoch + 1
         WHERE s.sessionId = :sessionId
           AND s.turnVersion = :expectedVersion
           AND s.status IN :activeStatuses
        """)
    int applyFinish(@Param("sessionId") String sessionId,
                    @Param("expectedVersion") int expectedVersion,
                    @Param("completedStatus") SessionStatus completedStatus,
                    @Param("pending") AsyncTaskStatus pending,
                    @Param("endReason") String endReason,
                    @Param("completedAt") LocalDateTime completedAt,
                    @Param("activeStatuses") List<SessionStatus> activeStatuses);

    /**
     * 原子领取评估任务（P4-9a）。
     *
     * <p>只有「代次 = 消息代次」且「尚未完成」时才能领到。重复投递的同一代次消息里，
     * 只有先领到的那个会真正执行，其余直接跳过——避免同一场面试产出两份报告与两批画像证据。
     * 代次更大的新消息（用户重试）不受影响。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
           SET s.evaluateStatus = :processing, s.evaluateError = NULL
         WHERE s.sessionId = :sessionId
           AND s.evaluateEpoch = :epoch
           AND (s.evaluateStatus IS NULL OR s.evaluateStatus <> :completed)
        """)
    int claimEvaluation(@Param("sessionId") String sessionId,
                        @Param("epoch") long epoch,
                        @Param("processing") AsyncTaskStatus processing,
                        @Param("completed") AsyncTaskStatus completed);

    /**
     * 读取评估代次（消费端把「消息代次 < 当前代次」判为过期触发）
     */
    @Query("SELECT s.evaluateEpoch FROM InterviewSessionEntity s WHERE s.sessionId = :sessionId")
    Optional<Long> findEvaluateEpoch(@Param("sessionId") String sessionId);

    /**
     * 重新请求评估（P4-9a）：置回 PENDING 并把代次 +1。
     *
     * <p>只在已结束（COMPLETED / EVALUATED）的会话上生效——重试入口不该把进行中的面试拉进评估。
     * 代次递增同时让尚未跑完的旧任务过期。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
           SET s.evaluateStatus = :pending, s.evaluateError = NULL,
               s.evaluateEpoch = s.evaluateEpoch + 1
         WHERE s.sessionId = :sessionId
           AND s.status IN :finishedStatuses
        """)
    int requestEvaluation(@Param("sessionId") String sessionId,
                          @Param("pending") AsyncTaskStatus pending,
                          @Param("finishedStatuses") List<SessionStatus> finishedStatuses);
}
