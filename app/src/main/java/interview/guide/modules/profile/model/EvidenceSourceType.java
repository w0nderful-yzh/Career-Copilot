package interview.guide.modules.profile.model;

/**
 * 画像证据来源类型。
 *
 * <p>面试来源（INTERVIEW_TURN）是唯一**可量化的评分证据**，画像分由它聚合得出；
 * 简历来源（RESUME）是**声明型证据**（无分、不参与聚合，见 SkillEvidenceEntity#score）。
 */
public enum EvidenceSourceType {

  /**
   * 简历声明证据：用户确认过的结构化简历里列出的技能条目。
   *
   * <p>无分数——简历侧没有逐技能分可用（详见 V20260915 迁移注释），
   * 故只表达「列过这项技能」，用于画像展示与下一场面试的 focus 选择。
   */
  RESUME,

  /** 面试会话级证据：整场面试总分 */
  INTERVIEW_SESSION,

  /** 面试轮次级证据：单题评分（category 即技能名） */
  INTERVIEW_TURN
}
