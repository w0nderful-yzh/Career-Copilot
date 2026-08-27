import { useEffect, useRef } from 'react';
import { AlertCircle, Bot, User } from 'lucide-react';
import type { CopilotMessage } from '../../types/copilot';
import BlockRenderer from './BlockRenderer';

// Copilot 消息列表：气泡渲染 + 流式光标 + 错误状态

function AssistantContent({ message }: { message: CopilotMessage }) {
  return (
    <div className="space-y-1">
      {message.content && (
        <div className="whitespace-pre-wrap break-words text-sm leading-relaxed text-slate-700 dark:text-slate-200">
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
      {message.blocks.map((block, index) => (
        <BlockRenderer key={`${message.id}-${index}`} block={block} />
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

export default function MessageList({ messages }: { messages: CopilotMessage[] }) {
  const bottomRef = useRef<HTMLDivElement>(null);

  // 流式更新时自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 px-6 text-center">
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-indigo-600 text-white shadow-lg">
          <Bot className="h-7 w-7" />
        </div>
        <h2 className="text-lg font-bold text-slate-800 dark:text-white">Career Copilot</h2>
        <p className="max-w-sm text-sm text-slate-500 dark:text-slate-400">
          告诉我你的求职目标，例如「我准备找 Java 后端实习，帮我看看怎么准备」，或直接问我最近复习得怎么样。
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-3xl space-y-6 px-4 py-6">
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
                ? 'rounded-tr-sm bg-primary-600 text-white dark:bg-primary-600'
                : 'rounded-tl-sm bg-white shadow-sm ring-1 ring-slate-100 dark:bg-slate-800 dark:ring-slate-700'
            }`}
          >
            <AssistantContent message={message} />
          </div>
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  );
}