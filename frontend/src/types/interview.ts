// 面试相关类型定义

import type { CategoryDTO } from '../api/skill';

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  /**
   * 题库总数（含**候选择问**）。
   *
   * 注意：**不要**拿它当进度分母——自适应会话会跳过部分候选追问，用它做分母会让总进度虚高
   * （用户只答了 3 题却显示「第 10 / 12 题」）。进度请用 answeredCount + 主问题数推导。
   */
  totalQuestions: number;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
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
   * 提交下一轮时把它作为 expectedVersion 回传；服务端据此拒绝过期请求
   * （另一个标签页已答完这题、或用户已结束面试），不会静默改写历史。
   */
  turnVersion?: number | null;
}

export type AsyncTaskStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface InterviewQuestion {
  questionIndex: number;
  question: string;
  type: string;
  category: string;
  topicSummary?: string | null;
  userAnswer: string | null;
  score: number | null;
  feedback: string | null;
  isFollowUp?: boolean;
  parentQuestionIndex?: number | null;
  /** 追问序号（P4Q-6）：同一主问题下的第几条追问，主问题为 null；技能名不再拼序号 */
  followUpIndex?: number | null;
  /**
   * 该题的作答状态（P4Q-5）：null/undefined = 尚未提问过。
   *
   * 跳过时 userAnswer 为空，只看答案文本会把已跳过的题从轨迹里丢掉，所以判据是
   * 「有答案 或 有状态」。
   */
  answerState?: 'ANSWERED' | 'SKIPPED' | 'DECLINED' | 'UNANSWERED' | null;
  referenceAnswer?: string | null;
  keyPoints?: string[];
  scoringRubric?: string | null;
  sourceContext?: string | null;
}

// ===== 本场面试的画像变化（P3 待收口） =====

/** 本场贡献的一条证据：题号由 sourceId（"sessionId:questionIndex"）解析而来 */
export interface ImpactEvidence {
  sourceId: string;
  questionIndex: number | null;
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
  questionCount: number;
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
  questionIndex: number;
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
