package interview.guide.modules.profile.service;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.profile.dto.SkillProfileImpactResponse;
import interview.guide.modules.profile.dto.SkillProfileImpactResponse.SessionEvidenceDTO;
import interview.guide.modules.profile.dto.SkillProfileImpactResponse.SkillImpactDTO;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一场面试对画像的影响（P3 待收口）。
 *
 * <p>只读派生：不新增任何存储，直接用 skill_evidence 重算——证据表已经带了
 * 来源（sourceId = "sessionId:questionIndex"）、分数与时间，差分与追溯都能从它还原。
 *
 * <p>差分口径：某技能
 * <ul>
 *   <li>{@code after}  = 该技能全部**有分**证据的均值（与聚合器同一口径）；
 *   <li>{@code before} = 排除本场证据后的均值，none 时为 null（本场首次考到）。
 * </ul>
 * 声明型证据（简历，无分）不参与——它本来就不影响分数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillProfileImpactService {

  private static final String DEFAULT_USER_ID = ProfileConstants.DEFAULT_USER_ID;

  private final SkillEvidenceRepository evidenceRepository;
  /** 来源键 → 真实发生顺序（展示「第 N 题」）：证据里只有标识，序号在轮次行上 */
  private final InterviewAnswerRepository answerRepository;

  /**
   * 计算某场面试带来的画像变化。
   *
   * @param sessionId 面试会话 ID
   * @return 本场考到的技能及其前后分与逐条证据；该场无可计分证据时返回空列表
   */
  @Transactional(readOnly = true)
  public SkillProfileImpactResponse impactOf(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return new SkillProfileImpactResponse(sessionId, List.of());
    }
    String prefix = sessionId + ":";
    List<SkillEvidenceEntity> sessionEvidences = evidenceRepository
        .findBySourceTypeAndSourceIdStartingWith(EvidenceSourceType.INTERVIEW_TURN, prefix);
    if (sessionEvidences.isEmpty()) {
      log.info("本场没有可计分的画像证据: sessionId={}", sessionId);
      return new SkillProfileImpactResponse(sessionId, List.of());
    }

    Map<String, List<SkillEvidenceEntity>> bySkill = sessionEvidences.stream()
        .collect(Collectors.groupingBy(SkillEvidenceEntity::getSkill));
    // 一次取回本场轨迹，避免每条证据都单独查答案行（N+1）。
    Map<String, Integer> ordinalByQuestionId = answerRepository.findTurnsBySessionId(sessionId).stream()
        .filter(answer -> answer.getQuestionId() != null)
        .collect(Collectors.toMap(
            InterviewAnswerEntity::getQuestionId,
            InterviewAnswerEntity::getTurnOrdinal,
            (first, ignored) -> first));

    List<SkillImpactDTO> skills = bySkill.entrySet().stream()
        .map(entry -> toImpact(entry.getKey(), entry.getValue(), prefix, ordinalByQuestionId))
        // 稳定返回顺序：展示排序（如按变化幅度）交给前端，后端不做二次排序
        .sorted(Comparator.comparing(SkillImpactDTO::skill))
        .toList();
    return new SkillProfileImpactResponse(sessionId, skills);
  }

  private SkillImpactDTO toImpact(String skill, List<SkillEvidenceEntity> sessionEvidences,
                                  String sessionPrefix,
                                  Map<String, Integer> ordinalByQuestionId) {
    List<SkillEvidenceEntity> scored = evidenceRepository
        .findByUserIdAndSkill(DEFAULT_USER_ID, skill).stream()
        .filter(evidence -> evidence.getScore() != null)
        .toList();

    List<SkillEvidenceEntity> previous = scored.stream()
        .filter(evidence -> !evidence.getSourceId().startsWith(sessionPrefix))
        .toList();

    int afterScore = average(scored);
    Integer beforeScore = previous.isEmpty() ? null : average(previous);
    // 首次考到时不报一个虚高的"涨幅"：delta 归零，由前端渲染成「本场新增」
    int delta = beforeScore == null ? 0 : afterScore - beforeScore;

    List<SessionEvidenceDTO> details = sessionEvidences.stream()
        .sorted(Comparator.comparing(SkillEvidenceEntity::getOccurredAt))
        .map(evidence -> toSessionEvidence(evidence, ordinalByQuestionId))
        .toList();
    return new SkillImpactDTO(skill, beforeScore, afterScore, delta, details);
  }

  private static int average(List<SkillEvidenceEntity> evidences) {
    return (int) Math.round(
        evidences.stream().mapToInt(SkillEvidenceEntity::getScore).average().orElse(0));
  }

  /**
   * sourceId 形如 {@code "sessionId:questionKey"}（P4-1 起 key 是题目稳定标识；
   * 旧数据是数字下标或 migration 回填的 {@code legacy-<下标>}）。
   *
   * <p>展示序号取轮次行的**真实发生顺序**（1 起），因此顺序调整或候选池重排都不会让
   * 「第 N 题」指错；解析不出来时留空，前端退化为「场次记录」而不是显示一个假序号。
   */
  private static SessionEvidenceDTO toSessionEvidence(
      SkillEvidenceEntity evidence, Map<String, Integer> ordinalByQuestionId) {
    String sourceId = evidence.getSourceId();
    String questionKey = null;
    Integer questionOrdinal = null;
    int separator = sourceId == null ? -1 : sourceId.lastIndexOf(':');
    if (separator > 0 && separator < sourceId.length() - 1) {
      questionKey = sourceId.substring(separator + 1);
      questionOrdinal = ordinalByQuestionId.get(questionKey);
      if (questionOrdinal == null && questionKey.chars().allMatch(Character::isDigit)) {
        // P4-1 之前 evidence.sourceId 的后缀是裸下标；答案迁移后对应 legacy-<下标>。
        questionOrdinal = ordinalByQuestionId.get(
            InterviewQuestionDTO.LEGACY_ID_PREFIX + questionKey);
      }
    }
    return new SessionEvidenceDTO(
        sourceId, questionKey, questionOrdinal, evidence.getScore(), evidence.getOccurredAt());
  }
}
