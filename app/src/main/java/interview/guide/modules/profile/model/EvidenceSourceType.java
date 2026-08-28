package interview.guide.modules.profile.model;

/**
 * 画像证据来源类型。
 *
 * <p>一期只有面试证据有真实数据（interview_answers 逐题分 / interview_sessions 总分）；
 * RESUME 预留给 P2-0 简历结构化解析后的逐技能条目。
 */
public enum EvidenceSourceType {

  /** 简历证据（P2-0 ResumeParse 产出逐技能条目后接入） */
  RESUME,

  /** 面试会话级证据：整场面试总分 */
  INTERVIEW_SESSION,

  /** 面试轮次级证据：单题评分（category 即技能名） */
  INTERVIEW_TURN
}
