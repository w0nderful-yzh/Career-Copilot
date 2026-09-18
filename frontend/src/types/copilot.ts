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
  planned_duration_minutes: number;
  required_topics: string[];
  resume_id?: number | null;
  summary: string;
}

/**
 * InterviewConfig：手动配置面板与 Agent 推荐收敛到的同一份面试配置。
 *
 * CREATE_INTERVIEW action 的 payload 在此基础上再带 `resumeId` 与 `requestId`
 * （camelCase，对齐后端 action 契约）。requestId 是**创建幂等键**：
 * 同一次确认流程（含网络重发/重试）必须复用同一值，配置变化时换新值——
 * 沿用旧键会让 Java 侧命中幂等缓存、返回上一次那个配置不符的会话。
 */
export interface InterviewConfig {
  direction: string;
  difficulty: string;
  planned_duration_minutes: number;
  required_topics: string[];
  focus: string[];
}

/**
 * 面试会话信号块（Interview Mode 重构）：CREATE_INTERVIEW 成功后由 Agent 下发。
 * 前端不再渲染成大 Card，而是把它作为「进入 Interview Mode」的信号：
 * CopilotPage 读取 session_id → 切到 Interview Mode，由消息流渲染 Java 题目数据。
 */
export interface InterviewSessionBlock {
  type: 'interview_session';
  session_id: string;
  skill_id?: string | null;
  difficulty?: string | null;
  mode: 'TEXT' | 'VOICE';
  focus?: string[];
  planned_duration_minutes?: number | null;
  required_topics?: string[];
  direction_name?: string | null;
}

/** Interview Mode 的运行时状态（放 CopilotPage，驱动中间区渲染） */
export interface InterviewModeState {
  sessionId: string;
  status: 'starting' | 'running' | 'evaluating' | 'completed' | 'error';
  title: string;
  difficulty?: string | null;
  error?: string | null;
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
  /** 简历已列、尚无评分证据的技能（P3 待收口）：无分数，仅表达「待验证」 */
  declaredSkills?: Array<{
    skill?: string | null;
    resumeId?: string | null;
    declaredAt?: string | null;
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
  /** 优化模式（P2 待修正）：让「通用 / 定向方向」在卡片上可分辨 */
  optimizationType?: 'GENERAL' | 'TARGET_DIRECTION' | 'JD_TARGETED' | null;
  targetDirection?: string | null;
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

/**
 * 消息渲染态。
 *
 * - streaming：正在流式产出；
 * - done：正常完成；
 * - stopped：用户主动「停止生成」或连接中断（与 done 区分，刷新后仍可还原）；
 * - error：本轮生成失败。
 */
export type MessageStatus = 'streaming' | 'done' | 'stopped' | 'error';

/** P1-2 工具执行轨迹步骤（tool_started / tool_completed 驱动） */
export interface ToolTraceStep {
  label: string;
  /** started 后未收到 completed 时为 true */
  pending: boolean;
}

/**
 * 「重新发送」所需的原始请求载荷。
 *
 * 失败/停止的轮次记下自己是怎么发出的，用户点重发时按同样的参数重跑一轮，
 * 不必重新输入（尤其是带附件或 Action 提交的轮次）。
 */
export interface TurnRetryPayload {
  message: string;
  userContent: string;
  attachments: AttachmentRef[];
  action?: ActionSelected;
  /**
   * 附件轮次在「上传失败」时才携带原始文件：那时资源 id 还不存在，只能按文件重走上传。
   * 上传成功的轮次改用 attachments 里的 id 重发（不重复上传）。仅在失败轮持有 File 引用，
   * 避免为每一轮附件对话常驻文件内存。
   */
  attachment?: { file: File; kind: 'resume' | 'job_description' };
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
  /** 本轮原始请求，供失败/停止后的「重新发送」复用 */
  retry?: TurnRetryPayload;
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
  /** 本轮生成终态 COMPLETED / STOPPED / FAILED（P1 停止生成留痕；老数据可能为 null） */
  status?: string | null;
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
