import { request } from './request';

export type AnalyzeStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type EvaluateStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface ResumeListItem {
  id: number;
  filename: string;
  fileSize: number;
  uploadedAt: string;
  accessCount: number;
  latestScore?: number;
  lastAnalyzedAt?: string;
  interviewCount: number;
  analyzeStatus?: AnalyzeStatus;
  analyzeError?: string;
  storageUrl?: string;
}

export interface ResumeStats {
  totalCount: number;
  totalInterviewCount: number;
  totalAccessCount: number;
}

export interface AnalysisItem {
  id: number;
  overallScore: number;
  contentScore: number;
  structureScore: number;
  skillMatchScore: number;
  expressionScore: number;
  projectScore: number;
  summary: string;
  analyzedAt: string;
  strengths: string[];
  suggestions: unknown[];
}

export interface InterviewItem {
  id: number;
  sessionId: string;
  totalQuestions: number;
  status: string;
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  overallScore: number | null;
  overallFeedback: string | null;
  createdAt: string;
  completedAt: string | null;
  questions?: unknown[];
  strengths?: string[];
  improvements?: string[];
  referenceAnswers?: unknown[];
}

export interface AnswerItem {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  referenceAnswer?: string;
  keyPoints?: string[];
  answeredAt: string;
}

export interface ResumeDetail {
  id: number;
  filename: string;
  fileSize: number;
  contentType: string;
  storageUrl: string;
  uploadedAt: string;
  accessCount: number;
  resumeText: string;
  analyzeStatus?: AnalyzeStatus;
  analyzeError?: string;
  analyses: AnalysisItem[];
  interviews: InterviewItem[];
}

export interface InterviewDetail extends InterviewItem {
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  answers: AnswerItem[];
}

export const historyApi = {
  /**
   * 获取所有简历列表
   */
  async getResumes(): Promise<ResumeListItem[]> {
    return request.get<ResumeListItem[]>('/api/resumes');
  },

  /**
   * 获取简历详情
   */
  async getResumeDetail(id: number): Promise<ResumeDetail> {
    return request.get<ResumeDetail>(`/api/resumes/${id}/detail`);
  },

  /**
   * 获取面试详情
   */
  async getInterviewDetail(sessionId: string): Promise<InterviewDetail> {
    return request.get<InterviewDetail>(`/api/interview/sessions/${sessionId}/details`);
  },

  /**
   * 导出简历分析报告PDF
   */
  async exportAnalysisPdf(resumeId: number): Promise<Blob> {
    return request.download(`/api/resumes/${resumeId}/export`);
  },

  /**
   * 导出面试报告PDF
   */
  async exportInterviewPdf(sessionId: string): Promise<Blob> {
    return request.download(`/api/interview/sessions/${sessionId}/export`);
  },

  /**
   * 删除简历
   */
  async deleteResume(id: number): Promise<void> {
    return request.delete(`/api/resumes/${id}`);
  },

  /**
   * 删除面试记录
   */
  async deleteInterview(sessionId: string): Promise<void> {
    return request.delete(`/api/interview/sessions/${sessionId}`);
  },

  /**
   * 获取简历统计信息
   */
  async getStatistics(): Promise<ResumeStats> {
    return request.get<ResumeStats>('/api/resumes/statistics');
  },

  /**
   * 重新分析简历
   */
  async reanalyze(id: number): Promise<void> {
    return request.post(`/api/resumes/${id}/reanalyze`);
  },

  /**
   * 获取简历的结构化版本列表（P2-0 解析确认 / P2-2 版本管理）
   */
  async getResumeVersions(resumeId: number): Promise<ResumeVersionItem[]> {
    return request.get<ResumeVersionItem[]>(`/api/resumes/${resumeId}/versions`);
  },

  /**
   * 确认解析结果（可携带补录修正后的结构化内容；请求体包装避免空对象歧义）
   */
  async confirmResumeVersion(
    versionId: number,
    correctedContent?: ResumeContentJson,
  ): Promise<ResumeVersionItem> {
    return request.post<ResumeVersionItem>(
      `/api/resume-versions/${versionId}/confirm`,
      { correctedContent: correctedContent ?? null },
    );
  },
};

// ===== 简历结构化版本（P2-0/P2-3） =====

export interface ResumeContentJson {
  basicInfo?: {
    name?: string | null;
    phone?: string | null;
    email?: string | null;
    location?: string | null;
    jobIntention?: string | null;
  } | null;
  education?: ResumeEducationItem[] | null;
  experience?: ResumeExperienceItem[] | null;
  projects?: ResumeProjectItem[] | null;
  skills?: ResumeSkillItem[] | null;
  customSections?: ResumeCustomSection[] | null;
}

export interface ResumeEducationItem {
  school?: string | null;
  major?: string | null;
  degree?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  description?: string | null;
}

export interface ResumeExperienceItem {
  company?: string | null;
  position?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  bullets?: string[] | null;
}

export interface ResumeProjectItem {
  name?: string | null;
  role?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  techStack?: string | null;
  bullets?: string[] | null;
}

export interface ResumeSkillItem {
  category?: string | null;
  content?: string | null;
}

export interface ResumeCustomSection {
  title?: string | null;
  items?: string[] | null;
}

export interface ResumeVersionItem {
  id: number;
  resumeId: number;
  version: number;
  source: 'IMPORT' | 'USER_EDIT' | 'AI_OPTIMIZE';
  confirmationStatus: 'PENDING_CONFIRMATION' | 'ACTIVE' | 'NEED_USER_INFO';
  content: ResumeContentJson | null;
  missingFields: string[];
  sourceCreatedAt: string;
  createdAt: string;
}
