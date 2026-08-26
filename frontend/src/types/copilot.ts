// Copilot Workspace 消息协议类型
// Block 类型与 Python agent-service 协议保持一致，前端只渲染白名单类型

export type AgentBlockType =
  | 'text'
  | 'action'
  | 'resume_summary'
  | 'interview_summary'
  | 'knowledge_citations';

export interface TextBlock {
  type: 'text';
  content: string;
}

export interface ActionBlock {
  type: 'action';
  route: string;
  label: string;
  params?: Record<string, unknown>;
}

export interface ResumeSummaryBlock {
  type: 'resume_summary';
  resumes: Array<{
    id?: number | null;
    filename?: string | null;
    latestScore?: number | null;
    lastAnalyzedAt?: string | null;
    interviewCount?: number | null;
  }>;
}

export interface InterviewSummaryBlock {
  type: 'interview_summary';
  interviews: Array<{
    sessionId?: string | null;
    skillId?: string | null;
    difficulty?: string | null;
    status?: string | null;
    evaluateStatus?: string | null;
    totalQuestions?: number | null;
    resumeId?: number | null;
  }>;
}

export interface KnowledgeCitationsBlock {
  type: 'knowledge_citations';
  citations: Array<{
    knowledgeBaseId?: number | null;
    name?: string | null;
  }>;
}

export type AgentBlock =
  | TextBlock
  | ActionBlock
  | ResumeSummaryBlock
  | InterviewSummaryBlock
  | KnowledgeCitationsBlock;

export type MessageStatus = 'streaming' | 'done' | 'error';

export interface CopilotMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  blocks: AgentBlock[];
  status: MessageStatus;
  error?: string;
}

// Copilot 对话会话（Java System of Record）
export interface ConversationItem {
  id: number;
  title: string;
  messageCount: number;
  isPinned: boolean;
  updatedAt: string;
}

export interface ConversationMessage {
  id: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  blocks: string | null; // JSON 字符串（结构化 Block 数组）
  createdAt: string;
}

export interface ConversationDetail {
  id: number;
  title: string;
  isPinned: boolean;
  messages: ConversationMessage[];
  createdAt: string;
  updatedAt: string;
}

// SSE 流式事件（与 Python StreamEvent 协议一致）
export type StreamEvent =
  | { type: 'block'; payload: Record<string, unknown> }
  | { type: 'message_delta'; payload: { content: string } }
  | { type: 'error'; payload: { message: string } }
  | { type: 'done'; payload: Record<string, unknown> };

// Action 白名单：路由 key → 前端真实路径
// 未知路由不渲染按钮，禁止任意跳转
export const ACTION_ROUTE_MAP: Record<string, { path: string; label: string }> = {
  RESUME_UPLOAD: { path: '/upload', label: '上传简历' },
  INTERVIEW_CREATE: { path: '/interview-hub', label: '开始模拟面试' },
  INTERVIEW_HISTORY: { path: '/interviews', label: '面试记录' },
  KNOWLEDGE_BASE: { path: '/knowledgebase', label: '知识库管理' },
  KNOWLEDGE_CHAT: { path: '/knowledgebase/chat', label: '问答助手' },
  SETTINGS: { path: '/settings', label: '设置' },
};