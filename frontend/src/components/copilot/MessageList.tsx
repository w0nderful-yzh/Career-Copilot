import { useEffect, useRef } from 'react';
import {
  AlertCircle,
  BookOpenCheck,
  Bot,
  FileSearch,
  Loader2,
  MessagesSquare,
  Send,
  Sparkles,
  User,
} from 'lucide-react';
import type { ChoiceOption, CopilotMessage } from '../../types/copilot';
import BlockRenderer from './BlockRenderer';

// Copilot 消息列表：气泡渲染 + 流式光标 + 错误状态

function AssistantContent({
  message,
  actionDisabled,
  onActionSelect,
}: {
  message: CopilotMessage;
  actionDisabled: boolean;
  onActionSelect: (option: ChoiceOption) => void;
}) {
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
      {/* P1-2：工具执行轻量状态行 */}
      {message.status === 'streaming' && message.activity && (
        <div
          data-testid="tool-activity"
          className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500"
        >
          <Loader2 className="h-3 w-3 animate-spin" />
          {message.activity}
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
        <div className="mt-2 flex items-center gap-2 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600 dark:bg-red-900/30 dark:text-red-300">
          <AlertCircle className="h-3.5 w-3.5 shrink-0" />
          {message.error ?? '处理失败，请稍后重试'}
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
  actionDisabled,
  onActionSelect,
  onQuickPrompt,
}: {
  messages: CopilotMessage[];
  actionDisabled: boolean;
  onActionSelect: (option: ChoiceOption) => void;
  onQuickPrompt: (prompt: string) => void;
}) {
  const bottomRef = useRef<HTMLDivElement>(null);

  // 流式更新时自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

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

  return (
    <div className="mx-auto w-full max-w-4xl space-y-7 px-5 py-8 lg:px-8">
      {messages.map((message) => (
        <div
          key={message.id}
          className={`flex gap-3 ${message.role === 'user' ? 'flex-row-reverse' : ''}`}
        >
          <div
            className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
              message.role === 'user'
                ? 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                : 'bg-gradient-to-br from-primary-500 to-indigo-600 text-white'
            }`}
          >
            {message.role === 'user' ? <User className="h-4 w-4" /> : <Bot className="h-4 w-4" />}
          </div>
          <div
            className={`max-w-[80%] rounded-2xl px-4 py-3 ${
              message.role === 'user'
                ? 'rounded-tr-sm bg-white text-slate-800 shadow-sm ring-1 ring-slate-200'
                : 'rounded-tl-sm bg-white shadow-sm ring-1 ring-slate-100 dark:bg-slate-800 dark:ring-slate-700'
            }`}
          >
            <AssistantContent
              message={message}
              actionDisabled={actionDisabled}
              onActionSelect={onActionSelect}
            />
          </div>
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  );
}
