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
  evaluateStatusUpdatedAt: string | null;
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
   * 提交答案（P4-9a：带请求标识与预期会话版本）。
   *
   * 重复发送同一个 requestId 时服务端返回原结果，不会再次推进——重试可以直接复用标识。
   */
  async submitAnswer(req: SubmitAnswerRequest): Promise<SubmitAnswerResponse> {
    return request.post<SubmitAnswerResponse>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      {
        questionId: req.questionId,
        questionIndex: req.questionIndex,
        answer: req.answer,
        requestId: req.requestId,
        expectedVersion: req.expectedVersion,
      },
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
   * 跳过当前题（P4Q-5 一等动作，P4-9a 与提交共用同一条推进链路）。
   *
   * Java 侧不调模型、不追问、不计分、不产生画像证据——与「答错」严格区分。
   */
  /** 用户调整剩余时间预算（P4Q-2）：当轮生效 */
  async updateBudget(sessionId: string, remainingMinutes: number): Promise<void> {
    await request.post<void>(`/api/interview/sessions/${sessionId}/budget`, {
      remainingMinutes,
    });
  },

  /**
   * 显式难度调整（P4Q-3c）：与跳过/预算同级的确定性节奏动作，不等模型。
   * 只改本场难度偏好，下一轮选题/生成立即生效，不推进轮次也不改当前题。
   */
  async updatePace(sessionId: string, difficulty: 'junior' | 'mid' | 'senior'): Promise<void> {
    await request.post<void>(`/api/interview/sessions/${sessionId}/pace`, { difficulty });
  },

  async skipQuestion(
    sessionId: string,
    questionId: string,
    requestId?: string,
    expectedVersion?: number,
  ): Promise<SubmitAnswerResponse> {
    return request.post<SubmitAnswerResponse>(
      `/api/interview/sessions/${sessionId}/skip`,
      { questionId, requestId, expectedVersion }
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
   * 提前交卷（P4-9a：与逐轮推进同一并发边界，带请求标识即可安全重试）
   */
  async completeInterview(
    sessionId: string,
    requestId?: string,
    expectedVersion?: number,
  ): Promise<void> {
    return request.post<void>(
      `/api/interview/sessions/${sessionId}/complete`,
      { requestId, expectedVersion }
    );
  },
};
