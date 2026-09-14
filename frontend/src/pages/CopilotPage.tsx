import { useCallback, useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { conversationApi, jobUploadApi, resumeUploadApi, streamChat } from '../api/agentChat';
import Composer, { type AttachmentKind } from '../components/copilot/Composer';
import ContextPanel from '../components/copilot/ContextPanel';
import InterviewWorkspace from '../components/copilot/InterviewWorkspace';
import MessageList from '../components/copilot/MessageList';
import type { CopilotOutletContext } from '../components/Layout';
import { FAILED_TURN_HINT, toMessageStatus } from '../utils/copilotTurnStatus';
import type {
  ActionSelected,
  AgentBlock,
  AttachmentRef,
  ChoiceOption,
  ConversationDetail,
  ConversationItem,
  CopilotMessage,
  InterviewModeState,
  InterviewSessionBlock,
  StreamEvent,
  TurnRetryPayload,
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

/** 历史消息 → 前端消息模型（终态由 Java status 还原，缺省按正常完成处理） */
function toCopilotMessages(detail: ConversationDetail): CopilotMessage[] {
  return detail.messages.map((message) => {
    const status = toMessageStatus(message.status);
    return {
      id: `saved_${message.id}`,
      role: message.role === 'USER' ? 'user' : 'assistant',
      content: message.content,
      blocks: parseBlocks(message.blocks),
      status,
      // 历史回放没有实时错误详情，失败轮次给兜底文案
      error: status === 'error' ? FAILED_TURN_HINT : undefined,
    };
  });
}

/** 取消息流里最近一个 interview_session 信号块（Interview Mode 重构：进入 Interview Mode 的信号） */
function latestInterviewSignal(messages: CopilotMessage[]): InterviewSessionBlock | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    for (let j = messages[i].blocks.length - 1; j >= 0; j--) {
      const block = messages[i].blocks[j];
      if (block.type === 'interview_session') {
        return block;
      }
    }
  }
  return null;
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
  // 历史加载失败：与「空会话」区分开，否则界面会渲染成新会话首屏，用户以为历史被清空
  const [historyError, setHistoryError] = useState<string | null>(null);
  // 失败重试用：递增以重新触发加载 effect
  const [historyReloadKey, setHistoryReloadKey] = useState(0);
  // 会话绑定的活动 JD（Conversation Memory，P2-5；侧栏活跃资源展示用）
  const [boundJobId, setBoundJobId] = useState<number | null>(null);
  const [streaming, setStreaming] = useState(false);
  // Interview Mode（Interview Mode 重构）：null = 普通聊天；有值 = 中间区进入面试模式
  const [interviewMode, setInterviewMode] = useState<InterviewModeState | null>(null);
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
      setHistoryError(null);
      return;
    }
    // 刚创建的新会话：不加载历史，保留本次发送追加的消息
    if (skipHistoryLoadRef.current === activeConversationId) {
      skipHistoryLoadRef.current = null;
      return;
    }
    (async () => {
      setLoadingHistory(true);
      // 重试前先清掉上一次的错误，避免旧提示与新加载状态并存
      setHistoryError(null);
      try {
        const detail = await conversationApi.getDetail(activeConversationId);
        if (cancelled) return;
        setMessages(toCopilotMessages(detail));
        setBoundJobId(detail.activeJobId ?? null);
      } catch (err) {
        if (cancelled) return;
        // 明确记录失败态：此前只 console.error，messages 保持为空 →
        // 渲染出新会话首屏，用户会误以为历史丢了（P1 待收口）
        console.error('Failed to load conversation:', err);
        setMessages([]);
        setHistoryError(err instanceof Error ? err.message : '对话加载失败');
      } finally {
        if (!cancelled) setLoadingHistory(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [activeConversationId, historyReloadKey]);

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
      // 记下本轮原始请求：失败/停止后可原样重发（含附件与 Action 提交）
      const retryPayload: TurnRetryPayload = { message, userContent, attachments, action };
      if (!existingAssistantId) {
        setMessages((prev) => [
          ...prev,
          { id: nextId(), role: 'user', content: userContent, blocks: [], status: 'done' },
          {
            id: assistantId,
            role: 'assistant',
            content: '',
            blocks: [],
            status: 'streaming',
            retry: retryPayload,
          },
        ]);
      } else {
        // 复用已有气泡（附件上传成功后继续本轮）：同步刷新重发载荷
        updateMessage(assistantId, (m) => ({ ...m, retry: retryPayload }));
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
          // 用户主动「停止生成」：标为 stopped 而非 done，
          // 否则停止后与正常完成无法区分（Java 侧同步落 STOPPED）
          updateMessage(assistantId, (current) => ({ ...current, status: 'stopped' }));
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
    async (text: string, attachment?: File, attachmentKind: AttachmentKind = 'resume') => {
      if (!attachment) {
        await runTurn({ message: text, userContent: text });
        return;
      }

      // 带附件时保留用户输入的文字，并把附件提示追加在其后（气泡与持久化历史保持一致）
      const attachmentLabel = attachmentKind === 'job_description' ? 'JD 附件' : '简历附件';
      const userContent = text
        ? `${text}\n[${attachmentLabel}：${attachment.name}]`
        : attachmentKind === 'job_description'
          ? `上传了岗位 JD：${attachment.name}`
          : `上传了简历附件：${attachment.name}`;

      // 乐观 UI：上传前先插入用户气泡与助手占位（活动行显示上传进度）
      const assistantId = nextId();
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'user', content: userContent, blocks: [], status: 'done' },
        { id: assistantId, role: 'assistant', content: '', blocks: [], status: 'streaming', toolTrace: [{ label: attachmentKind === 'job_description' ? '正在上传并解析 JD…' : '正在上传并解析简历…', pending: true }] },
      ]);
      setStreaming(true);

      // 上传到对应库（文件不经 Agent，只传资源 id）
      let attachments: AttachmentRef[] = [];
      try {
        if (attachmentKind === 'job_description') {
          const result = await jobUploadApi.upload(attachment);
          attachments = [{ kind: 'job_description', jobId: result.id, filename: attachment.name }];
        } else {
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
        }
      } catch (err) {
        console.error('Failed to upload attachment:', err);
        updateMessage(assistantId, (m) => ({
          ...m,
          status: 'error',
          error: err instanceof Error && err.message !== '请求失败'
            ? `附件上传失败：${err.message}`
            : '附件上传失败，请重试',
          toolTrace: [],
          // 上传失败时资源 id 还不存在，重发需要原始文件（其余轮次用已有 id 即可）
          retry: {
            message: text,
            userContent,
            attachments: [],
            attachment: { file: attachment, kind: attachmentKind },
          },
        }));
        setStreaming(false);
        return;
      }

      // 复用已插入的气泡继续本轮对话（SSE 在 Copilot 内完成）
      const fallbackMessage = attachmentKind === 'job_description'
        ? '请帮我看看这份 JD'
        : '请帮我分析这份简历';
      await runTurn({
        message: text || fallbackMessage,
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

  /**
   * 失败/停止后重发本轮：复用消息上保存的原始请求重跑，用户无需重新输入。
   *
   * 语义是「新的一轮」——后端会一并落一条用户消息，界面与历史里会再出现一次该提问，
   * 与持久化结果保持一致（不做无痕重放）。若要「原地续写/重新生成」，需要后端提供
   * regenerate 语义（跳过 USER 落库），属后续独立改动。
   */
  const retryTurn = useCallback(
    (messageId: string) => {
      const payload = messages.find((message) => message.id === messageId)?.retry;
      if (!payload) return;
      if (payload.attachment) {
        // 附件上传失败轮：资源 id 尚不存在，按原始文件重走一遍上传
        void send(payload.message, payload.attachment.file, payload.attachment.kind);
        return;
      }
      void runTurn({
        message: payload.message,
        userContent: payload.userContent,
        attachments: payload.attachments,
        action: payload.action,
      });
    },
    [messages, runTurn, send],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  // 面试信号块到达 → 进入 Interview Mode（若已是同会话 mode 则保持，避免重复进入）
  useEffect(() => {
    const signal = latestInterviewSignal(messages);
    if (!signal) return;
    setInterviewMode((prev) => {
      if (prev && prev.sessionId === signal.session_id) return prev;
      const title = signal.direction_name || signal.skill_id || '模拟面试';
      return {
        sessionId: signal.session_id,
        status: 'starting',
        title,
        difficulty: signal.difficulty ?? null,
      };
    });
  }, [messages]);

  // 切换会话时退出 Interview Mode（Java 会话保留，可恢复）
  useEffect(() => {
    setInterviewMode(null);
  }, [activeConversationId]);

  // 面试完成 → 退出 Interview Mode，向会话写入一条轻量「面试完成摘要」artifact
  // （领域隔离：过程不写 conversation，只写结果；供历史回放与复盘）
  const exitInterviewWithSummary = useCallback(async (title: string) => {
    const summaryContent = `✅ 模拟面试完成（${title}）。表现已写入能力画像，可让我复盘本次面试或再来一场。`;
    if (activeConversationId !== null) {
      const assistantId = `interview_done_${Date.now()}`;
      setMessages((prev) => [
        ...prev,
        { id: assistantId, role: 'assistant', content: summaryContent, blocks: [], status: 'done' },
      ]);
      try {
        const res = await fetch(`/api/agent/conversations/${activeConversationId}/messages`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            messages: [{ role: 'ASSISTANT', content: summaryContent, blocks: JSON.stringify([]) }],
          }),
        });
        if (!res.ok) console.error('保存面试完成摘要失败:', res.status);
      } catch (err) {
        console.error('保存面试完成摘要失败:', err);
      }
    }
    setInterviewMode(null);
  }, [activeConversationId]);

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

        {interviewMode ? (
          <InterviewWorkspace
            mode={interviewMode}
            onChangeStatus={setInterviewMode}
            onExit={() => void exitInterviewWithSummary(interviewMode.title)}
          />
        ) : (
          <>
            <main className="relative flex-1 overflow-y-auto bg-[radial-gradient(circle_at_top,_rgba(99,102,241,0.06),_transparent_38%)] dark:bg-[radial-gradient(circle_at_top,_rgba(99,102,241,0.10),_transparent_38%)]">
              {loadingHistory ? (
                <div className="flex h-full items-center justify-center text-sm text-slate-400">
                  加载对话中…
                </div>
              ) : historyError ? (
                /* 加载失败必须与「空会话」区分：否则会显示新会话首屏，像是历史被清空 */
                <div className="flex h-full items-center justify-center px-6">
                  <div className="w-full max-w-md rounded-2xl border border-red-200 bg-red-50/70 px-5 py-4 text-center dark:border-red-900/50 dark:bg-red-900/20">
                    <AlertCircle className="mx-auto h-5 w-5 text-red-500" />
                    <p className="mt-2 text-sm font-medium text-red-700 dark:text-red-300">
                      对话加载失败
                    </p>
                    <p className="mt-1 break-words text-xs text-red-600/80 dark:text-red-300/80">
                      {historyError}
                    </p>
                    <button
                      type="button"
                      onClick={() => setHistoryReloadKey((key) => key + 1)}
                      className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-red-700"
                    >
                      <RefreshCw className="h-3.5 w-3.5" />
                      重试
                    </button>
                  </div>
                </div>
              ) : (
                <MessageList
                  messages={messages}
                  actionDisabled={streaming}
                  onActionSelect={submitAction}
                  onQuickPrompt={(prompt) => void send(prompt)}
                  onRetry={retryTurn}
                />
              )}
            </main>

            <div className="shrink-0 border-t border-slate-200/60 bg-white/85 pt-3 backdrop-blur-xl dark:border-slate-700 dark:bg-slate-900/85">
              <Composer streaming={streaming} onSend={send} onCancel={cancel} />
            </div>
          </>
        )}
      </section>
      <ContextPanel messages={messages} activeJobId={boundJobId} />
    </div>
  );
}
