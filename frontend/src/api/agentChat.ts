import request from './request';
import type {
  AttachmentRef,
  ConversationDetail,
  ConversationItem,
  StreamEvent,
} from '../types/copilot';
import type { UploadResponse } from '../types/resume';

/**
 * 发送消息并消费 SSE 流式响应。
 *
 * 通过 AbortSignal 支持取消：取消后抛出 DOMException(AbortError)，
 * 由调用方决定如何标记消息状态。携带 conversation_id 时后端会在流式结束后持久化本轮消息。
 * attachments 为已上传资源的结构化引用（如简历 id）。
 */
export async function streamChat(
  message: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
  conversationId?: number,
  attachments?: AttachmentRef[],
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message,
      conversation_id: conversationId ?? null,
      // 前端类型用 camelCase，Python 协议用 snake_case，在边界转换
      attachments: (attachments ?? []).map((att) => ({
        kind: att.kind,
        resume_id: att.resumeId,
        filename: att.filename ?? null,
        duplicate: att.duplicate ?? false,
      })),
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

export const conversationApi = {
  list: () => request.get<ConversationItem[]>(conversationBase),

  create: () => request.post<ConversationItem>(conversationBase, {}),

  getDetail: (conversationId: number) =>
    request.get<ConversationDetail>(`${conversationBase}/${conversationId}`),

  rename: (conversationId: number, title: string) =>
    request.put<void>(`${conversationBase}/${conversationId}/title`, { title }),

  togglePin: (conversationId: number) =>
    request.put<void>(`${conversationBase}/${conversationId}/pin`),

  remove: (conversationId: number) =>
    request.delete<void>(`${conversationBase}/${conversationId}`),
};

// ===== 简历附件上传（复用 Java 简历库上传，文件不经 Agent） =====

export const resumeUploadApi = {
  uploadAndAnalyze: (file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<UploadResponse>('/api/resumes/upload', formData);
  },
};
