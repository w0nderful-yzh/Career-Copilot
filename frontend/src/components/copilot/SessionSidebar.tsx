import { Plus, Trash2 } from 'lucide-react';
import type { ConversationItem } from '../../types/copilot';

// Copilot 会话侧栏：列表 / 新建 / 切换 / 删除

interface SessionSidebarProps {
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

export default function SessionSidebar({
  conversations,
  activeConversationId,
  loading,
  onNew,
  onSelect,
  onDelete,
}: SessionSidebarProps) {
  return (
    <aside className="flex w-60 shrink-0 flex-col border-r border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <div className="p-3">
        <button
          onClick={onNew}
          className="flex w-full items-center justify-center gap-2 rounded-lg bg-primary-600 px-3 py-2 text-sm font-medium text-white transition hover:bg-primary-700 dark:bg-primary-500 dark:hover:bg-primary-600"
        >
          <Plus className="h-4 w-4" />
          新对话
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-2 pb-2">
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
                  <div
                    onClick={() => onSelect(conversation.id)}
                    className={`group flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 transition ${
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
                    <button
                      onClick={(event) => {
                        event.stopPropagation();
                        onDelete(conversation.id);
                      }}
                      title="删除对话"
                      className="shrink-0 rounded p-1 text-slate-300 opacity-0 transition hover:bg-red-50 hover:text-red-500 group-hover:opacity-100 dark:text-slate-600 dark:hover:bg-red-900/30 dark:hover:text-red-300"
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
    </aside>
  );
}