// Copilot Workspace 消息协议类型
// Block 类型与 Python agent-service 协议保持一致，前端只渲染白名单类型

export type AgentBlockType =
  | 'text'
  | 'action'
  | 'navigation'
  | 'choice'
  | 'resume_summary'
  | 'interview_summary'
  | 'knowledge_citations'
  | 'skill_profile'
  | 'resume_optimization'
  | 'interview_proposal'
  | 'interview_session';

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

/** Agent 完成确定性写操作（如面试创建成功）后给出的导航入口，由白名单映射 */
export interface NavigationBlock {
  type: 'navigation';
  route: string;
  label: string;
  params?: Record<string, unknown>;
}

/** 面试提案确认块（P1-4）：Agent 推荐配置 + [按推荐开始] / [调整配置] */
export interface InterviewProposalBlock {
  type: 'interview_proposal';
  direction: string;
  direction_name: string;
  difficulty: string;
  difficulty_name: string;
  mode: 'TEXT' | 'VOICE';
  focus: string[];
  question_count: number;
  resume_id?: number | null;
  summary: string;
}

/**
 * 内嵌面试会话块（P4-0）：CREATE_INTERVIEW 成功后原地内嵌，答题直连 Java Interview API。
 * 展示参数（skillId/difficulty/mode/focus）由 Agent 创建时给出；
 * 状态机在块内自管理（读取 getSession / submitAnswer / 轮询评估），不写入 Conversation Message。
 */
export interface InterviewSessionBlock {
  type: 'interview_session';
  /** Java 面试会话 ID */
  sessionId: string;
  /** Java skillId（如 java-backend），用于展示 */
  skillId?: string | null;
  /** Java 难度枚举 junior/mid/senior，用于展示 */
  difficulty?: string | null;
  mode: 'TEXT' | 'VOICE';
  focus?: string[];
  questionCount?: number | null;
  /** 方向展示名（Agent 语境，如 "Java 后端"）；缺省回退 skillId */
  directionName?: string | null;
}

/** 内嵌面试块的展示状态（块内部状态机，非持久化协议字段） */
export type InterviewLiveStatus =
  | 'loading'      // 拉取会话中
  | 'running'      // 面试进行中（展示当前题 + 输入）
  | 'answering'    // 提交答案后等待下一题（决策/评估中）
  | 'evaluating'   // 全部答完，等待异步整场评估
  | 'completed'    // 评估完成，展示结果卡
  | 'error';       // 会话不可用

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

/** 技能证据：一次可追溯的评分来源（如某场面试的某道题） */
export interface SkillEvidence {
  sourceType?: 'RESUME' | 'INTERVIEW_SESSION' | 'INTERVIEW_TURN' | null;
  sourceId?: string | null;
  score?: number | null;
  occurredAt?: string | null;
}

/** 技能画像块（P3-2）：Evidence-driven 聚合分 + 证据明细，数值由 Java 聚合器产出 */
export interface SkillProfileBlock {
  type: 'skill_profile';
  skills: Array<{
    skill?: string | null;
    score?: number | null;
    evidenceCount?: number | null;
    evidences?: SkillEvidence[] | null;
  }>;
}

/** 单条简历优化建议（P2-1）：JSON-path 定位的 Diff */
export interface ResumeOptimizationPatch {
  id: string;
  type: 'REPLACE' | 'ADD' | 'DELETE';
  path: string;
  oldValue?: string | null;
  newValue?: string | null;
  reason: string;
}

/** 简历优化提案块（P2-3）：Diff 卡片 + 勾选应用（用户确认后才执行写操作） */
export interface ResumeOptimizationBlock {
  type: 'resume_optimization';
  proposalId: number;
  resumeId: number;
  versionId: number;
  summary: string;
  patches: ResumeOptimizationPatch[];
  rejectedNote?: string | null;
}

export type AgentBlock =
  | TextBlock
  | ActionBlock
  | NavigationBlock
  | ChoiceBlock
  | ResumeSummaryBlock
  | InterviewSummaryBlock
  | KnowledgeCitationsBlock
  | SkillProfileBlock
  | ResumeOptimizationBlock
  | InterviewProposalBlock
  | InterviewSessionBlock;

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
  activeResumeId?: number | null;
  /** 会话绑定的活动 JD（P2-5 Conversation Memory） */
  activeJobId?: number | null;
}

// 结构化资源引用（随消息附带，文件二进制不经 Agent，只传资源 id）
export interface AttachmentRef {
  kind: 'resume' | 'job_description';
  resumeId?: number;
  /** JD 资源 id（kind=job_description 时必填，Java job_descriptions 主键） */
  jobId?: number;
  filename?: string;
  /** Java 判定内容重复、未新增记录时置 true（复用已有简历；JD 不去重） */
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
