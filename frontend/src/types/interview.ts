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
}

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
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
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
