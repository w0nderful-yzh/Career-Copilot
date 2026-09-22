package interview.guide.modules.profile.service;

import interview.guide.modules.resume.model.ResumeContentJson;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 简历 → 画像的同步入口（P3 待收口）。
 *
 * <p>简历模块只需调这一个方法，不必了解技能名归一化与声明语义的细节。
 *
 * <p>同步时机刻意选在**用户确认结构化简历**之后：解析结果未经确认时不算权威，
 * 把未确认的解析写进画像会出现「用户还没看过就被画像引用」的问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProfileSyncService {

  private final ResumeEvidenceExtractor extractor;
  private final SkillProfileAggregator aggregator;

  /**
   * 同步某份简历的技能声明（整体替换）。
   *
   * <p>失败不向上抛：画像同步属于旁路增强，不能因为画像问题让「确认简历」这一步失败。
   */
  public void syncDeclarations(Long resumeId, ResumeContentJson content) {
    if (resumeId == null) {
      return;
    }
    try {
      aggregator.replaceResumeDeclarations(
          resumeId, extractor.extract(resumeId, content, LocalDateTime.now()));
    } catch (Exception e) {
      log.error("简历技能声明同步失败（不影响简历确认）: resumeId={}, error={}",
          resumeId, e.getMessage(), e);
    }
  }

  /** 简历删除时清理其技能声明证据 */
  public void removeDeclarations(Long resumeId) {
    if (resumeId == null) {
      return;
    }
    try {
      aggregator.removeResumeEvidence(resumeId);
    } catch (Exception e) {
      log.error("简历技能声明清理失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
    }
  }
}
