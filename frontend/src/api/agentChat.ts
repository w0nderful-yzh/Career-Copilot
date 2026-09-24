import request from './request';
import type {
  ActionSelected,
  AttachmentRef,
  ConversationDetail,
  ConversationItem,
  StreamEvent,
} from '../types/copilot';
import type { UploadResponse } from '../types/resume';
import { serializeAttachments } from '../utils/agentChatProtocol';

export interface StreamChatOptions {
  conversationId?: number;
  /** 已上传资源的结构化引用（如简历 id），文件二进制不经 Agent */
  attachments?: AttachmentRef[];
  action?: ActionSelected;
  /**
   * 进行中的面试会话 ID（P4-10）：Interview Mode 里带上，
   * 让 Copilot 能读当前进展（现在考到哪、还剩什么），而不必等面试结束。
   */
  activeInterviewSessionId?: string;
  /**
   * 重新生成本轮回答：用户消息已存在于会话历史，后端不得重复落库，
   * 只重新生成并保存助手回复（前端已在调用前删掉旧的助手消息）。
   */
  regenerate?: boolean;
}

/**
 * 发送消息并消费 SSE 流式响应。
 *
 * 通过 AbortSignal 支持取消：取消后抛出 DOMException(AbortError)，
 * 由调用方决定如何标记消息状态。携带 conversationId 时后端会在流式结束后持久化本轮消息。
 */
export async function streamChat(
  message: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
  options: StreamChatOptions = {},
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message,
      conversation_id: options.conversationId ?? null,
      // 前端类型用 camelCase，Python 协议用 snake_case，在边界转换
      attachments: serializeAttachments(options.attachments ?? []),
      action: options.action ?? null,
      active_interview_session_id: options.activeInterviewSessionId ?? null,
      regenerate: options.regenerate ?? false,
    }),
    signal,
  });

  if (!response.ok) {
    throw new Error(`请求失败: HTTP ${response.status}`);
  }
  if (!response.body) {
    throw new Error('浏览器不支持流式响应');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // SSE 帧以空行分隔，逐帧解析
    let separatorIndex: number;
    while ((separatorIndex = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      const dataLine = frame
        .split('\n')
        .find((line) => line.startsWith('data: '));
      if (!dataLine) continue;
      try {
        onEvent(JSON.parse(dataLine.slice(6)) as StreamEvent);
      } catch {
        // 忽略无法解析的帧，不影响后续事件
      }
    }
  }
}
// ===== Copilot 会话管理（Java /api/agent/conversations） =====

const conversationBase = '/api/agent/conversations';

/** 会话状态：ACTIVE 活跃（默认列表）/ ARCHIVED 已归档（软隐藏，可恢复） */
export type ConversationStatusFilter = 'ACTIVE' | 'ARCHIVED';

export const conversationApi = {
  list: (status: ConversationStatusFilter = 'ACTIVE') =>
    request.get<ConversationItem[]>(`${conversationBase}?status=${status}`),

  create: () => request.post<ConversationItem>(conversationBase, {}),

  getDetail: (conversationId: number) =>
    request.get<ConversationDetail>(`${conversationBase}/${conversationId}`),

  rename: (conversationId: number, title: string) =>
    request.put<void>(`${conversationBase}/${conversationId}/title`, { title }),

  togglePin: (conversationId: number) =>
    request.put<void>(`${conversationBase}/${conversationId}/pin`),

  /** 归档：从活跃列表收起但保留记录，可恢复（区别于 remove 的硬删除） */
  archive: (conversationId: number) =>
    request.put<void>(`${conversationBase}/${conversationId}/archive`),

  restore: (conversationId: number) =>
    request.put<void>(`${conversationBase}/${conversationId}/restore`),

  remove: (conversationId: number) =>
    request.delete<void>(`${conversationBase}/${conversationId}`),

  /**
   * 截断消息：删除该消息及其之后的全部消息，返回删除条数。
   *
   * 供「编辑已发送消息」与「重新生成回答」使用——两者都要求历史里不留残影，
   * 且都必须先按 messageId 对账（见 utils/copilotMessageReconcile），不可按数量猜。
   */
  truncateFrom: (conversationId: number, messageId: number) =>
    request.delete<number>(`${conversationBase}/${conversationId}/messages/${messageId}`),
};

// ===== 技能画像（Java Profile 模块，P3-2） =====

export type ProfileEvidenceSource = 'RESUME' | 'INTERVIEW_SESSION' | 'INTERVIEW_TURN';

export interface ProfileEvidence {
  sourceType: ProfileEvidenceSource;
  sourceId: string;
  /** null = 声明型证据（简历列出的技能，尚无评分，不参与聚合） */
  score: number | null;
  occurredAt?: string | null;
}

export interface SkillProfileSkill {
  skill: string;
  score: number;
  evidenceCount: number;
  updatedAt?: string | null;
  evidences?: ProfileEvidence[];
}

/** 简历已列、尚无评分证据的技能（P3 待收口："待验证"） */
export interface DeclaredSkill {
  skill: string;
  resumeId: string;
  declaredAt?: string | null;
}

export interface SkillProfileResponse {
  skills: SkillProfileSkill[];
  declaredSkills?: DeclaredSkill[];
}

export const skillProfileApi = {
  /** 全部技能画像 + 证据明细（侧栏面板用；无数据时 skills 为空数组）。
   * Java Tool 端点返回 Result<ToolResponse> 双层信封，需解出内层业务数据 */
  get: async (): Promise<SkillProfileResponse> => {
    const envelope = await request.post<{ tool: string; data: SkillProfileResponse }>(
      '/api/agent/tools/get_skill_profile',
      {},
    );
    return envelope?.data ?? { skills: [] };
  },
};

// ===== 简历附件上传（复用 Java 简历库上传，文件不经 Agent） =====

export const resumeUploadApi = {
  uploadAndAnalyze: (file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<UploadResponse>('/api/resumes/upload', formData);
  },
};

// ===== JD 附件上传（P2-5：独立于简历库，Tika 解析文本入库） =====

export interface JobUploadResult {
  id: number;
  title: string;
  company?: string | null;
  contentLength?: number;
}

export const jobUploadApi = {
  upload: (file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<JobUploadResult>('/api/jobs/upload', formData);
  },
};

// ===== JD 查询（P2-5：侧栏活跃资源显示绑定 JD 标题） =====

export const jobApi = {
  get: async (jobId: number): Promise<JobUploadResult & { contentText?: string }> => {
    return request.get(`/api/jobs/${jobId}`);
  },
};
