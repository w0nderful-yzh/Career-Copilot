// Copilot Workspace 消息协议类型
// Block 类型与 Python agent-service 协议保持一致，前端只渲染白名单类型

export type AgentBlockType =
  | 'text'
  | 'action'
  | 'choice'
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

export interface ChoiceOption {
  action: string;
  label: string;
  payload?: Record<string, unknown>;
}

export interface ChoiceBlock {
  type: 'choice';
  title?: string | null;
  options: ChoiceOption[];
}

export interface ActionSelected {
  type: 'ACTION_SELECTED';
  action: string;
  payload?: Record<string, unknown>;
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
  | ChoiceBlock
  | ResumeSummaryBlock
  | InterviewSummaryBlock
  | KnowledgeCitationsBlock;

export type MessageStatus = 'streaming' | 'done' | 'error';

/** P1-2 工具执行轨迹步骤（tool_started / tool_completed 驱动） */
export interface ToolTraceStep {
  label: string;
  /** started 后未收到 completed 时为 true */
  pending: boolean;
}

export interface CopilotMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  blocks: AgentBlock[];
  status: MessageStatus;
  error?: string;
  /** 工具执行轨迹：依次累积，流式结束后整行保留（体现 Agent 实际执行步骤） */
  toolTrace?: ToolTraceStep[];
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

// 结构化资源引用（随消息附带，文件二进制不经 Agent，只传资源 id）
export interface AttachmentRef {
  kind: 'resume';
  resumeId: number;
  filename?: string;
  /** Java 判定内容重复、未新增记录时置 true（复用已有简历） */
  duplicate?: boolean;
}

// SSE 流式事件（与 Python StreamEvent 协议一致）
export type StreamEvent =
  | { type: 'block'; payload: Record<string, unknown> }
  | { type: 'message_delta'; payload: { content: string } }
  | { type: 'error'; payload: { message: string } }
  | { type: 'done'; payload: Record<string, unknown> }
  // P1-2：Graph 执行期轻量进度事件
  | { type: 'tool_started'; payload: { tool: string; label?: string } }
  | { type: 'tool_progress'; payload: { tool: string; label: string } }
  | { type: 'tool_completed'; payload: { tool: string } }
  | { type: 'run_status'; payload: { status: string } };
