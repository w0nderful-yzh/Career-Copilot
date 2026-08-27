import { Plus, Trash2 } from 'lucide-react';
import type { ConversationItem } from '../../types/copilot';

// Copilot 会话列表面板：渲染在全局 Layout 最左侧栏（/copilot 时），避免双层侧栏
// 仅负责列表展示，会话状态由 Layout 统一管理

interface SessionListProps {
  conversations: ConversationItem[];
  activeConversationId: number | null;
  loading: boolean;
  onNew: () => void;
  onSelect: (conversationId: number) => void;
  onDelete: (conversationId: number) => void;
}

function formatRelativeTime(iso: string): string {
  const date = new Date(iso);
  const diffMs = Date.now() - date.getTime();
  const minutes = Math.floor(diffMs / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} 天前`;
  return date.toLocaleDateString('zh-CN');
}

export default function SessionList({
  conversations,
  activeConversationId,
  loading,
  onNew,
  onSelect,
  onDelete,
}: SessionListProps) {
  return (
    <div>
      <div className="px-2 pb-4 pt-1">
        <button
          onClick={onNew}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 px-3 py-2.5 text-sm font-semibold text-white shadow-lg shadow-primary-500/20 transition hover:-translate-y-0.5 hover:bg-primary-700 dark:bg-primary-500 dark:hover:bg-primary-600"
        >
          <Plus className="h-4 w-4" />
          新对话
        </button>
      </div>

      <div className="px-2 pb-2">
        <div className="mb-1 px-3">
          <span className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
            最近对话
          </span>
        </div>
        {loading ? (
          <p className="px-3 py-2 text-xs text-slate-400">加载中…</p>
        ) : conversations.length === 0 ? (
          <p className="px-3 py-2 text-xs text-slate-400 dark:text-slate-500">
            还没有对话，开始你的第一段对话吧
          </p>
        ) : (
          <ul className="space-y-1">
            {conversations.map((conversation) => {
              const active = conversation.id === activeConversationId;
              return (
                <li key={conversation.id}>
                  <div className="group relative">
                    <button
                      type="button"
                      onClick={() => onSelect(conversation.id)}
                      className={`flex w-full items-center gap-2 rounded-xl px-3 py-2.5 pr-9 text-left transition ${
                      active
                        ? 'bg-primary-50 text-primary-700 dark:bg-primary-900/30 dark:text-primary-300'
                        : 'text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800'
                    }`}
                    >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">
                        {conversation.title}
                      </p>
                      <p className="mt-0.5 text-xs text-slate-400 dark:text-slate-500">
                        {conversation.messageCount} 条 · {formatRelativeTime(conversation.updatedAt)}
                      </p>
                    </div>
                    </button>
                    <button
                      onClick={(event) => {
                        event.stopPropagation();
                        onDelete(conversation.id);
                      }}
                      title="删除对话"
                      className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-slate-300 opacity-0 transition hover:bg-red-50 hover:text-red-500 focus:opacity-100 group-hover:opacity-100 dark:text-slate-600 dark:hover:bg-red-900/30 dark:hover:text-red-300"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
