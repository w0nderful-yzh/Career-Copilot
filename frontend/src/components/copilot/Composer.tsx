import { useState } from 'react';
import { Send, Square } from 'lucide-react';

// Copilot 输入栏：发送消息 / 停止（取消）当前流式响应

interface ComposerProps {
  streaming: boolean;
  onSend: (message: string) => void;
  onCancel: () => void;
  disabled?: boolean;
}

export default function Composer({ streaming, onSend, onCancel, disabled }: ComposerProps) {
  const [value, setValue] = useState('');

  const submit = () => {
    const message = value.trim();
    if (!message || streaming) return;
    setValue('');
    onSend(message);
  };

  return (
    <div className="mx-auto w-full max-w-3xl px-4 pb-4">
      <div className="flex items-end gap-2 rounded-2xl border border-slate-200 bg-white p-2 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <textarea
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            // Enter 发送，Shift+Enter 换行
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
          rows={1}
          placeholder={streaming ? 'Copilot 正在思考…' : '输入你的目标或问题…'}
          disabled={disabled}
          className="max-h-32 min-h-[2.5rem] flex-1 resize-none bg-transparent px-2 py-1.5 text-sm text-slate-800 outline-none placeholder:text-slate-400 dark:text-white dark:placeholder:text-slate-500"
        />
        {streaming ? (
          <button
            onClick={onCancel}
            title="停止生成"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-red-500 text-white transition hover:bg-red-600"
          >
            <Square className="h-4 w-4" />
          </button>
        ) : (
          <button
            onClick={submit}
            disabled={!value.trim()}
            title="发送"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary-600 text-white transition hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-primary-500 dark:hover:bg-primary-600"
          >
            <Send className="h-4 w-4" />
          </button>
        )}
      </div>
      <p className="mt-2 text-center text-xs text-slate-400 dark:text-slate-500">
        Career Copilot 会结合你的简历、面试记录与知识库回答，建议均由你点击确认后执行
      </p>
    </div>
  );
}