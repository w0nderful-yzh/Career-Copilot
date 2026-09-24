import { useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  BookOpenCheck,
  Bot,
  Check,
  Copy,
  FileSearch,
  Loader2,
  MessagesSquare,
  Pencil,
  RefreshCw,
  Send,
  Sparkles,
  Square,
  User,
} from 'lucide-react';
import type { ChoiceOption, CopilotMessage } from '../../types/copilot';
import BlockRenderer from './BlockRenderer';
import {getCopilotTurnFailure} from '../../utils/copilotTurnStatus';
import { useAutoGrowTextarea } from '../../hooks/useAutoGrowTextarea';
import {
  IDLE_COMPOSITION,
  isComposingEvent,
  shouldSendOnEnter,
  toComposerKeyEvent,
  type CompositionState,
} from '../../utils/composerKeyboard';

// Copilot 消息列表：气泡渲染 + 流式光标 + 错误/停止状态 + 消息级操作（复制 / 编辑 / 重新生成）

/** 消息内联编辑器最大高度（px），与输入框保持同一套行为 */
const MAX_EDITOR_HEIGHT = 200;

/** 距底部多少像素内仍视为「跟随最新消息」 */
const STICK_TO_BOTTOM_THRESHOLD = 120;

/**
 * 找到最近的可滚动祖先。
 *
 * 滚动容器是页面里的 `<main>`（消息列表自己不持有滚动条），
 * 这里往上找而不是把 ref 层层传下来：调用方只需渲染 MessageList。
 */
function findScrollContainer(element: HTMLElement | null): HTMLElement | null {
  let current = element?.parentElement ?? null;
  while (current) {
    const overflowY = window.getComputedStyle(current).overflowY;
    if (overflowY === 'auto' || overflowY === 'scroll') return current;
    current = current.parentElement;
  }
  return null;
}

/**
 * 重新生成入口。
 *
 * 语义是「重做这一轮」而不是「再发一遍」：调用方会先按 messageId 截断掉旧的助手回复，
 * 再以 regenerate 语义重跑，历史里不会多出一条重复的提问。
 */
function RegenerateButton({ disabled, onClick }: { disabled: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title="重新生成这一轮回答（不会重复提问）"
      className="inline-flex shrink-0 items-center gap-1 rounded-md border border-current px-2 py-0.5 font-medium transition hover:bg-black/5 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-white/10"
    >
      <RefreshCw className="h-3 w-3" />
      重新生成
    </button>
  );
}

/** 消息级操作按钮：hover / 聚焦时出现，移动端常驻 */
function MessageAction({
  icon: Icon,
  label,
  title,
  onClick,
}: {
  icon: typeof Copy;
  label: string;
  title: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 transition hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-700 dark:hover:text-slate-200"
    >
      <Icon className="h-3 w-3" />
      {label}
    </button>
  );
}

/**
 * 用户消息的内联编辑器。
 *
 * 键盘行为与主输入框一致（Enter 保存并重发、Shift+Enter 换行、Esc 取消），
 * 合成判定复用 composerKeyboard，避免中文选词时误提交。
 */
function MessageEditor({
  initialValue,
  onCancel,
  onSubmit,
}: {
  initialValue: string;
  onCancel: () => void;
  onSubmit: (text: string) => void;
}) {
  const [draft, setDraft] = useState(initialValue);
  const textareaRef = useAutoGrowTextarea(draft, MAX_EDITOR_HEIGHT);
  const compositionRef = useRef<CompositionState>(IDLE_COMPOSITION);
  const trimmed = draft.trim();

  useEffect(() => {
    // 进入编辑态即聚焦并把光标放到末尾，减少一次点击
    const element = textareaRef.current;
    if (!element) return;
    element.focus();
    element.setSelectionRange(element.value.length, element.value.length);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const keyEvent = toComposerKeyEvent(event);
    const composing = isComposingEvent(keyEvent, compositionRef.current);
    if (event.key === 'Escape' && !composing) {
      event.preventDefault();
      onCancel();
      return;
    }
    if (shouldSendOnEnter(keyEvent, compositionRef.current)) {
      event.preventDefault();
      if (trimmed) onSubmit(trimmed);
    }
  };

  return (
    <div className="w-[22rem] max-w-[70vw]">
      <textarea
        ref={textareaRef}
        data-testid="message-editor"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={handleKeyDown}
        onCompositionStart={() => {
          compositionRef.current = { composing: true, endedAt: 0 };
        }}
        onCompositionEnd={(event) => {
          compositionRef.current = {
            composing: false,
            endedAt: event.nativeEvent ? event.nativeEvent.timeStamp : 0,
          };
        }}
        rows={1}
        className="w-full resize-none rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm leading-6 text-slate-800 outline-none focus:border-primary-400 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100"
      />
      <div className="mt-2 flex flex-wrap items-center justify-end gap-2 text-xs">
        <span className="mr-auto text-slate-400">Enter 保存并重发 · Shift+Enter 换行 · Esc 取消</span>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-md border border-slate-200 px-2.5 py-1 font-medium text-slate-500 transition hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          取消
        </button>
        <button
          type="button"
          onClick={() => trimmed && onSubmit(trimmed)}
          disabled={!trimmed}
          className="rounded-md bg-slate-950 px-2.5 py-1 font-semibold text-white transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-slate-950 dark:hover:bg-primary-400"
        >
          保存并重新发送
        </button>
      </div>
    </div>
  );
}

function AssistantContent({
  message,
  actionDisabled,
  onActionSelect,
  onRegenerate,
}: {
  message: CopilotMessage;
  actionDisabled: boolean;
  onActionSelect: (option: ChoiceOption) => void;
  onRegenerate?: (messageId: string) => void;
}) {
  const turnFailure = getCopilotTurnFailure(message.status, message.error);
  return (
    <div className="space-y-1">
      {message.content && (
        <div className={`whitespace-pre-wrap break-words text-sm leading-relaxed ${
          message.role === 'user' ? 'text-slate-800' : 'text-slate-700 dark:text-slate-200'
        }`}>
          {message.content}
          {message.status === 'streaming' && (
            <span className="ml-0.5 inline-block h-4 w-0.5 animate-pulse bg-primary-500 align-middle" />
          )}
        </div>
      )}
      {message.status === 'streaming' && !message.content && (
        <div className="flex items-center gap-2 text-sm text-slate-400">
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-300" />
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-300 [animation-delay:0.15s]" />
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-300 [animation-delay:0.3s]" />
        </div>
      )}
      {/* P1-2：工具执行轨迹（每个已完成的工具依次保留，流式结束后整行隐藏） */}
      {message.toolTrace && message.toolTrace.length > 0 && (
        <div
          data-testid="tool-activity"
          className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-400 dark:text-slate-500"
        >
          {message.toolTrace.map((step, i) => {
            const running = message.status === 'streaming' && i === message.toolTrace!.length - 1 && step.pending;
            return (
              <span key={`${step.label}-${i}`} className="inline-flex items-center gap-1">
                {i > 0 && <span aria-hidden>·</span>}
                {running ? (
                  <Loader2 className="h-3 w-3 animate-spin" />
                ) : (
                  <Check className="h-3 w-3 text-emerald-500" />
                )}
                {step.label}
              </span>
            );
          })}
        </div>
      )}
      {message.blocks.map((block, index) => (
        <BlockRenderer
          key={`${message.id}-${index}`}
          block={block}
          actionDisabled={actionDisabled}
          onActionSelect={onActionSelect}
        />
      ))}
      {message.status === 'error' && (
        <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600 dark:bg-red-900/30 dark:text-red-300">
          <AlertCircle className="h-3.5 w-3.5 shrink-0" />
          <span className="min-w-0 flex-1">{turnFailure?.message}</span>
          {turnFailure?.retryable && onRegenerate && (
            <RegenerateButton disabled={actionDisabled} onClick={() => onRegenerate(message.id)} />
          )}
        </div>
      )}
      {/* 停止生成：中性提示而非错误提示——用户主动中断不是故障（P1 待收口） */}
      {message.status === 'stopped' && (
        <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500 dark:bg-slate-700/40 dark:text-slate-400">
          <Square className="h-3 w-3 shrink-0" />
          <span className="min-w-0 flex-1">
            {message.content ? '已停止生成，以上为已产出的部分' : '已停止生成，本轮未产出内容'}
          </span>
          {onRegenerate && (
            <RegenerateButton disabled={actionDisabled} onClick={() => onRegenerate(message.id)} />
          )}
        </div>
      )}
    </div>
  );
}

const QUICK_ACTIONS = [
  {
    label: '查看简历',
    description: '了解已有简历与分析结果',
    prompt: '帮我看看已有的简历和最近分析结果',
    icon: FileSearch,
    accent: 'bg-blue-50 text-blue-600 dark:bg-blue-950/50 dark:text-blue-300',
  },
  {
    label: '复盘面试',
    description: '总结最近模拟面试表现',
    prompt: '帮我复盘最近的模拟面试表现',
    icon: MessagesSquare,
    accent: 'bg-orange-50 text-orange-600 dark:bg-orange-950/50 dark:text-orange-300',
  },
  {
    label: '知识问答',
    description: '基于个人知识库检索回答',
    prompt: '我想基于知识库复习一个技术问题',
    icon: BookOpenCheck,
    accent: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/50 dark:text-emerald-300',
  },
  {
    label: '开始面试',
    description: '配置一场针对性模拟面试',
    prompt: '我想开始一场模拟面试',
    icon: Sparkles,
    accent: 'bg-violet-50 text-violet-600 dark:bg-violet-950/50 dark:text-violet-300',
  },
] as const;

export default function MessageList({
  messages,
  conversationId,
  actionDisabled,
  onActionSelect,
  onQuickPrompt,
  onRegenerate,
  onSubmitEdit,
}: {
  messages: CopilotMessage[];
  /** 当前会话 id：切换会话时要把滚动位置重置到底部 */
  conversationId?: number | null;
  actionDisabled: boolean;
  onActionSelect: (option: ChoiceOption) => void;
  onQuickPrompt: (prompt: string) => void;
  /** 重新生成某一轮回答（调用方负责截断旧回复并以 regenerate 语义重跑） */
  onRegenerate?: (messageId: string) => void;
  /** 提交编辑后的用户消息（调用方负责确认、截断与重发） */
  onSubmitEdit?: (messageId: string, text: string) => void;
}) {
  const bottomRef = useRef<HTMLDivElement>(null);
  /**
   * 是否跟随最新消息。
   *
   * 消息变化不只来自新消息：编辑中间消息、截断历史、流式增量都会改 messages，
   * 每次变化都把用户拽回底部会让人没法回看。上翻即暂停跟随，滚回底部附近自动恢复。
   */
  const stickToBottomRef = useRef(true);
  // 正在编辑的用户消息 id（同一时刻只允许编辑一条，避免并发截断互相踩踏）
  const [editingId, setEditingId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const hasMessages = messages.length > 0;
  const lastMessageStreaming = messages[messages.length - 1]?.status === 'streaming';

  // 切换会话：历史整批替换，用户期望直接落在最新位置（保留上一个会话的滚动状态会停在开头）
  useEffect(() => {
    stickToBottomRef.current = true;
  }, [conversationId]);

  // 记录滚动位置是否在底部附近
  useEffect(() => {
    if (!hasMessages) return;
    const container = findScrollContainer(bottomRef.current);
    if (!container) return;
    const handleScroll = () => {
      const distance = container.scrollHeight - container.scrollTop - container.clientHeight;
      stickToBottomRef.current = distance <= STICK_TO_BOTTOM_THRESHOLD;
    };
    container.addEventListener('scroll', handleScroll, { passive: true });
    return () => container.removeEventListener('scroll', handleScroll);
  }, [hasMessages]);

  // 跟随最新消息；流式期间用即时滚动，避免每个增量都排队一次平滑动画
  useEffect(() => {
    if (!hasMessages || !stickToBottomRef.current) return;
    bottomRef.current?.scrollIntoView({ behavior: lastMessageStreaming ? 'auto' : 'smooth' });
  }, [messages, hasMessages, lastMessageStreaming]);

  const copyMessage = async (message: CopilotMessage) => {
    if (!message.content) return;
    try {
      await navigator.clipboard.writeText(message.content);
      setCopiedId(message.id);
      window.setTimeout(
        () => setCopiedId((current) => (current === message.id ? null : current)),
        1500,
      );
    } catch (err) {
      // 剪贴板不可用（非安全上下文 / 无权限）：只记录，不打断阅读
      console.error('复制消息失败:', err);
    }
  };

  if (messages.length === 0) {
    return (
      <div className="relative flex min-h-full items-center justify-center overflow-hidden px-6 py-12 text-center">
        <div className="pointer-events-none absolute left-1/2 top-20 h-64 w-64 -translate-x-1/2 rounded-full bg-primary-200/25 blur-3xl dark:bg-primary-900/20" />
        <div className="relative w-full max-w-3xl">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-[20px] bg-slate-950 text-white shadow-xl shadow-slate-300/40 dark:bg-white dark:text-slate-950 dark:shadow-none">
            <Bot className="h-8 w-8" />
          </div>
          <p className="mt-6 text-xs font-bold uppercase tracking-[0.24em] text-primary-600 dark:text-primary-400">
            Career workspace
          </p>
          <h2 className="mt-2 font-display text-3xl font-bold tracking-tight text-slate-950 dark:text-white sm:text-4xl">
            今天想为求职推进哪一步？
          </h2>
          <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-slate-500 dark:text-slate-400">
            描述你的目标，或直接拖入 PDF 简历。Copilot 会读取真实业务数据，并把下一步操作交给你确认。
          </p>

          <div className="mt-8 grid gap-3 text-left sm:grid-cols-2">
            {QUICK_ACTIONS.map((action) => (
              <button
                key={action.label}
                type="button"
                onClick={() => onQuickPrompt(action.prompt)}
                className="group flex items-center gap-3 rounded-2xl border border-slate-200/80 bg-white/85 px-4 py-4 shadow-sm backdrop-blur transition hover:-translate-y-0.5 hover:border-slate-300 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 dark:border-slate-700 dark:bg-slate-800/80 dark:hover:border-slate-600"
              >
                <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${action.accent}`}>
                  <action.icon className="h-5 w-5" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-bold text-slate-800 dark:text-slate-100">
                    {action.label}
                  </span>
                  <span className="mt-0.5 block text-xs text-slate-400 dark:text-slate-500">
                    {action.description}
                  </span>
                </span>
                <Send className="h-4 w-4 text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-primary-500" />
              </button>
            ))}
          </div>
        </div>
      </div>
    );
  }

  const lastIndex = messages.length - 1;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-7 px-5 py-8 lg:px-8">
      {messages.map((message, index) => {
        const isUser = message.role === 'user';
        const editing = editingId === message.id;
        const canEdit = isUser && !actionDisabled && Boolean(onSubmitEdit);
        // 只允许重新生成最后一条回答：改中间轮次会连带删除其后全部消息，
        // 用户几乎不会预期这种破坏，也不便判断结果
        const isLastAssistant = !isUser
          && index === lastIndex
          && message.status !== 'streaming'
          && !actionDisabled
          && Boolean(onRegenerate);
        // 失败 / 停止轮已经在提示条里给了「重新生成」，操作区不再重复放一个同名按钮
        const canRegenerate = isLastAssistant && message.status === 'done';
        const showActions = !editing && (message.content || canEdit || canRegenerate);

        return (
          <div
            key={message.id}
            className={`group flex gap-3 ${isUser ? 'flex-row-reverse' : ''}`}
          >
            <div
              className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
                isUser
                  ? 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                  : 'bg-gradient-to-br from-primary-500 to-indigo-600 text-white'
              }`}
            >
              {isUser ? <User className="h-4 w-4" /> : <Bot className="h-4 w-4" />}
            </div>
            <div
              className={`max-w-[80%] rounded-2xl px-4 py-3 ${
                isUser
                  ? 'rounded-tr-sm bg-white text-slate-800 shadow-sm ring-1 ring-slate-200'
                  : 'rounded-tl-sm bg-white shadow-sm ring-1 ring-slate-100 dark:bg-slate-800 dark:ring-slate-700'
              }`}
            >
              {editing && onSubmitEdit ? (
                <MessageEditor
                  initialValue={message.content}
                  onCancel={() => setEditingId(null)}
                  onSubmit={(text) => {
                    setEditingId(null);
                    onSubmitEdit(message.id, text);
                  }}
                />
              ) : (
                <AssistantContent
                  message={message}
                  actionDisabled={actionDisabled}
                  onActionSelect={onActionSelect}
                  onRegenerate={isLastAssistant ? onRegenerate : undefined}
                />
              )}
              {/* 操作区：hover / 键盘聚焦时出现；移动端没有 hover，常驻显示 */}
              {showActions && (
                <div
                  className={`mt-1.5 flex flex-wrap items-center gap-1 text-xs text-slate-400 opacity-0 transition focus-within:opacity-100 group-hover:opacity-100 max-sm:opacity-100 ${
                    isUser ? 'justify-end' : ''
                  }`}
                >
                  {message.content && (
                    <MessageAction
                      icon={copiedId === message.id ? Check : Copy}
                      label={copiedId === message.id ? '已复制' : '复制'}
                      title="复制这条消息"
                      onClick={() => void copyMessage(message)}
                    />
                  )}
                  {canEdit && (
                    <MessageAction
                      icon={Pencil}
                      label="编辑"
                      title="编辑后重新发送（其后的对话会被删除）"
                      onClick={() => setEditingId(message.id)}
                    />
                  )}
                  {canRegenerate && onRegenerate && (
                    <MessageAction
                      icon={RefreshCw}
                      label="重新生成"
                      title="重新生成这一轮回答（不会重复提问）"
                      onClick={() => onRegenerate(message.id)}
                    />
                  )}
                </div>
              )}
            </div>
          </div>
        );
      })}
      <div ref={bottomRef} />
    </div>
  );
}
