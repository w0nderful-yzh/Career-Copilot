package interview.guide.modules.interview.model;

/**
 * 面试的简历上下文（P4Q-1）：出题实际依据的**来源、版本与文本快照**。
 *
 * <p>为什么要落这个快照：此前的简历来源是「调用方传什么就用什么」——Java 只认调用方传进来的
 * 原文文本，而 Agent 侧从不传（硬编码 null），于是 **Agent 发起的面试简历题分支从未生效**，
 * 简历只在提案推荐那一步被读过一次。现在由 Java 统一解析来源，并把「这场面试用了哪份简历的
 * 哪个版本、出题时看的是哪份文本」随会话存下来，报告与复盘才能追溯依据。
 */
public record InterviewResumeContext(
    Source source,
    Long resumeId,
    Integer version,
    String text
) {

  /** 简历来源类型 */
  public enum Source {
    /** 已确认的结构化版本（简历库 ACTIVE 版本，或调用方明确指定的版本） */
    RESUME_VERSION,
    /** 简历原文（没有可用结构化版本时的降级来源） */
    RESUME_TEXT,
    /** 调用方直接传入的文本（手动面试 / 知识库面试等没有简历 ID 的场景） */
    EXPLICIT_TEXT,
    /** 通用面试：没有任何简历依据 */
    NONE
  }

  /** 无简历依据（通用面试） */
  public static InterviewResumeContext none() {
    return new InterviewResumeContext(Source.NONE, null, null, null);
  }

  /** 调用方显式传入的文本（无简历 ID 的通道） */
  public static InterviewResumeContext explicitText(String text) {
    return new InterviewResumeContext(Source.EXPLICIT_TEXT, null, null, text);
  }

  /** 出题是否可能产出简历相关问题（出题的 hasResume 判据与之保持一致） */
  public boolean hasText() {
    return text != null && !text.isBlank();
  }
}
