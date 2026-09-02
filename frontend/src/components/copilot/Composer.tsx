import { useRef, useState } from 'react';
import { BriefcaseBusiness, FileText, Paperclip, Send, Square, X } from 'lucide-react';

// Copilot 输入栏：发送消息 / 停止（取消）当前流式响应
// 支持拖入或选择 PDF 附件（简历或 JD，发送前可切换类型），发送时由外层上传到对应库

export type AttachmentKind = 'resume' | 'job_description';

interface ComposerProps {
  streaming: boolean;
  onSend: (message: string, attachment?: File, attachmentKind?: AttachmentKind) => void;
  onCancel: () => void;
  disabled?: boolean;
}

const ACCEPTED_TYPES = ['application/pdf'];

function isPdf(file: File): boolean {
  return ACCEPTED_TYPES.includes(file.type) || file.name.toLowerCase().endsWith('.pdf');
}

const KIND_META = {
  resume: {
    label: '简历',
    hint: '加入简历库并后台分析，Agent 会引导下一步',
    icon: FileText,
    tagClass: 'bg-blue-50 text-blue-600 dark:bg-blue-950/50 dark:text-blue-300',
  },
  job_description: {
    label: 'JD',
    hint: '作为目标岗位 JD，可定向优化简历 / 匹配分析 / 出题面试',
    icon: BriefcaseBusiness,
    tagClass: 'bg-amber-50 text-amber-600 dark:bg-amber-950/50 dark:text-amber-300',
  },
} as const;

export default function Composer({ streaming, onSend, onCancel, disabled }: ComposerProps) {
  const [value, setValue] = useState('');
  const [attachment, setAttachment] = useState<File | null>(null);
  const [attachmentKind, setAttachmentKind] = useState<AttachmentKind>('resume');
  const [dragging, setDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const meta = KIND_META[attachmentKind];

  const submit = () => {
    const message = value.trim();
    if ((!message && !attachment) || streaming) return;
    onSend(message, attachment ?? undefined, attachmentKind);
    setValue('');
    setAttachment(null);
    setAttachmentKind('resume');
  };

  const handleFile = (file: File | undefined) => {
    if (!file) return;
    if (!isPdf(file)) {
      window.alert('暂只支持 PDF 附件（简历或 JD）');
      return;
    }
    setAttachment(file);
  };

  return (
    <div className="mx-auto w-full max-w-4xl px-5 pb-4 lg:px-8">
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          handleFile(event.dataTransfer.files?.[0]);
        }}
        className={`rounded-2xl border p-2.5 shadow-[0_12px_35px_rgba(15,23,42,0.08)] transition dark:shadow-none ${
          dragging
            ? 'border-primary-400 bg-primary-50/50 dark:bg-primary-900/20'
            : 'border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-800'
        }`}
      >
        {attachment && (
          <div className="mb-2 flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-700/50">
            <meta.icon className="h-4 w-4 shrink-0 text-primary-500" />
            <span className="min-w-0 flex-1 truncate text-sm text-slate-700 dark:text-slate-200">
              {attachment.name}
            </span>
            {/* 附件类型标记：上传前可切换简历/JD（决定走简历库还是 JD 库） */}
            <div className="flex shrink-0 items-center rounded-lg border border-slate-200 bg-white p-0.5 dark:border-slate-600 dark:bg-slate-800">
              {(Object.keys(KIND_META) as AttachmentKind[]).map((kind) => {
                const kindMeta = KIND_META[kind];
                const KindIcon = kindMeta.icon;
                const active = attachmentKind === kind;
                return (
                  <button
                    key={kind}
                    type="button"
                    onClick={() => setAttachmentKind(kind)}
                    className={`flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium transition ${
                      active
                        ? kindMeta.tagClass
                        : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-300'
                    }`}
                  >
                    <KindIcon className="h-3 w-3" />
                    {kindMeta.label}
                  </button>
                );
              })}
            </div>
            <button
              onClick={() => setAttachment(null)}
              title="移除附件"
              className="shrink-0 rounded p-1 text-slate-400 hover:bg-slate-200 hover:text-red-500 dark:hover:bg-slate-600"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        )}

        <div className="flex items-end gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept="application/pdf,.pdf"
            className="hidden"
            onChange={(event) => handleFile(event.target.files?.[0] ?? undefined)}
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            title="添加附件（PDF 简历或 JD）"
            disabled={disabled}
            className="mb-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-slate-400 transition hover:bg-slate-100 hover:text-primary-600 dark:hover:bg-slate-700 dark:hover:text-primary-400"
          >
            <Paperclip className="h-4 w-4" />
          </button>
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
            className="max-h-32 min-h-[2.5rem] flex-1 resize-none bg-transparent px-2 py-1.5 text-sm leading-6 text-slate-800 outline-none placeholder:text-slate-400 dark:text-white dark:placeholder:text-slate-500"
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
              disabled={!value.trim() && !attachment}
              title="发送"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-950 text-white shadow-md transition hover:-translate-y-0.5 hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-slate-950 dark:hover:bg-primary-400"
            >
              <Send className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>
      <p className="mt-2 text-center text-xs text-slate-400 dark:text-slate-500">
        {attachment ? meta.hint : '支持拖入 PDF 简历或岗位 JD，Agent 会引导下一步'}
      </p>
    </div>
  );
}
