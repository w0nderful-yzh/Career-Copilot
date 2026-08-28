import { useCallback, useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { conversationApi, resumeUploadApi, streamChat } from '../api/agentChat';
import Composer from '../components/copilot/Composer';
import ContextPanel from '../components/copilot/ContextPanel';
import MessageList from '../components/copilot/MessageList';
import type { CopilotOutletContext } from '../components/Layout';
import type {
  ActionSelected,
  AgentBlock,
  AttachmentRef,
  ChoiceOption,
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
    conversations,
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
        case 'tool_started':
          // 追加为 pending 步骤，轨迹整轮保留（体现 Agent 实际执行步骤）
          updateMessage(assistantId, (message) => ({
            ...message,
            toolTrace: [
              ...(message.toolTrace ?? []),
              { label: event.payload.label ?? event.payload.tool, pending: true },
            ],
          }));
          break;
        case 'tool_progress':
          // 轮询等待等场景：原地更新最后一个 pending 步骤的文案（如「等待分析完成 3/11」）
          updateMessage(assistantId, (message) => {
            if (!message.toolTrace?.length) return message;
            const trace = [...message.toolTrace];
            for (let i = trace.length - 1; i >= 0; i -= 1) {
              if (trace[i].pending) {
                trace[i] = { ...trace[i], label: event.payload.label };
                break;
              }
            }
            return { ...message, toolTrace: trace };
          });
          break;
        case 'tool_completed':
          updateMessage(assistantId, (message) => {
            if (!message.toolTrace?.length) return message;
            // 按顺序回填第一个未完成步骤为完成
            const trace = [...message.toolTrace];
            for (let i = 0; i < trace.length; i += 1) {
              if (trace[i].pending) {
                trace[i] = { ...trace[i], pending: false };
                break;
              }
            }
            return { ...message, toolTrace: trace };
          });
          break;
        case 'run_status':
          // WAITING_USER：附件选择等需用户决策的场景，置尾步骤完成提示等待
          if (event.payload.status === 'WAITING_USER') {
            updateMessage(assistantId, (message) => {
              if (!message.toolTrace?.length) return message;
              const trace = [...message.toolTrace];
              for (let i = 0; i < trace.length; i += 1) {
                if (trace[i].pending) {
                  trace[i] = { ...trace[i], pending: false };
                  break;
                }
              }
              return { ...message, toolTrace: trace };
            });
          }
          break;
        case 'error':
          updateMessage(assistantId, (message) => ({
            ...message,
            status: 'error',
            error: event.payload.message ?? '处理失败，请稍后重试',
          }));
          break;
        case 'done':
          updateMessage(assistantId, (message) => {
            // 收尾时兜底清掉仍未完成的步骤 spinner
            const trace = message.toolTrace?.map((s) =>
              s.pending ? { ...s, pending: false } : s
            );
            return {
              ...message,
              status: message.status === 'error' ? 'error' : 'done',
              toolTrace: trace,
            };
          });
          break;
      }
    },
    [updateMessage],
  );

  const runTurn = useCallback(
    async ({
      message,
      userContent,
      attachments = [],
      action,
      existingAssistantId,
    }: {
      message: string;
      userContent: string;
      attachments?: AttachmentRef[];
      action?: ActionSelected;
      /** 附件上传等前置阶段已插入气泡时，复用该助手消息而非再追加 */
      existingAssistantId?: string;
    }) => {
      // 无会话时先创建（Java System of Record），并同步到 Layout 会话列表。
      let conversationId = activeConversationId;
      if (conversationId === null) {
        try {
          const created: ConversationItem = await conversationApi.create();
          conversationId = created.id;
          onConversationCreated(created);
          // 标记跳过本次历史加载，避免刚追加的消息被空历史覆盖。
          skipHistoryLoadRef.current = created.id;
          selectConversation(created.id);
        } catch (err) {
          console.error('Failed to create conversation:', err);
          if (existingAssistantId) {
            updateMessage(existingAssistantId, (m) => ({
              ...m,
              status: 'error',
              error: '会话创建失败，请稍后重试',
            }));
            return;
          }
          return;
        }
      }

      const assistantId = existingAssistantId ?? nextId();
      if (!existingAssistantId) {
        setMessages((prev) => [
          ...prev,
          { id: nextId(), role: 'user', content: userContent, blocks: [], status: 'done' },
          { id: assistantId, role: 'assistant', content: '', blocks: [], status: 'streaming' },
        ]);
      }
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;
      try {
        await streamChat(
          message,
          (event) => handleEvent(assistantId, event),
          controller.signal,
          conversationId,
          attachments,
          action,
        );
      } catch (err) {
        if (controller.signal.aborted) {
          updateMessage(assistantId, (current) => ({ ...current, status: 'done' }));
        } else {
          updateMessage(assistantId, (current) => ({
            ...current,
            status: 'error',
            error: err instanceof Error ? err.message : '网络异常，请稍后重试',
          }));
        }
      } finally {
        abortRef.current = null;
        setStreaming(false);
        refreshConversations();
      }
    },
    [
      activeConversationId,
      handleEvent,
      onConversationCreated,
      refreshConversations,
      selectConversation,
      updateMessage,
    ],
  );

  const send = useCallback(
    async (text: string, attachment?: File) => {
      // 带附件时保留用户输入的文字，并把附件提示追加在其后（气泡与持久化历史保持一致）
      const userContent = attachment
        ? (text ? `${text}\n[简历附件：${attachment.name}]` : `上传了简历附件：${attachment.name}`)
        : text;

      if (!attachment) {
        await runTurn({ message: userContent, userContent });
        return;
      }

      // 乐观 UI：上传前先插入用户气泡与助手占位（活动行显示上传进度）
      const assistantId = nextId();
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'user', content: userContent, blocks: [], status: 'done' },
        { id: assistantId, role: 'assistant', content: '', blocks: [], status: 'streaming', toolTrace: [{ label: '正在上传并解析简历…', pending: true }] },
      ]);
      setStreaming(true);

      // 上传到 Java 简历库（文件不经 Agent，只传资源 id；分析异步进行）
      let attachments: AttachmentRef[] = [];
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
        updateMessage(assistantId, (m) => ({
          ...m,
          status: 'error',
          error: err instanceof Error && err.message !== '请求失败'
            ? `简历上传失败：${err.message}`
            : '简历上传失败，请重试',
          toolTrace: [],
        }));
        setStreaming(false);
        return;
      }

      // 复用已插入的气泡继续本轮对话（SSE 分析在 Copilot 内完成）
      await runTurn({
        message: text || '请帮我分析这份简历',
        userContent,
        attachments,
        existingAssistantId: assistantId,
      });
    },
    [runTurn, updateMessage],
  );

  const submitAction = useCallback(
    (option: ChoiceOption) => {
      // 文案仅用于可读的用户气泡和历史；Graph 只按结构化 action 确定性路由。
      void runTurn({
        message: option.label,
        userContent: `已选择：${option.label}`,
        action: {
          type: 'ACTION_SELECTED',
          action: option.action,
          payload: option.payload ?? {},
        },
      });
    },
    [runTurn],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const activeConversation = conversations.find((item) => item.id === activeConversationId);

  return (
    <div className="grid h-full min-w-0 grid-cols-1 overflow-hidden bg-[#fbfbfd] dark:bg-slate-950 xl:grid-cols-[minmax(0,1fr)_20rem]">
      <section className="flex min-w-0 flex-col overflow-hidden">
        <header className="flex h-16 shrink-0 items-center justify-between border-b border-slate-200/80 bg-white/90 px-6 backdrop-blur dark:border-slate-700 dark:bg-slate-900/90 lg:px-8">
          <div className="min-w-0">
            <h1 className="truncate font-display text-base font-bold text-slate-950 dark:text-white">
              {activeConversation?.title || 'Career Copilot'}
            </h1>
            <p className="mt-0.5 text-xs text-slate-400">
              {streaming ? '正在处理你的请求…' : 'Agent 求职工作台'}
            </p>
          </div>
          <div className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-500 shadow-sm dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300">
            <span className={`h-2 w-2 rounded-full ${streaming ? 'animate-pulse bg-amber-400' : 'bg-emerald-500'}`} />
            {streaming ? '运行中' : '已就绪'}
          </div>
        </header>

        <main className="relative flex-1 overflow-y-auto bg-[radial-gradient(circle_at_top,_rgba(99,102,241,0.06),_transparent_38%)] dark:bg-[radial-gradient(circle_at_top,_rgba(99,102,241,0.10),_transparent_38%)]">
          {loadingHistory ? (
            <div className="flex h-full items-center justify-center text-sm text-slate-400">
              加载对话中…
            </div>
          ) : (
            <MessageList
              messages={messages}
              actionDisabled={streaming}
              onActionSelect={submitAction}
              onQuickPrompt={(prompt) => void send(prompt)}
            />
          )}
        </main>

        <div className="shrink-0 border-t border-slate-200/60 bg-white/85 pt-3 backdrop-blur-xl dark:border-slate-700 dark:bg-slate-900/85">
          <Composer streaming={streaming} onSend={send} onCancel={cancel} />
        </div>
      </section>
      <ContextPanel messages={messages} />
    </div>
  );
}
