package interview.guide.modules.profile.service;

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

    List<SkillImpactDTO> skills = bySkill.entrySet().stream()
        .map(entry -> toImpact(entry.getKey(), entry.getValue(), prefix))
        // 稳定返回顺序：展示排序（如按变化幅度）交给前端，后端不做二次排序
        .sorted(Comparator.comparing(SkillImpactDTO::skill))
        .toList();
    return new SkillProfileImpactResponse(sessionId, skills);
  }

  private SkillImpactDTO toImpact(String skill, List<SkillEvidenceEntity> sessionEvidences,
                                  String sessionPrefix) {
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
        .map(SkillProfileImpactService::toSessionEvidence)
        .toList();
    return new SkillImpactDTO(skill, beforeScore, afterScore, delta, details);
  }

  private static int average(List<SkillEvidenceEntity> evidences) {
    return (int) Math.round(
        evidences.stream().mapToInt(SkillEvidenceEntity::getScore).average().orElse(0));
  }

  /** sourceId 形如 "sessionId:questionIndex"；题号解析失败不影响其余信息 */
  private static SessionEvidenceDTO toSessionEvidence(SkillEvidenceEntity evidence) {
    String sourceId = evidence.getSourceId();
    Integer questionIndex = null;
    int separator = sourceId == null ? -1 : sourceId.lastIndexOf(':');
    if (separator > 0 && separator < sourceId.length() - 1) {
      try {
        questionIndex = Integer.valueOf(sourceId.substring(separator + 1));
      } catch (NumberFormatException ignored) {
        // 非数字后缀：保留 sourceId 供追溯，题号留空
      }
    }
    return new SessionEvidenceDTO(
        sourceId, questionIndex, evidence.getScore(), evidence.getOccurredAt());
  }
}
