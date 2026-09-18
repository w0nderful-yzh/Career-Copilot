// 面试相关类型定义

import type { CategoryDTO } from '../api/skill';

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  /**
   * 候选素材总数（含**候选择问**）。
   *
   * 注意：**不要**拿它当进度分母——自适应会话会跳过部分候选追问，用它做分母会让总进度虚高。
   * 进度请用 turns 的实际已答数 + 主问题数推导。
   */
  totalQuestions: number;
  /** 候选池内顺序（展示/历史兼容用）；判断路径请用 currentQuestionId */
  currentQuestionIndex: number;
  /** 当前待答题的稳定标识（P4-1）：提交与跳过都传它 */
  currentQuestionId?: string | null;
  /** 当前待答题：服务端按标识定位好，前端不必再按下标推 */
  currentQuestion?: InterviewQuestion | null;
  /** 候选素材（只读）：**可以问什么**，未问过的候选择问也在里面 */
  candidates: InterviewQuestion[];
  /** 实际轨迹：**实际发生了什么**（作答/跳过/明确不会，按发生顺序） */
  turns: InterviewTurn[];
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'EVALUATED';
  knowledgeBaseId?: number | null;
  interviewCategory?: string | null;
  /** 自适应会话（P4-3）：逐题决策、会跳过候选追问、提前结束 */
  adaptive?: boolean;
  /**
   * 报告异步任务状态（P4Q-4）：null/undefined = 尚未进入评估。
   *
   * status 只会是 COMPLETED 或 EVALUATED，**评估中与评估失败在 status 上无法区分**，
   * 必须靠 evaluateStatus 才能识别失败并给出重试入口。
   */
  evaluateStatus?: AsyncTaskStatus | null;
  /** 评估失败原因（evaluateStatus = FAILED 时展示） */
  evaluateError?: string | null;
  /**
   * 会话推进版本（P4-9a）：每次作答 / 跳过 / 结束 +1。
   *
   * 提交下一轮时把它作为 expectedVersion 回传；服务端据此拒绝过期请求。
   */
  turnVersion?: number | null;
  /** 结束原因（P4-1/P4Q-2）：CANDIDATES_EXHAUSTED / USER_FINISHED / COVERAGE_SATISFIED / BUDGET_EXHAUSTED */
  endReason?: string | null;
  /** 预计时长（分钟，P4Q-2）；null = 旧会话未记录计划（不显示假预算） */
  plannedDurationMinutes?: number | null;
  /** 用户答题累计耗时（秒，P4Q-2）：不含模型等待与暂停 */
  consumedSeconds?: number;
  /** 剩余预算（秒，P4Q-2）；null = 无计划 */
  remainingSeconds?: number | null;
  /** 必要覆盖话题（P4Q-2）；空 = 未声明 */
  requiredTopics?: string[];
}

/** 实际发生的一轮（P4-1）：候选素材之外唯一可信的面试轨迹 */
export interface InterviewTurn {
  /** 题目稳定标识（候选池内唯一） */
  questionId?: string | null;
  /** 真实发生顺序（1 起）；报告补写的未考察项为 null */
  ordinal?: number | null;
  questionIndex?: number | null;
  question?: string | null;
  /** 考察技能名；话题类轮次为 null */
  category?: string | null;
  /** 交流话题，如「项目经历」 */
  topic?: string | null;
  userAnswer?: string | null;
  answerState?: 'ANSWERED' | 'SKIPPED' | 'DECLINED' | 'UNANSWERED' | null;
  score?: number | null;
  feedback?: string | null;
  /** 本轮最终决定：FOLLOW_UP / NEXT_MAIN / FINISH_EXHAUSTED / FINISH_USER */
  decidedAction?: string | null;
  /** Java 最终选择的下一题；收束时为空 */
  decidedNextQuestionId?: string | null;
  /** Java 最终决定依据 */
  decisionReason?: string | null;
  /** 实际展示的简短承接语 */
  transitionMessage?: string | null;
  referenceAnswer?: string | null;
  keyPoints?: string[] | null;
  occurredAt?: string | null;
}

export type AsyncTaskStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface InterviewQuestion {
  /** 题目稳定标识（P4-1）：排序/合并不改变它 */
  questionId: string;
  /** 候选池内顺序；仅展示与历史兼容用 */
  questionIndex: number;
  question: string;
  type: string;
  /** 考察技能名；话题类候选为 null */
  category: string | null;
  /** 交流话题，如「项目经历」；与考察技能分开表达（P4-1） */
  topic?: string | null;
  topicSummary?: string | null;
  isFollowUp?: boolean;
  /** 追问所属主问题的稳定标识 */
  parentQuestionId?: string | null;
  /** 追问序号（P4Q-6）：同一主问题下的第几条追问，主问题为 null */
  followUpIndex?: number | null;
  difficulty?: number | null;
  followUpType?: string | null;
  expectedPoints?: string[] | null;
  referenceAnswer?: string | null;
  keyPoints?: string[];
  scoringRubric?: string | null;
  sourceContext?: string | null;
}

// ===== 本场面试的画像变化（P3 待收口） =====

/**
 * 本场贡献的一条证据。
 *
 * sourceId 形如 "sessionId:questionKey"（P4-1 起 key 是题目稳定标识）；
 * questionOrdinal 是**真实发生顺序**（1 起），解析不到时为 null。
 */
export interface ImpactEvidence {
  sourceId: string;
  questionKey?: string | null;
  questionOrdinal: number | null;
  score: number;
  occurredAt?: string | null;
}

/** 单个技能的前后分与差值 */
export interface SkillImpact {
  skill: string;
  /** null = 该技能本场首次被考到（此前无任何有分证据） */
  beforeScore: number | null;
  afterScore: number;
  /** 0 表示无变化；beforeScore 为 null（首次考到）时也是 0，由前端渲染成「新增」 */
  delta: number;
  sessionEvidences: ImpactEvidence[];
}

export interface ProfileImpact {
  sessionId: string;
  skills: SkillImpact[];
}

export interface CreateInterviewRequest {
  resumeText: string;
  plannedDurationMinutes?: number;
  requiredTopics?: string[];
  /** 旧入口兼容；新 Copilot 配置不再传固定题数 */
  questionCount?: number;
  resumeId?: number;
  forceCreate?: boolean;
  llmProvider?: string;
  skillId: string;
  difficulty?: string;
  customCategories?: CategoryDTO[];
  jdText?: string;
  requestId?: string;
}

export interface SubmitAnswerRequest {
  sessionId: string;
  /** 待答题的稳定标识（P4-1）：来自 currentQuestionId */
  questionId: string;
  /** 候选池内顺序：仅旧调用方兼容 */
  questionIndex?: number;
  answer: string;
  /**
   * 请求标识（P4-9a）：同一次提交的重试必须复用同一个标识，
   * 服务端据此返回原结果而不是再次推进（可选：不带时只保留并发闸门）。
   */
  requestId?: string;
  /** 预期会话版本（P4-9a）：取自上次响应或会话读取的 turnVersion */
  expectedVersion?: number;
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
  /** 推进后的会话版本（P4-9a）：下一次提交要原样回传 */
  turnVersion?: number;
  /** 用户答题累计耗时（秒，P4Q-2）：随载荷带回，顶栏不必再发一次会话请求 */
  consumedSeconds?: number;
  /** 剩余预算（秒，P4Q-2）；null = 无计划 */
  remainingSeconds?: number | null;
  /** 模型建议被 Java 接纳后实际展示的承接语 */
  transitionMessage?: string | null;
}

export interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}

export interface InterviewReport {
  sessionId: string;
  totalQuestions: number;
  overallScore: number;
  categoryScores: CategoryScore[];
  questionDetails: QuestionEvaluation[];
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  referenceAnswers: ReferenceAnswer[];
}

export interface CategoryScore {
  category: string;
  score: number;
  questionCount: number;
}

export interface QuestionEvaluation {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
}

export interface ReferenceAnswer {
  questionIndex: number;
  question: string;
  referenceAnswer: string;
  keyPoints: string[];
}
