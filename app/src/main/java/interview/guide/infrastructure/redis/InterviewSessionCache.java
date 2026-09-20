package interview.guide.infrastructure.redis;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话 Redis 缓存服务
 * 管理面试会话在 Redis 中的存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionCache {

    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    /**
     * 缓存键前缀
     */
    private static final String SESSION_KEY_PREFIX = "interview:session:";

    /**
     * 简历ID到会话ID的映射前缀（用于查找未完成会话）
     */
    private static final String RESUME_SESSION_KEY_PREFIX = "interview:resume:";

    /**
     * 会话默认过期时间（24小时）
     */
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /**
     * 缓存的会话数据
     */
    @Data
    public static class CachedSession implements Serializable {
        private String sessionId;
        private String resumeText;
        private Long resumeId;
        private Long knowledgeBaseId;
        private String interviewCategory;
        private String questionsJson;  // 序列化的候选素材（P4-1：只读素材，不再回写答案）
        /**
         * 序列化的实际轨迹（P4-1）：作答 / 跳过 / 明确不会，按发生顺序。
         *
         * <p>与候选素材分开存储：把答案写在素材里，会让「候选择问」与「真实轮次」再也分不开。
         */
        private String turnsJson;
        private int currentIndex;
        /** 当前待答题的稳定标识（P4-1）：定位与提交校验都基于它 */
        private String currentQuestionId;
        /** 预计时长（分钟，P4Q-2）：计划未记录时为 null（旧会话） */
        private Integer plannedDurationMinutes;
        /** 必要覆盖话题（JSON 数组，P4Q-2） */
        private String requiredTopicsJson;
        /** 提案重点分类（JSON 数组，P5-2）：让「为什么这场重点考了这些」可审计 */
        private String focusCategoriesJson;
        /** 用户答题累计耗时（秒，P4Q-2）：不含模型等待 */
        private Integer consumedSeconds = 0;
        private SessionStatus status;
        private Boolean adaptive = false;  // P4-3 是否自适应（逐题评估+决策选题）
        // P4Q-1 简历上下文的来源与版本（出题实际依据；供 DTO 展示与追溯，null = 未记录）
        private String resumeSource;
        private Integer resumeVersion;
        /**
         * 会话推进版本（P4-9a）：与数据库 interview_sessions.turn_version 对应。
         *
         * <p>缓存里的它是一个**副本**，权威值在数据库——写缓存永远发生在数据库提交之后，
         * 且提交被拒绝时服务端会用数据库的实体重建缓存。因此这里读到旧值时不会被当成事实
         * 写回数据库，只会让客户端拿到「刷新后重试」的可见原因。
         */
        private Integer turnVersion = 0;

        public CachedSession() {
        }

        public CachedSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                            String interviewCategory,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            SessionStatus status, Boolean adaptive, ObjectMapper objectMapper) {
            this(sessionId, resumeText, resumeId, knowledgeBaseId, interviewCategory, questions,
                currentIndex, status, adaptive, null, null, objectMapper);
        }

        public CachedSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                            String interviewCategory,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            SessionStatus status, Boolean adaptive,
                            String resumeSource, Integer resumeVersion, ObjectMapper objectMapper) {
            this.sessionId = sessionId;
            this.resumeText = resumeText;
            this.resumeId = resumeId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.interviewCategory = interviewCategory;
            this.currentIndex = currentIndex;
            this.status = status;
            this.adaptive = adaptive != null ? adaptive : false;
            this.resumeSource = resumeSource;
            this.resumeVersion = resumeVersion;
            this.currentQuestionId = (status == SessionStatus.CREATED || status == SessionStatus.IN_PROGRESS)
                && questions != null && currentIndex >= 0 && currentIndex < questions.size()
                ? questions.get(currentIndex).questionId()
                : null;
            try {
                this.questionsJson = objectMapper.writeValueAsString(questions);
                // 新会话的轨迹是确定的空快照，不需要首次读取时再回源数据库。
                this.turnsJson = objectMapper.writeValueAsString(List.of());
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e);
            }
        }

        public List<InterviewQuestionDTO> getQuestions(ObjectMapper objectMapper) {
            try {
                return objectMapper.readValue(questionsJson, new TypeReference<>() {});
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化问题列表失败");
            }
        }

        /**
         * 轨迹快照是否已写过（P4-1）。
         *
         * <p>与「轨迹为空」是两件事：新会话没问过任何题时快照就是 {@code []}，
         * 只有从未写过（旧缓存 / 刚创建的会话）才需要回源数据库。
         */
        public boolean hasTurnsSnapshot() {
            return turnsJson != null;
        }

        /** 实际轨迹（P4-1）：缓存里没有时返回空列表，由调用方回源数据库 */
        public List<InterviewTurnDTO> getTurns(ObjectMapper objectMapper) {
            if (turnsJson == null || turnsJson.isBlank()) {
                return List.of();
            }
            try {
                return objectMapper.readValue(turnsJson, new TypeReference<>() {});
            } catch (JacksonException e) {
                // 轨迹解析失败不该让整个会话读不出来：回源数据库即可（读取侧自愈）
                log.warn("反序列化会话轨迹失败，回源数据库重建: sessionId={}", sessionId, e);
                turnsJson = null;
                return List.of();
            }
        }
    }

    /**
     * 保存会话到缓存
     */
    public void saveSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                           String interviewCategory,
                           List<InterviewQuestionDTO> questions, int currentIndex,
                           SessionStatus status) {
        saveSession(sessionId, resumeText, resumeId, knowledgeBaseId, interviewCategory,
            questions, currentIndex, status, false);
    }

    /** P4-3：带 adaptive 标记保存（自适应会话逐题评估+决策选题） */
    public void saveSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                           String interviewCategory,
                           List<InterviewQuestionDTO> questions, int currentIndex,
                           SessionStatus status, Boolean adaptive) {
        saveSession(sessionId, resumeText, resumeId, knowledgeBaseId, interviewCategory,
            questions, currentIndex, status, adaptive, null, null);
    }

    /** P4Q-1：带简历上下文来源与版本的保存（普通面试创建链路） */
    public void saveSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                           String interviewCategory,
                           List<InterviewQuestionDTO> questions, int currentIndex,
                           SessionStatus status, Boolean adaptive,
                           String resumeSource, Integer resumeVersion) {
        String key = buildSessionKey(sessionId);
        CachedSession cachedSession = new CachedSession(
            sessionId, resumeText, resumeId, knowledgeBaseId, interviewCategory,
            questions, currentIndex, status, adaptive, resumeSource, resumeVersion, objectMapper
        );

        redisService.set(key, cachedSession, SESSION_TTL);

        // 如果有 resumeId，建立映射关系（用于查找未完成会话）
        if (resumeId != null && isUnfinishedStatus(status)) {
            saveResumeSessionMapping(resumeId, sessionId);
        }

        log.debug("会话已缓存: sessionId={}, resumeId={}, kbId={}, status={}",
            sessionId, resumeId, knowledgeBaseId, status);
    }

    /**
     * 获取缓存的会话
     */
    public Optional<CachedSession> getSession(String sessionId) {
        String key = buildSessionKey(sessionId);
        CachedSession session = redisService.get(key);
        if (session != null) {
            log.debug("从缓存获取会话: sessionId={}", sessionId);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    /**
     * 更新会话状态
     */
    public void updateSessionStatus(String sessionId, SessionStatus status) {
        getSession(sessionId).ifPresent(session -> {
            session.setStatus(status);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);

            // 如果会话已完成，移除映射
            if (!isUnfinishedStatus(status) && session.getResumeId() != null) {
                removeResumeSessionMapping(session.getResumeId(), sessionId);
            }

            log.debug("更新会话状态: sessionId={}, status={}", sessionId, status);
        });
    }

    /** P4-3：更新 adaptive 标记（缓存恢复 DB 后落缓存） */
    public void updateAdaptive(String sessionId, boolean adaptive) {
        getSession(sessionId).ifPresent(session -> {
            session.setAdaptive(adaptive);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
        });
    }

    /**
     * 更新当前问题索引
     */
    public void updateCurrentIndex(String sessionId, int currentIndex) {
        getSession(sessionId).ifPresent(session -> {
            session.setCurrentIndex(currentIndex);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
            log.debug("更新会话进度: sessionId={}, currentIndex={}", sessionId, currentIndex);
        });
    }

    /**
     * 写入面试计划（P4Q-2）：创建链路在 saveSession 之后调用一次。
     *
     * <p>计划属于会话状态的一部分：顶栏展示与决策收束都要读它，
     * 不放进缓存的话每次读取都得回源数据库。
     */
    public void applyPlan(String sessionId, InterviewPlan plan) {
        getSession(sessionId).ifPresent(session -> {
            session.setPlannedDurationMinutes(plan.plannedDurationMinutes());
            try {
                session.setRequiredTopicsJson(
                    objectMapper.writeValueAsString(plan.requiredTopics()));
                session.setFocusCategoriesJson(
                    objectMapper.writeValueAsString(plan.focusCategories()));
            } catch (JacksonException e) {
                log.warn("序列化计划快照失败，计划按无覆盖处理: sessionId={}", sessionId, e);
            }
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
        });
    }

    /** 更新用户调整后的时间预算（P4Q-2）：planned = 已用 + 用户声明的剩余 */
    public void applyBudgetOverride(String sessionId, int plannedMinutes, int consumedSeconds) {
        getSession(sessionId).ifPresent(session -> {
            session.setPlannedDurationMinutes(plannedMinutes);
            session.setConsumedSeconds(consumedSeconds);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
        });
    }

    /**
     * 一次性写回「本轮之后」的会话视图（P4-1）。
     *
     * <p>候选素材、实际轨迹、当前题标识 / 序号、状态、版本本来分五次写会各打一次 Redis，
     * 且中间态（轨迹更新了但当前题没动）会误导下一次读取；这里合成一次写。
     */
    public void applyTurnState(String sessionId, List<InterviewQuestionDTO> candidates,
                               List<InterviewTurnDTO> turns, int currentIndex,
                               String currentQuestionId, SessionStatus status, Integer turnVersion,
                               Integer consumedSeconds) {
        getSession(sessionId).ifPresent(session -> {
            try {
                session.setQuestionsJson(objectMapper.writeValueAsString(candidates));
                session.setTurnsJson(objectMapper.writeValueAsString(turns));
                session.setCurrentIndex(currentIndex);
                session.setCurrentQuestionId(currentQuestionId);
                session.setStatus(status);
                session.setTurnVersion(turnVersion != null ? turnVersion : 0);
                if (consumedSeconds != null) {
                    session.setConsumedSeconds(consumedSeconds);
                }
                String key = buildSessionKey(sessionId);
                redisService.set(key, session, SESSION_TTL);
                if (!isUnfinishedStatus(status) && session.getResumeId() != null) {
                    removeResumeSessionMapping(session.getResumeId(), sessionId);
                }
            } catch (JacksonException e) {
                log.error("序列化会话轨迹失败：读取侧会回源数据库重建: sessionId={}", sessionId, e);
            }
        });
    }

    /** 更新当前题标识（P4-1）：旧数据或外部写入路径用 */
    public void updateCurrentQuestionId(String sessionId, String currentQuestionId) {
        getSession(sessionId).ifPresent(session -> {
            session.setCurrentQuestionId(currentQuestionId);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
        });
    }

    /** 更新推进版本（P4-9a）。
     *
     * <p>单独一个方法而不是塞进 saveSession 的参数表：saveSession 已有十几个位置参数，
     * 再加一个只会让调用点更难读；版本只在「提交完成后」和「从数据库重建后」两个时机更新。
     */
    public void updateTurnVersion(String sessionId, Integer turnVersion) {
        getSession(sessionId).ifPresent(session -> {
            session.setTurnVersion(turnVersion != null ? turnVersion : 0);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
        });
    }

    /**
     * 更新问题列表（用于保存答案）
     */
    public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
        getSession(sessionId).ifPresent(session -> {
            try {
                session.setQuestionsJson(objectMapper.writeValueAsString(questions));
                String key = buildSessionKey(sessionId);
                redisService.set(key, session, SESSION_TTL);
                log.debug("更新会话问题: sessionId={}", sessionId);
            } catch (JacksonException e) {
                log.error("序列化问题列表失败", e);
            }
        });
    }

    /**
     * 删除会话缓存
     */
    public void deleteSession(String sessionId) {
        getSession(sessionId).ifPresent(session -> {
            if (session.getResumeId() != null) {
                removeResumeSessionMapping(session.getResumeId(), sessionId);
            }
        });

        String key = buildSessionKey(sessionId);
        redisService.delete(key);
        log.debug("删除会话缓存: sessionId={}", sessionId);
    }

    /**
     * 根据简历ID查找未完成的会话ID
     */
    public Optional<String> findUnfinishedSessionId(Long resumeId) {
        String key = buildResumeSessionKey(resumeId);
        String sessionId = redisService.get(key);
        if (sessionId != null) {
            // 验证会话是否仍然存在且未完成
            Optional<CachedSession> sessionOpt = getSession(sessionId);
            if (sessionOpt.isPresent() && isUnfinishedStatus(sessionOpt.get().getStatus())) {
                return Optional.of(sessionId);
            } else {
                // 会话已不存在或已完成，清理映射
                redisService.delete(key);
            }
        }
        return Optional.empty();
    }

    /**
     * 刷新会话过期时间
     */
    public void refreshSessionTTL(String sessionId) {
        String key = buildSessionKey(sessionId);
        redisService.expire(key, SESSION_TTL);
    }

    /**
     * 检查会话是否在缓存中
     */
    public boolean exists(String sessionId) {
        String key = buildSessionKey(sessionId);
        return redisService.exists(key);
    }

    // ==================== 私有方法 ====================

    private String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String buildResumeSessionKey(Long resumeId) {
        return RESUME_SESSION_KEY_PREFIX + resumeId;
    }

    private void saveResumeSessionMapping(Long resumeId, String sessionId) {
        String key = buildResumeSessionKey(resumeId);
        redisService.set(key, sessionId, SESSION_TTL);
    }

    private void removeResumeSessionMapping(Long resumeId, String sessionId) {
        String key = buildResumeSessionKey(resumeId);
        String currentSessionId = redisService.get(key);
        // 只有当前映射的是这个 sessionId 时才删除
        if (sessionId.equals(currentSessionId)) {
            redisService.delete(key);
        }
    }

    private boolean isUnfinishedStatus(SessionStatus status) {
        return status == SessionStatus.CREATED || status == SessionStatus.IN_PROGRESS;
    }
}
