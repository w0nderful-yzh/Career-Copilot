import { request } from './request';
import type {
  CreateInterviewRequest,
  CurrentQuestionResponse,
  InterviewReport,
  InterviewSession,
  ProfileImpact,
  SubmitAnswerRequest,
  SubmitAnswerResponse
} from '../types/interview';

export interface TextSessionMeta {
  sessionId: string;
  skillId: string;
  difficulty: string;
  resumeId: number | null;
  totalQuestions: number;
  status: string;
  evaluateStatus: string | null;
  evaluateError: string | null;
  overallScore: number | null;
  sourceType: string | null;
  knowledgeBaseId: number | null;
  interviewCategory?: string | null;
  createdAt: string;
  completedAt: string | null;
}

export const interviewApi = {
  /**
   * 列出所有文字面试会话
   */
  async listSessions(): Promise<TextSessionMeta[]> {
    return request.get<TextSessionMeta[]>('/api/interview/sessions');
  },

  /**
   * 创建面试会话
   */
  async createSession(req: CreateInterviewRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/interview/sessions', req, {
      timeout: 180000, // 3分钟超时，AI生成问题需要时间
    });
  },

  /**
   * 获取会话信息
   */
  async getSession(sessionId: string): Promise<InterviewSession> {
    return request.get<InterviewSession>(`/api/interview/sessions/${sessionId}`);
  },

  /**
   * 获取当前问题
   */
  async getCurrentQuestion(sessionId: string): Promise<CurrentQuestionResponse> {
    return request.get<CurrentQuestionResponse>(`/api/interview/sessions/${sessionId}/question`);
  },

  /**
   * 提交答案
   */
  async submitAnswer(req: SubmitAnswerRequest): Promise<SubmitAnswerResponse> {
    return request.post<SubmitAnswerResponse>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer },
      {
        timeout: 180000, // 3分钟超时
      }
    );
  },

  /**
   * 获取面试报告
   */
  async getReport(sessionId: string): Promise<InterviewReport> {
    return request.get<InterviewReport>(`/api/interview/sessions/${sessionId}/report`, {
      timeout: 180000, // 3分钟超时，AI评估需要时间
    });
  },

  /**
   * 本场面试带来的画像变化（P3 待收口）。
   *
   * 差分由 Java 按证据重算（before = 排除本场 / after = 含本场），无额外存储；
   * 每条变化带逐题证据（题号 + 时间）供追溯。失败由调用方自行降级（不阻塞结果卡）。
   */
  async getProfileImpact(sessionId: string): Promise<ProfileImpact> {
    return request.get<ProfileImpact>(
      `/api/interview/sessions/${sessionId}/profile-impact`
    );
  },

  /**
   * 重试生成面试报告（P4Q-4）：评估失败或超时后的用户重试入口。
   * 已有报告时会话幂等返回，不会重复评分。
   */
  async retryEvaluation(sessionId: string): Promise<InterviewSession> {
    return request.post<InterviewSession>(
      `/api/interview/sessions/${sessionId}/evaluate/retry`
    );
  },

  /**
   * 查找未完成的面试会话
   */
  async findUnfinishedSession(resumeId: number): Promise<InterviewSession | null> {
    try {
      return await request.get<InterviewSession>(`/api/interview/sessions/unfinished/${resumeId}`);
    } catch {
      // 如果没有未完成的会话，返回null
      return null;
    }
  },

  /**
   * 暂存答案（不进入下一题）
   */
  async saveAnswer(req: SubmitAnswerRequest): Promise<void> {
    return request.put<void>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer }
    );
  },

  /**
   * 提前交卷
   */
  async completeInterview(sessionId: string): Promise<void> {
    return request.post<void>(`/api/interview/sessions/${sessionId}/complete`);
  },
};
