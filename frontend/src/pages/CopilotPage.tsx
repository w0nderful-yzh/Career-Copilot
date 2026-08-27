import { useCallback, useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { conversationApi, resumeUploadApi, streamChat } from '../api/agentChat';
import Composer from '../components/copilot/Composer';
import MessageList from '../components/copilot/MessageList';
import type { CopilotOutletContext } from '../components/Layout';
import type {
  AgentBlock,
  AttachmentRef,
  ConversationDetail,
  ConversationItem,
  CopilotMessage,
  StreamEvent,
} from '../types/copilot';

// Copilot Workspace：Agent 对话工作台
// 会话列表由 Layout 最左侧栏统一管理（避免双层侧栏），本页只负责消息区

let messageSeq = 0;
function nextId(): string {
  messageSeq += 1;
  return `msg_${Date.now()}_${messageSeq}`;
}

/** 解析 Java 侧 blocks JSON 字符串为受控 Block 数组，解析失败返回空 */
function parseBlocks(blocksJson: string | null): AgentBlock[] {
  if (!blocksJson) return [];
  try {
    const parsed = JSON.parse(blocksJson);
    return Array.isArray(parsed) ? (parsed as AgentBlock[]) : [];
  } catch {
    return [];
  }
}

/** 历史消息 → 前端消息模型 */
function toCopilotMessages(detail: ConversationDetail): CopilotMessage[] {
  return detail.messages.map((message) => ({
    id: `saved_${message.id}`,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    blocks: parseBlocks(message.blocks),
    status: 'done',
  }));
}

export default function CopilotPage() {
  const {
    activeConversationId,
    refreshConversations,
    selectConversation,
    onConversationCreated,
  } = useOutletContext<CopilotOutletContext>();

  const [messages, setMessages] = useState<CopilotMessage[]>([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  // 新建会话首次触发历史加载时跳过（保留刚追加的流式消息，避免被空历史覆盖）
  const skipHistoryLoadRef = useRef<number | null>(null);

  const updateMessage = useCallback(
    (id: string, updater: (message: CopilotMessage) => CopilotMessage) => {
      setMessages((prev) =>
        prev.map((message) => (message.id === id ? updater(message) : message)),
      );
    },
    [],
  );

  // 当前会话切换时加载历史消息；清空时重置为空白会话
  useEffect(() => {
    let cancelled = false;
    if (activeConversationId === null) {
      setMessages([]);
      return;
    }
    // 刚创建的新会话：不加载历史，保留本次发送追加的消息
    if (skipHistoryLoadRef.current === activeConversationId) {
      skipHistoryLoadRef.current = null;
      return;
    }
    (async () => {
      setLoadingHistory(true);
      try {
        const detail = await conversationApi.getDetail(activeConversationId);
        if (cancelled) return;
        setMessages(toCopilotMessages(detail));
      } catch (err) {
        console.error('Failed to load conversation:', err);
      } finally {
        if (!cancelled) setLoadingHistory(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [activeConversationId]);

  const handleEvent = useCallback(
    (assistantId: string, event: StreamEvent) => {
      switch (event.type) {
        case 'block':
          updateMessage(assistantId, (message) => ({
            ...message,
            blocks: [...message.blocks, event.payload as unknown as AgentBlock],
          }));
          break;
        case 'message_delta':
          updateMessage(assistantId, (message) => ({
            ...message,
            content: message.content + (event.payload.content ?? ''),
          }));
          break;
        case 'error':
          updateMessage(assistantId, (message) => ({
            ...message,
            status: 'error',
            error: event.payload.message ?? '处理失败，请稍后重试',
          }));
          break;
        case 'done':
          updateMessage(assistantId, (message) => ({
            ...message,
            status: message.status === 'error' ? 'error' : 'done',
          }));
          break;
      }
    },
    [updateMessage],
  );

  const send = useCallback(
    async (text: string, attachment?: File) => {
      // 有附件（PDF 简历）时先上传到 Java 简历库（文件不经 Agent，只传资源 id）
      let attachments: AttachmentRef[] = [];
      if (attachment) {
        try {
          const result = await resumeUploadApi.uploadAndAnalyze(attachment);
          const resumeId = result.storage?.resumeId;
          if (!resumeId) {
            throw new Error('上传成功但未返回简历 ID');
          }
          attachments = [{
            kind: 'resume',
            resumeId,
            filename: attachment.name,
            duplicate: result.duplicate ?? false,
          }];
        } catch (err) {
          console.error('Failed to upload resume:', err);
          // 上传失败：不发送，直接提示
          window.alert('简历上传失败，请重试');
          return;
        }
      }

      // 无会话时先创建（Java System of Record），并同步到 Layout 会话列表
      let conversationId = activeConversationId;
      if (conversationId === null) {
        try {
          const created: ConversationItem = await conversationApi.create();
          conversationId = created.id;
          onConversationCreated(created);
          // 标记跳过本次历史加载，避免刚追加的消息被空历史覆盖
          skipHistoryLoadRef.current = created.id;
          selectConversation(created.id);
        } catch (err) {
          console.error('Failed to create conversation:', err);
        }
      }

      // 带附件时用户消息用文件名提示，让历史记录可读
      const userContent = attachments.length > 0
        ? `上传了简历附件：${attachment?.name}`
        : text;

      const assistantId = nextId();
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'user', content: userContent, blocks: [], status: 'done' },
        { id: assistantId, role: 'assistant', content: '', blocks: [], status: 'streaming' },
      ]);
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;
      try {
        await streamChat(
          text || `请处理我上传的简历：${attachment?.name}`,
          (event) => handleEvent(assistantId, event),
          controller.signal,
          conversationId ?? undefined,
          attachments,
        );
      } catch (err) {
        if (controller.signal.aborted) {
          updateMessage(assistantId, (message) => ({
            ...message,
            status: 'done',
          }));
        } else {
          updateMessage(assistantId, (message) => ({
            ...message,
            status: 'error',
            error: err instanceof Error ? err.message : '网络异常，请稍后重试',
          }));
        }
      } finally {
        abortRef.current = null;
        setStreaming(false);
        // 流式结束：刷新会话列表（消息数/标题/时间更新）
        refreshConversations();
      }
    },
    [activeConversationId, handleEvent, onConversationCreated, refreshConversations, selectConversation, updateMessage],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-slate-200 bg-white/80 px-6 py-3 backdrop-blur dark:border-slate-700 dark:bg-slate-900/80">
        <div className="mx-auto flex w-full max-w-3xl items-center justify-between">
          <h1 className="text-sm font-bold text-slate-800 dark:text-white">Career Copilot</h1>
          <span className="text-xs text-slate-400 dark:text-slate-500">Agent 工作台</span>
        </div>
      </header>

      <main className="flex-1 overflow-y-auto bg-slate-50/50 dark:bg-slate-900/50">
        {loadingHistory ? (
          <div className="flex h-full items-center justify-center text-sm text-slate-400">
            加载对话中…
          </div>
        ) : (
          <MessageList messages={messages} />
        )}
      </main>

      <Composer streaming={streaming} onSend={send} onCancel={cancel} />
    </div>
  );
}