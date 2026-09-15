package interview.guide.modules.profile.service;

import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.resume.model.ResumeContentJson;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 简历来源证据提取（P3 待收口）：把结构化简历的技能条目转成**声明型**证据。
 *
 * <p>为什么是声明而非评分：简历侧没有逐技能分——结构化解析的 skills 只有
 * category/content（技能名，无分），简历分析只有四个维度分，都不是技能分。
 * 给未验证的技能编一个分数会破坏「画像分能由证据逐条还原」，因此这里产出
 * {@code score = null} 的声明证据，只表达「简历里列过这项技能」。
 *
 * <p>产物不参与聚合，价值在两处：画像能显示「简历已列 · 待验证」；
 * 面试提案据此把**从没考过**的技能选进 focus（信息量最大的一类）。
 */
@Slf4j
@Service
public class ResumeEvidenceExtractor {

  /** 单个简历最多产出多少条技能声明（防止一份超长技能罗列把画像淹掉） */
  static final int MAX_DECLARATIONS = 40;

  /**
   * 从结构化简历内容提取技能声明证据。
   *
   * @param resumeId    简历 ID（作为 RESUME 证据的 sourceId）
   * @param content     结构化简历内容；为 null 或没有技能条目时返回空
   * @param declaredAt  声明时间（取用户确认该版本的时刻）
   * @return 声明证据列表（score 均为 null）
   */
  public List<SkillEvidenceEntity> extract(Long resumeId, ResumeContentJson content,
                                           LocalDateTime declaredAt) {
    if (resumeId == null || content == null || content.skills() == null) {
      return List.of();
    }
    List<String> contents = content.skills().stream()
        .map(ResumeContentJson.SkillItem::content)
        .filter(item -> item != null && !item.isBlank())
        .toList();
    List<String> skills = ResumeSkillNormalizer.normalizeAll(contents, MAX_DECLARATIONS);
    if (skills.isEmpty()) {
      log.info("结构化简历未解析出可用技能名，跳过声明写入: resumeId={}", resumeId);
      return List.of();
    }

    String sourceId = String.valueOf(resumeId);
    List<SkillEvidenceEntity> declarations = new ArrayList<>(skills.size());
    for (String skill : skills) {
      SkillEvidenceEntity evidence = new SkillEvidenceEntity(
          ProfileConstants.DEFAULT_USER_ID, skill, EvidenceSourceType.RESUME, sourceId, null,
          declaredAt);
      declarations.add(evidence);
    }
    log.info("简历技能声明已提取: resumeId={}, 技能数={}", resumeId, declarations.size());
    return declarations;
  }
}
