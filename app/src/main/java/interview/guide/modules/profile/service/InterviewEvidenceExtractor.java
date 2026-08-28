package interview.guide.modules.profile.service;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试证据提取服务：把已落库的面试评分转成画像 Evidence。
 *
 * <p>一期只有 INTERVIEW_TURN（逐题分）真实产出：category 即技能名，score 即该技能
 * 一次可追溯的评分。会话总分（INTERVIEW_SESSION）是逐题分的均值，属于冗余证据，
 * 暂不写入；RESUME 证据待 P2-0 简历结构化解析后接入。
 *
 * <p>只取真实作答的题目：未作答题得 0 分是"未考"而非"不会"，计入会拉低画像。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewEvidenceExtractor {

  private final InterviewSessionRepository sessionRepository;

  /**
   * 从面试会话提取证据列表。
   *
   * @param sessionId 面试会话 ID（业务 sessionId 字符串）
   * @return 证据列表；会话不存在或无可计分答案时为空
   */
  @Transactional(readOnly = true)
  public List<SkillEvidenceEntity> extract(String sessionId) {
    Optional<InterviewSessionEntity> sessionOpt =
        sessionRepository.findBySessionId(sessionId);
    if (sessionOpt.isEmpty()) {
      log.warn("提取证据时面试会话不存在: sessionId={}", sessionId);
      return List.of();
    }

    InterviewSessionEntity session = sessionOpt.get();
    List<SkillEvidenceEntity> evidences = new ArrayList<>();
    for (InterviewAnswerEntity answer : session.getAnswers()) {
      // 只取真实作答且有评分的答案；score 为 null 表示评估未覆盖该题
      if (answer.getUserAnswer() == null || answer.getUserAnswer().isBlank()
          || answer.getScore() == null) {
        continue;
      }
      String skill = normalizeSkill(answer.getCategory());
      if (skill == null) {
        continue;
      }
      evidences.add(new SkillEvidenceEntity(
          ProfileConstants.DEFAULT_USER_ID,
          skill,
          EvidenceSourceType.INTERVIEW_TURN,
          sessionId + ":" + answer.getQuestionIndex(),
          answer.getScore(),
          session.getCompletedAt() != null ? session.getCompletedAt() : LocalDateTime.now()));
    }
    log.info("面试证据已提取: sessionId={}, 证据数={}", sessionId, evidences.size());
    return evidences;
  }

  /** category 为空时归入未知技能并丢弃（无技能归属的证据不参与聚合） */
  private String normalizeSkill(String category) {
    if (category == null || category.isBlank()) {
      return null;
    }
    return category.trim();
  }
}
