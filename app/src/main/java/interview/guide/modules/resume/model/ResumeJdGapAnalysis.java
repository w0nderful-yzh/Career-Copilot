package interview.guide.modules.resume.model;

import java.util.List;

/**
 * JD 差距分析快照。
 *
 * <p>与 Patch 分开持久化：Gap 说明岗位要求与简历证据的差距，
 * Patch 只表达能在现有事实基础上安全应用的改写。
 */
public record ResumeJdGapAnalysis(
    String jobTitle,
    MatchLevel matchLevel,
    String summary,
    List<GapItem> items
) {

  public enum MatchLevel {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
  }

  public enum GapStatus {
    MATCHED,
    PARTIAL,
    MISSING,
    UNKNOWN
  }

  public record GapItem(
      String requirement,
      GapStatus status,
      List<String> resumeEvidence,
      String impact,
      List<String> verificationRequired
  ) {}
}
