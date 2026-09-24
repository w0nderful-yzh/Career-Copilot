import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useOutletContext } from 'react-router-dom';
import { AlertCircle, RefreshCw, X } from 'lucide-react';
import { conversationApi, jobUploadApi, resumeUploadApi, streamChat } from '../api/agentChat';
import ConfirmDialog from '../components/ConfirmDialog';
import Composer, { type AttachmentKind } from '../components/copilot/Composer';
import ContextPanel from '../components/copilot/ContextPanel';
import InterviewWorkspace from '../components/copilot/InterviewWorkspace';
import MessageList from '../components/copilot/MessageList';
import type { CopilotOutletContext } from '../components/Layout';
import { ROUTES } from '../constants/routes';
import { FAILED_TURN_HINT, toMessageStatus } from '../utils/copilotTurnStatus';
import {
  countFollowingMessages,
  resolveJavaMessageId,
} from '../utils/copilotMessageReconcile';
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
    conversationsLoaded,
  } = useOutletContext<CopilotOutletContext>();
  const location = useLocation();
  const navigate = useNavigate();

  const [messages, setMessages] = useState<CopilotMessage[]>([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  // 历史加载失败：与「空会话」区分开，否则界面会渲染成新会话首屏，用户以为历史被清空
  const [historyError, setHistoryError] = useState<string | null>(null);
  // 失败重试用：递增以重新触发加载 effect
  const [historyReloadKey, setHistoryReloadKey] = useState(0);
  /**
   * 消息级操作（编辑 / 重新生成）的就地提示。
   *
   * 这些操作可能在半途失败（对账拿不到 Java 消息 id、截断被拒），
   * 既不能静默丢弃，也不该把整个消息区换成错误页——用一条可关闭的顶部提示如实说明。
   */
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  // 待确认的编辑：编辑中间消息会删除其后的对话，必须先让用户确认
  const [pendingEdit, setPendingEdit] = useState<{
    messageId: string;
    text: string;
    index: number;
    following: number;
  } | null>(null);
  // 会话绑定的活动 JD（Conversation Memory，P2-5；侧栏活跃资源展示用）
  const [boundJobId, setBoundJobId] = useState<number | null>(null);
  const [streaming, setStreaming] = useState(false);
  // Interview Mode（Interview Mode 重构）：null = 普通聊天；有值 = 中间区进入面试模式
  const [interviewMode, setInterviewMode] = useState<InterviewModeState | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // 已主动退出的面试会话：不再被旧的 interview_session 信号块自动拉回 Interview Mode。
  // 退出时会向会话追加一条「面试完成摘要」artifact，messages 因此变化，
  // 下面的自动进入 effect 会重新扫描到那个信号块 —— 不记录就会把用户又拽回面试模式（P4 待修正）。
  const closedInterviewSessionsRef = useRef<Set<string>>(new Set());
  /** 进行中的面试会话（P4-10）：发送消息时读取，避免把 interviewMode 塞进回调依赖 */
  const interviewModeRef = useRef<InterviewModeState | null>(null);
  // 面试模式变化时同步给 ref：发送消息的回调不能依赖 interviewMode（否则每次进出面试都要重建）
  useEffect(() => {
    interviewModeRef.current = interviewMode;
  }, [interviewMode]);
  /**
   * 侧栏画像刷新令牌（P4-6b）：面试报告完成时 +1。
   *
   * 报告落库会同时写入画像证据，侧栏若不重取就还在显示面试前的分数——
   * 用户刚看到「本场画像变化」，旁边的画像却纹丝不动，是最容易让人不信任的一类不一致。
   */
  const [profileRefreshToken, setProfileRefreshToken] = useState(0);
  useEffect(() => {
    if (interviewMode?.status === 'completed') {
      setProfileRefreshToken((token) => token + 1);
    }
  }, [interviewMode?.status]);

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
      regenerate = false,
    }: {
      message: string;
      userContent: string;
      attachments?: AttachmentRef[];
      action?: ActionSelected;
      /** 附件上传等前置阶段已插入气泡时，复用该助手消息而非再追加 */
      existingAssistantId?: string;
      /**
       * 重新生成语义：用户消息已在会话历史里，前端也已删掉旧的助手回复，
       * 因此本轮不追加用户气泡，后端也不得重复落库用户消息。
       */
      regenerate?: boolean;
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
        const userBubble: CopilotMessage = {
          id: nextId(),
          role: 'user',
          content: userContent,
          blocks: [],
          status: 'done',
        };
        const assistantBubble: CopilotMessage = {
          id: assistantId,
          role: 'assistant',
          content: '',
          blocks: [],
          status: 'streaming',
          retry: retryPayload,
        };
        setMessages((prev) => [
          ...prev,
          // 重新生成时不追加用户气泡：这一轮的提问已经在会话历史里
          ...(regenerate ? [] : [userBubble]),
          assistantBubble,
        ]);
      } else {
        // 复用已有气泡（附件上传成功后继续本轮）：同步刷新重发载荷
        updateMessage(assistantId, (m) => ({ ...m, retry: retryPayload }));
      }
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;
      try {
        await streamChat(message, (event) => handleEvent(assistantId, event), controller.signal, {
          conversationId,
          attachments,
          action,
          // P4-10：Interview Mode 里把进行中的会话告诉后端，
          // Copilot 才能回答「现在考到哪、还剩什么」而不是只能等结束后的报告
          activeInterviewSessionId: interviewModeRef.current?.sessionId,
          regenerate,
        });
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

  /**
   * 画像低分项「一键定向」（P4-6b）。
   *
   * 把用户的明确选择作为动作载荷交给提案节点：它能对上方向分类就强制进「重点 + 必要覆盖」，
   * 对不上会如实说明——不悄悄换成一个考不到的重点。
   */
  const startFocusInterview = useCallback(
    (skill: string) => {
      const text = `针对「${skill}」来一场定向面试`;
      void runTurn({
        message: text,
        userContent: text,
        action: {
          type: 'ACTION_SELECTED',
          action: 'START_INTERVIEW',
          payload: { focusSkill: skill },
        },
      });
    },
    [runTurn],
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

  /** 编辑确认后的实际执行：对齐 Java 消息 id → 截断 → 重发 */
  const applyEdit = useCallback(
    async (index: number, text: string) => {
      const conversationId = activeConversationId;
      if (conversationId === null) return;
      const original = messages[index];
      if (!original) return;

      const outcome = await resolveJavaMessageId({
        localMessages: messages,
        index,
        loadRemote: async () => (await conversationApi.getDetail(conversationId)).messages,
      }).catch((err: unknown) => {
        console.error('编辑前对账失败:', err);
        return { status: 'mismatch' as const, remoteCount: 0 };
      });

      if (outcome.status === 'ok') {
        try {
          await conversationApi.truncateFrom(conversationId, outcome.javaId);
        } catch (err) {
          console.error('截断历史消息失败:', err);
          setActionNotice('无法编辑这条消息：历史删除失败，请稍后重试');
          return;
        }
      } else if (outcome.status === 'mismatch') {
        setActionNotice('无法编辑这条消息：本地对话与已保存记录对不上，刷新页面后重试');
        return;
      } else if (outcome.remoteCount > index) {
        // 该条之前的部分已落库、这一条还没写完：等落库完成再编辑，避免删错
        setActionNotice('这条消息还没有保存完成，请稍后重试');
        return;
      }
      // remoteCount <= index：这一轮根本没落库（如请求未到达后端），无需截断

      // 本地同步截断，保证界面与 Java 一致；编辑后的内容作为新一轮发出
      setMessages((prev) => prev.slice(0, index));
      const payload = original.retry;
      if (payload?.attachment) {
        // 附件上传失败轮：资源 id 还不存在，按原始文件重走上传
        await send(text, payload.attachment.file, payload.attachment.kind);
        return;
      }
      // 编辑后按普通消息重发：不再沿用原来的 Action 载荷，
      // 否则界面上的文本与后端确定性动作会不一致
      await runTurn({
        message: text,
        userContent: text,
        attachments: payload?.attachments ?? [],
      });
    },
    [activeConversationId, messages, runTurn, send],
  );

  /**
   * 编辑已发送的用户消息：确认 → 截断 → 以新内容重发。
   *
   * 编辑中间消息会连带删除其后的对话（不可恢复），因此只要后面还有内容就先让用户确认；
   * 编辑最后一条不打扰。截断必须按 Java messageId 精确执行，见 utils/copilotMessageReconcile。
   */
  const submitEdit = useCallback(
    (messageId: string, text: string) => {
      if (streaming || activeConversationId === null) return;
      const index = messages.findIndex((message) => message.id === messageId);
      const target = messages[index];
      if (!target || target.role !== 'user') return;
      const following = countFollowingMessages(messages, index);
      if (following > 0) {
        setPendingEdit({ messageId, text, index, following });
        return;
      }
      void applyEdit(index, text);
    },
    [applyEdit, messages, streaming, activeConversationId],
  );

  /**
   * 重新生成某一轮回答（也用于失败 / 停止后的重试）。
   *
   * 与旧的「重新发送」不同：先把旧的助手回复从 Java 删掉，再以 regenerate 语义重跑，
   * 历史里不会再多出一条重复的提问。历史回放的轮次没有 retry 载荷，用前一条用户消息重建。
   */
  const regenerateTurn = useCallback(
    async (messageId: string) => {
      if (streaming || activeConversationId === null) return;
      const index = messages.findIndex((message) => message.id === messageId);
      const assistant = messages[index];
      if (!assistant || assistant.role !== 'assistant') return;
      const preceding = messages[index - 1];
      const payload: TurnRetryPayload | null = assistant.retry
        ?? (preceding?.role === 'user'
          ? { message: preceding.content, userContent: preceding.content, attachments: [] }
          : null);
      if (!payload) {
        setActionNotice('这一轮没有可复用的提问内容，无法重新生成');
        return;
      }

      const outcome = await resolveJavaMessageId({
        localMessages: messages,
        index,
        loadRemote: async () => (await conversationApi.getDetail(activeConversationId)).messages,
      }).catch((err: unknown) => {
        console.error('重新生成前对账失败:', err);
        return { status: 'mismatch' as const, remoteCount: 0 };
      });

      if (outcome.status === 'mismatch') {
        setActionNotice('无法重新生成：本地对话与已保存记录对不上，刷新页面后重试');
        return;
      }
      if (outcome.status === 'pending' && outcome.remoteCount >= index) {
        // 提问已落库、回答还没写完：等落库完成，避免把半截回复删成孤儿
        setActionNotice('这一轮还没有保存完成，请稍后重试');
        return;
      }

      if (outcome.status === 'ok') {
        try {
          await conversationApi.truncateFrom(activeConversationId, outcome.javaId);
        } catch (err) {
          console.error('截断历史回答失败:', err);
          setActionNotice('无法重新生成：历史删除失败，请稍后重试');
          return;
        }
      }

      if (outcome.status === 'pending') {
        // 整轮都没有落库（如请求未到达后端）：没有历史可删，按新一轮重发
        setMessages((prev) => prev.slice(0, Math.max(0, index - 1)));
        await runTurn({
          message: payload.message,
          userContent: payload.userContent,
          attachments: payload.attachments,
          action: payload.action,
        });
        return;
      }

      if (payload.attachment) {
        // 附件上传失败轮：Java 里没有这条助手消息，本地去掉占位后按原始文件重走上传
        setMessages((prev) => prev.filter((message) => message.id !== messageId));
        await send(payload.message, payload.attachment.file, payload.attachment.kind);
        return;
      }

      // 就地重置该助手气泡（不删了再加）：避免列表跳动与滚动位置丢失
      updateMessage(messageId, () => ({
        id: messageId,
        role: 'assistant',
        content: '',
        blocks: [],
        status: 'streaming',
        retry: payload,
      }));
      await runTurn({
        message: payload.message,
        userContent: payload.userContent,
        attachments: payload.attachments,
        action: payload.action,
        existingAssistantId: messageId,
        regenerate: true,
      });
    },
    [activeConversationId, messages, runTurn, send, streaming, updateMessage],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  /** 画像变化的追溯入口（P3 待收口）：跳到面试记录页并定位该场次 */
  const viewInterviewSession = useCallback(
    (sessionId: string) => {
      navigate(ROUTES.interviewHistory, { state: { highlightSessionId: sessionId } });
    },
    [navigate],
  );

  /**
   * 让 Copilot 复盘指定面试会话（REVIEW_INTERVIEW action）。
   *
   * 这是该 action 的真实前端入口：此前 Python 侧已实现 REVIEW_INTERVIEW 处理（读 Java
   * /interview/sessions/{id}/details 做逐题复盘），但前端从来没有地方触发它（P4 待修正）。
   * 复盘面向「已结束」的会话，因此先退出 Interview Mode 并登记为已退出。
   */
  const reviewInterview = useCallback(
    async (sessionId: string, title: string) => {
      closedInterviewSessionsRef.current.add(sessionId);
      setInterviewMode(null);
      // 同时留下完成摘要 artifact：与「完成并返回对话」保持一致的历史回放记录
      if (activeConversationId !== null) {
        const summaryContent = `✅ 模拟面试完成（${title}）。表现已写入能力画像，下面为你复盘。`;
        const assistantId = `interview_done_${Date.now()}`;
        setMessages((prev) => [
          ...prev,
          { id: assistantId, role: 'assistant', content: summaryContent, blocks: [], status: 'done' },
        ]);
        try {
          await fetch(`/api/agent/conversations/${activeConversationId}/messages`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              messages: [{ role: 'ASSISTANT', content: summaryContent, blocks: JSON.stringify([]) }],
            }),
          });
        } catch (err) {
          console.error('保存面试完成摘要失败:', err);
        }
      }
      await runTurn({
        message: '帮我复盘这次面试',
        userContent: '让 Copilot 复盘这次面试',
        action: {
          type: 'ACTION_SELECTED',
          action: 'REVIEW_INTERVIEW',
          payload: { sessionId },
        },
      });
    },
    [activeConversationId, runTurn],
  );

  // 面试记录页点「让 Copilot 复盘」会带 reviewSessionId 跳到 /copilot：
  // 等会话列表就绪后再发起（否则 activeConversationId 还是 null，runTurn 会误建新会话）。
  const pendingReviewSessionId =
    (location.state as { reviewSessionId?: string } | null)?.reviewSessionId ?? null;

  useEffect(() => {
    if (!pendingReviewSessionId || !conversationsLoaded) return;
    // 处理一次即清 state，避免重渲染/刷新重复发起
    navigate(ROUTES.copilot, { replace: true, state: null });
    void reviewInterview(pendingReviewSessionId, '指定面试');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingReviewSessionId, conversationsLoaded]);

  // 面试信号块到达 → 进入 Interview Mode（若已是同会话 mode 则保持，避免重复进入）
  useEffect(() => {
    const signal = latestInterviewSignal(messages);
    if (!signal) return;
    // 已退出的会话不再自动进入（面试已结束，不应把用户拽回面试模式）
    if (closedInterviewSessionsRef.current.has(signal.session_id)) return;
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
  const exitInterviewWithSummary = useCallback(async (mode: InterviewModeState) => {
    // 先登记已退出：追加摘要会让 messages 变化并重新扫描到信号块，
    // 不登记就会被自动拉回面试模式（见 closedInterviewSessionsRef 注释）
    closedInterviewSessionsRef.current.add(mode.sessionId);
    const summaryContent = `✅ 模拟面试完成（${mode.title}）。表现已写入能力画像，可让我复盘本次面试或再来一场。`;
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
            onExit={() => void exitInterviewWithSummary(interviewMode)}
            onReview={() => void reviewInterview(interviewMode.sessionId, interviewMode.title)}
            onViewSession={viewInterviewSession}
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
                  conversationId={activeConversationId}
                  actionDisabled={streaming}
                  onActionSelect={submitAction}
                  onQuickPrompt={(prompt) => void send(prompt)}
                  onRegenerate={(messageId) => void regenerateTurn(messageId)}
                  onSubmitEdit={submitEdit}
                />
              )}
            </main>

            <div className="shrink-0 border-t border-slate-200/60 bg-white/85 pt-3 backdrop-blur-xl dark:border-slate-700 dark:bg-slate-900/85">
              {/* 编辑 / 重新生成的就地失败提示：不静默，也不把消息区换成错误页 */}
              {actionNotice && (
                <div className="mx-auto mb-2 w-full max-w-4xl px-5 lg:px-8">
                  <div
                    role="alert"
                    className="flex items-start gap-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:bg-amber-900/30 dark:text-amber-300"
                  >
                    <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span className="min-w-0 flex-1 break-words">{actionNotice}</span>
                    <button
                      type="button"
                      onClick={() => setActionNotice(null)}
                      title="关闭提示"
                      className="shrink-0 rounded p-0.5 transition hover:bg-amber-100 dark:hover:bg-amber-900/50"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              )}
              <Composer streaming={streaming} onSend={send} onCancel={cancel} />
            </div>
          </>
        )}
      </section>
      <ContextPanel
        messages={messages}
        activeJobId={boundJobId}
        profileRefreshToken={profileRefreshToken}
        onStartFocusInterview={startFocusInterview}
      />

      {/* 编辑中间消息会删除其后的全部对话，删除不可恢复，必须先确认 */}
      <ConfirmDialog
        open={pendingEdit !== null}
        title="编辑这条消息？"
        message={`这条消息之后还有 ${pendingEdit?.following ?? 0} 条对话，重新发送后会一并删除，且不可恢复。`}
        confirmText="删除并重新发送"
        cancelText="取消"
        confirmVariant="danger"
        onCancel={() => setPendingEdit(null)}
        onConfirm={() => {
          const pending = pendingEdit;
          setPendingEdit(null);
          if (pending) void applyEdit(pending.index, pending.text);
        }}
      />
    </div>
  );
}
