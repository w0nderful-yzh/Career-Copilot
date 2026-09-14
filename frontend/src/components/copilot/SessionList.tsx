import { useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  Archive,
  ArrowLeft,
  Check,
  Pencil,
  Pin,
  Plus,
  RefreshCw,
  RotateCcw,
  Trash2,
  X,
} from 'lucide-react';
import type { ConversationItem } from '../../types/copilot';

// Copilot 会话列表面板：渲染在全局 Layout 最左侧栏（/copilot 时），避免双层侧栏
// 会话状态（列表/归档/错误）由 Layout 统一管理，本组件只负责展示与触发操作

/** 会话条目操作集合：集中成对象，避免十来个回调散在 props 上 */
export interface SessionItemActions {
  onSelect: (conversationId: number) => void;
  onRename: (conversationId: number, title: string) => void;
  onTogglePin: (conversationId: number) => void;
  /** 归档：从活跃列表收起但保留记录，可恢复 */
  onArchive: (conversationId: number) => void;
  /** 恢复已归档会话 */
  onRestore: (conversationId: number) => void;
  /** 删除：硬删除且不可恢复 */
  onDelete: (conversationId: number) => void;
}

interface SessionListProps {
  conversations: ConversationItem[];
  activeConversationId: number | null;
  loading: boolean;
  /** 加载或操作失败提示；非空时优先展示，避免与「还没有对话」的空态混淆 */
  error?: string | null;
  /** 归档视图：条目操作为「恢复 / 删除」，顶部提供返回入口 */
  archivedMode?: boolean;
  actions: SessionItemActions;
  onNew: () => void;
  onOpenArchived: () => void;
  onCloseArchived: () => void;
  onRetry?: () => void;
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

/** 条目上的图标按钮（hover 才显形，与既有删除按钮一致） */
function ItemAction({
  title,
  danger,
  onClick,
  children,
}: {
  title: string;
  danger?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={title}
      aria-label={title}
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      className={`rounded p-1 text-slate-300 transition dark:text-slate-600 ${
        danger
          ? 'hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/30 dark:hover:text-red-300'
          : 'hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-700 dark:hover:text-slate-200'
      }`}
    >
      {children}
    </button>
  );
}

/** 单个会话条目：支持就地重命名（Enter 保存 / Esc 取消 / 失焦保存） */
function SessionItem({
  conversation,
  active,
  archivedMode,
  actions,
}: {
  conversation: ConversationItem;
  active: boolean;
  archivedMode: boolean;
  actions: SessionItemActions;
}) {
  const [renaming, setRenaming] = useState(false);
  const [draft, setDraft] = useState(conversation.title);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (renaming) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [renaming]);

  const commitRename = () => {
    const title = draft.trim();
    setRenaming(false);
    // 空标题不提交：后端会把空标题回落成「新对话」，并非用户本意
    if (!title || title === conversation.title) return;
    actions.onRename(conversation.id, title);
  };

  if (renaming) {
    return (
      <div className="flex items-center gap-1 rounded-xl bg-slate-50 px-2 py-1.5 dark:bg-slate-800">
        <input
          ref={inputRef}
          value={draft}
          maxLength={40}
          aria-label="会话标题"
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              commitRename();
            } else if (event.key === 'Escape') {
              event.preventDefault();
              setRenaming(false);
            }
          }}
          onBlur={commitRename}
          className="min-w-0 flex-1 rounded-md border border-primary-300 bg-white px-2 py-1 text-sm text-slate-800 outline-none dark:border-primary-600 dark:bg-slate-900 dark:text-slate-100"
        />
        {/* 用 onMouseDown 阻止默认行为，避免点击按钮先触发 input 的 blur 提交 */}
        <button
          type="button"
          title="保存"
          aria-label="保存"
          onMouseDown={(event) => event.preventDefault()}
          onClick={commitRename}
          className="rounded p-1 text-emerald-600 transition hover:bg-emerald-50 dark:hover:bg-emerald-900/30"
        >
          <Check className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          title="取消"
          aria-label="取消"
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => setRenaming(false)}
          className="rounded p-1 text-slate-400 transition hover:bg-slate-100 dark:hover:bg-slate-700"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    );
  }

  return (
    <div className="group relative">
      <button
        type="button"
        onClick={() => actions.onSelect(conversation.id)}
        className={`flex w-full items-center gap-2 rounded-xl px-3 py-2.5 pr-[6rem] text-left transition ${
          active
            ? 'bg-primary-50 text-primary-700 dark:bg-primary-900/30 dark:text-primary-300'
            : 'text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800'
        }`}
      >
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-1 text-sm font-medium">
            {conversation.isPinned && (
              <Pin className="h-3 w-3 shrink-0 text-primary-500" aria-label="已置顶" />
            )}
            <span className="truncate">{conversation.title}</span>
          </p>
          <p className="mt-0.5 text-xs text-slate-400 dark:text-slate-500">
            {conversation.messageCount} 条 · {formatRelativeTime(conversation.updatedAt)}
          </p>
        </div>
      </button>
      <div className="absolute right-1.5 top-1/2 flex -translate-y-1/2 items-center gap-0.5 opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
        {archivedMode ? (
          <>
            <ItemAction title="恢复到最近对话" onClick={() => actions.onRestore(conversation.id)}>
              <RotateCcw className="h-3.5 w-3.5" />
            </ItemAction>
            <ItemAction title="删除（不可恢复）" danger onClick={() => actions.onDelete(conversation.id)}>
              <Trash2 className="h-3.5 w-3.5" />
            </ItemAction>
          </>
        ) : (
          <>
            <ItemAction
              title={conversation.isPinned ? '取消置顶' : '置顶'}
              onClick={() => actions.onTogglePin(conversation.id)}
            >
              <Pin className="h-3.5 w-3.5" />
            </ItemAction>
            <ItemAction title="重命名" onClick={() => setRenaming(true)}>
              <Pencil className="h-3.5 w-3.5" />
            </ItemAction>
            <ItemAction
              title="归档（保留记录，可恢复）"
              onClick={() => actions.onArchive(conversation.id)}
            >
              <Archive className="h-3.5 w-3.5" />
            </ItemAction>
            <ItemAction
              title="删除（不可恢复）"
              danger
              onClick={() => actions.onDelete(conversation.id)}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </ItemAction>
          </>
        )}
      </div>
    </div>
  );
}

export default function SessionList({
  conversations,
  activeConversationId,
  loading,
  error,
  archivedMode = false,
  actions,
  onNew,
  onOpenArchived,
  onCloseArchived,
  onRetry,
}: SessionListProps) {
  const emptyText = archivedMode
    ? '还没有归档的对话'
    : '还没有对话，开始你的第一段对话吧';

  return (
    <div>
      <div className="px-2 pb-4 pt-1">
        {archivedMode ? (
          <button
            onClick={onCloseArchived}
            className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-sm font-medium text-slate-600 transition hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800"
          >
            <ArrowLeft className="h-4 w-4" />
            返回最近对话
          </button>
        ) : (
          <button
            onClick={onNew}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 px-3 py-2.5 text-sm font-semibold text-white shadow-lg shadow-primary-500/20 transition hover:-translate-y-0.5 hover:bg-primary-700 dark:bg-primary-500 dark:hover:bg-primary-600"
          >
            <Plus className="h-4 w-4" />
            新对话
          </button>
        )}
      </div>

      <div className="px-2 pb-2">
        <div className="mb-1 px-3">
          <span className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
            {archivedMode ? '已归档' : '最近对话'}
          </span>
        </div>
        {loading ? (
          <p className="px-3 py-2 text-xs text-slate-400">
            {archivedMode ? '加载归档中…' : '加载中…'}
          </p>
        ) : (
          <>
            {/* 失败提示：列表已有内容时作为顶部提示（如删除失败），列表为空时提示本身就是
                主要内容——两种情况都不退化成「还没有对话」的空态 */}
            {error && (
              <div className="mx-1 mb-2 rounded-lg bg-red-50 px-3 py-2 dark:bg-red-900/20">
                <p className="flex items-start gap-1.5 text-xs text-red-600 dark:text-red-300">
                  <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  <span className="min-w-0 flex-1 break-words">{error}</span>
                </p>
                {onRetry && (
                  <button
                    type="button"
                    onClick={onRetry}
                    className="mt-2 inline-flex items-center gap-1 rounded-md bg-red-600 px-2 py-1 text-xs font-semibold text-white transition hover:bg-red-700"
                  >
                    <RefreshCw className="h-3 w-3" />
                    重试
                  </button>
                )}
              </div>
            )}
            {conversations.length === 0
              ? !error && (
                  <p className="px-3 py-2 text-xs text-slate-400 dark:text-slate-500">
                    {emptyText}
                  </p>
                )
              : (
                  <ul className="space-y-1" data-testid="session-items">
                    {conversations.map((conversation) => (
                      <li key={conversation.id}>
                        <SessionItem
                          conversation={conversation}
                          active={conversation.id === activeConversationId}
                          archivedMode={archivedMode}
                          actions={actions}
                        />
                      </li>
                    ))}
                  </ul>
                )}
          </>
        )}
      </div>

      {!archivedMode && (
        <div className="px-2 pt-1">
          {/* 归档入口：归档在数据层早已生效（列表只查 ACTIVE），但此前没有入口也没有恢复入口 */}
          <button
            onClick={onOpenArchived}
            className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs font-medium text-slate-500 transition hover:bg-slate-50 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            <Archive className="h-3.5 w-3.5" />
            已归档
          </button>
        </div>
      )}
    </div>
  );
}
