import { useCallback, useRef, useState } from 'react';
import { streamChat } from '../api/agentChat';
import Composer from '../components/copilot/Composer';
import MessageList from '../components/copilot/MessageList';
import type { AgentBlock, CopilotMessage, StreamEvent } from '../types/copilot';

// Copilot Workspace：Agent 对话工作台
// 支持流式消息（SSE）、错误状态展示与取消请求

let messageSeq = 0;
function nextId(): string {
  messageSeq += 1;
  return `msg_${Date.now()}_${messageSeq}`;
}

export default function CopilotPage() {
  const [messages, setMessages] = useState<CopilotMessage[]>([]);
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const updateMessage = useCallback(
    (id: string, updater: (message: CopilotMessage) => CopilotMessage) => {
      setMessages((prev) =>
        prev.map((message) => (message.id === id ? updater(message) : message)),
      );
    },
    [],
  );

  const handleEvent = useCallback(
    (assistantId: string, event: StreamEvent) => {
      switch (event.type) {
        case 'block':
          // 结构化块：按白名单类型追加
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
    async (text: string) => {
      const assistantId = nextId();
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'user', content: text, blocks: [], status: 'done' },
        { id: assistantId, role: 'assistant', content: '', blocks: [], status: 'streaming' },
      ]);
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;
      try {
        await streamChat(text, (event) => handleEvent(assistantId, event), controller.signal);
      } catch (err) {
        // 取消请求：保留已生成内容，标记为完成；其他错误标记失败
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
      }
    },
    [handleEvent, updateMessage],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  return (
    <div className="flex h-[calc(100vh-1px)] flex-col">
      <header className="border-b border-slate-200 bg-white/80 px-6 py-3 backdrop-blur dark:border-slate-700 dark:bg-slate-900/80">
        <div className="mx-auto flex w-full max-w-3xl items-center justify-between">
          <h1 className="text-sm font-bold text-slate-800 dark:text-white">Career Copilot</h1>
          <span className="text-xs text-slate-400 dark:text-slate-500">Agent 工作台</span>
        </div>
      </header>

      <main className="flex-1 overflow-y-auto bg-slate-50/50 dark:bg-slate-900/50">
        <MessageList messages={messages} />
      </main>

      <Composer streaming={streaming} onSend={send} onCancel={cancel} />
    </div>
  );
}