import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {BookOpen, Bot, Calendar, Database, FileStack, MessageSquare, Moon, Settings, Sparkles, Sun, Users,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useCallback, useEffect, useState} from 'react';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';
import {ROUTES} from '../constants/routes';
import {conversationApi} from '../api/agentChat';
import SessionList from './copilot/SessionList';
import type {ConversationItem} from '../types/copilot';

/** 通过 Outlet context 暴露给 /copilot 页面的会话管理能力 */
export interface CopilotOutletContext {
  conversations: ConversationItem[];
  activeConversationId: number | null;
  loadingConversations: boolean;
  refreshConversations: () => Promise<void>;
  selectConversation: (conversationId: number) => void;
  newConversation: () => void;
  deleteConversation: (conversationId: number) => Promise<void>;
  onConversationCreated: (conversation: ConversationItem) => void;
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  // ===== Copilot 会话状态（提升到 Layout，供最左侧栏渲染，避免双层侧栏） =====
  const isCopilot = currentPath === ROUTES.copilot;
  const [conversations, setConversations] = useState<ConversationItem[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<number | null>(null);
  const [loadingConversations, setLoadingConversations] = useState(false);
  // 会话列表加载失败：与「还没有对话」的空态区分，否则失败会被当成空列表展示
  const [conversationError, setConversationError] = useState<string | null>(null);
  // 归档视图：归档在数据层早已生效（列表只查 ACTIVE），但此前没有任何归档入口，
  // 也没有查看/恢复入口——归档集合只进不出。这里补上进入与恢复。
  const [archivedMode, setArchivedMode] = useState(false);
  const [archivedConversations, setArchivedConversations] = useState<ConversationItem[]>([]);
  const [archivedLoading, setArchivedLoading] = useState(false);

  const refreshConversations = useCallback(async () => {
    try {
      const list = await conversationApi.list();
      setConversations(list);
      setConversationError(null);
    } catch (err) {
      console.error('Failed to load conversations:', err);
      setConversationError(err instanceof Error ? err.message : '会话列表加载失败');
    } finally {
      setLoadingConversations(false);
    }
  }, []);

  const selectConversation = useCallback((conversationId: number) => {
    setActiveConversationId(conversationId);
    navigate(ROUTES.copilot);
  }, [navigate]);

  const newConversation = useCallback(() => {
    setActiveConversationId(null);
    navigate(ROUTES.copilot);
  }, [navigate]);

  /**
   * 会话操作统一包装：失败写入 conversationError（界面可见），成功后刷新受影响的列表。
   *
   * @param refresh 操作影响哪个列表：active 刷新活跃列表，archived 刷新归档列表
   * @returns 是否成功（调用方据此决定是否要继续做后续状态调整，如取消选中）
   */
  const runConversationAction = useCallback(
    async (
      action: () => Promise<unknown>,
      failMessage: string,
      refresh: 'active' | 'archived',
    ): Promise<boolean> => {
      try {
        await action();
        setConversationError(null);
        if (refresh === 'archived') {
          setArchivedConversations(await conversationApi.list('ARCHIVED'));
        } else {
          await refreshConversations();
        }
        return true;
      } catch (err) {
        console.error(failMessage, err);
        setConversationError(err instanceof Error ? err.message : failMessage);
        return false;
      }
    },
    [refreshConversations],
  );

  const renameConversation = useCallback(
    (conversationId: number, title: string) =>
      runConversationAction(
        () => conversationApi.rename(conversationId, title),
        '重命名失败，请重试',
        archivedMode ? 'archived' : 'active',
      ),
    [archivedMode, runConversationAction],
  );

  const togglePinConversation = useCallback(
    (conversationId: number) =>
      runConversationAction(
        () => conversationApi.togglePin(conversationId),
        '置顶操作失败，请重试',
        archivedMode ? 'archived' : 'active',
      ),
    [archivedMode, runConversationAction],
  );

  const archiveConversation = useCallback(
    async (conversationId: number) => {
      const ok = await runConversationAction(
        () => conversationApi.archive(conversationId),
        '归档失败，请重试',
        'active',
      );
      // 归档的正是当前打开的会话：一并取消选中，避免它从列表消失却仍处于打开状态
      if (ok && activeConversationId === conversationId) {
        setActiveConversationId(null);
      }
    },
    [activeConversationId, runConversationAction],
  );

  const restoreConversation = useCallback(
    async (conversationId: number) => {
      const ok = await runConversationAction(
        () => conversationApi.restore(conversationId),
        '恢复失败，请重试',
        'archived',
      );
      // 恢复后它也回到活跃列表，同步刷新以免切回去看到旧数据
      if (ok) {
        await refreshConversations();
      }
    },
    [refreshConversations, runConversationAction],
  );

  const openArchived = useCallback(async () => {
    setArchivedMode(true);
    setArchivedLoading(true);
    setConversationError(null);
    try {
      setArchivedConversations(await conversationApi.list('ARCHIVED'));
    } catch (err) {
      console.error('Failed to load archived conversations:', err);
      setConversationError(err instanceof Error ? err.message : '归档列表加载失败');
    } finally {
      setArchivedLoading(false);
    }
  }, []);

  const closeArchived = useCallback(() => {
    setArchivedMode(false);
    setConversationError(null);
  }, []);

  const deleteConversation = useCallback(
    async (conversationId: number) => {
      const confirmed = window.confirm(
        '确定删除这段对话吗？删除后不可恢复。若只是想从列表收起、保留记录，请改用「归档」。',
      );
      if (!confirmed) return;
      const ok = await runConversationAction(
        () => conversationApi.remove(conversationId),
        '删除失败，请稍后重试',
        archivedMode ? 'archived' : 'active',
      );
      if (ok && activeConversationId === conversationId) {
        setActiveConversationId(null);
      }
    },
    [activeConversationId, archivedMode, runConversationAction],
  );

  // Layout 全局复用会话侧栏，仅首次挂载时恢复最近会话。
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoadingConversations(true);
      try {
        const list = await conversationApi.list();
        if (cancelled) return;
        setConversations(list);
        setConversationError(null);
        if (list.length > 0) {
          setActiveConversationId(list[0].id);
        }
      } catch (err) {
        if (cancelled) return;
        console.error('Failed to restore conversation:', err);
        setConversationError(err instanceof Error ? err.message : '会话列表加载失败');
      } finally {
        if (!cancelled) setLoadingConversations(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate(ROUTES.interviewCreate(crypto.randomUUID()), {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
    navigate(`/voice-interview?${params.toString()}`, {
      state: {
        voiceConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          techEnabled: true,
          projectEnabled: true,
          hrEnabled: true,
          plannedDuration: config.plannedDuration,
          resumeId: config.resumeId,
          llmProvider: config.llmProvider,
        },
      },
    });
  };

  const copilotWorkspaceItems = [
    { path: ROUTES.resumeLibrary, label: '简历', icon: FileStack },
    { path: ROUTES.interviewHub, label: '模拟面试', icon: Sparkles },
    { path: ROUTES.interviewHistory, label: '面试记录', icon: Users },
    { path: '/interview-schedule', label: '面试日程', icon: Calendar },
    { path: ROUTES.knowledgeBase, label: '知识库', icon: Database },
    { path: '/knowledgebase-interview', label: '知识库面试', icon: BookOpen },
    { path: ROUTES.knowledgeChat, label: '知识问答', icon: MessageSquare },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === ROUTES.interview
        || currentPath.startsWith('/interview/')
        || currentPath.startsWith('/voice-interview');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen bg-[#f7f8fb] dark:bg-slate-950">
      {/* 左侧边栏 */}
      <aside className="w-64 bg-white dark:bg-slate-900 border-r border-slate-100 dark:border-slate-700 fixed h-screen left-0 top-0 z-50 flex flex-col">
        {/* Logo */}
        <div className="border-b border-slate-100 p-5 dark:border-slate-700">
          <Link to={ROUTES.copilot} className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-950 text-white shadow-lg shadow-slate-300/40 dark:bg-white dark:text-slate-950 dark:shadow-none">
              <Bot className="h-5 w-5" />
            </div>
            <div>
              <span className="block text-lg font-bold tracking-tight text-slate-900 dark:text-white">
                Career Copilot
              </span>
              <span className="text-xs text-slate-400 dark:text-slate-500">
                智能求职工作台
              </span>
            </div>
          </Link>
        </div>

        {/* 会话与 Workspace 在所有页面保持一致，切换业务页时不再回退旧导航。 */}
        <nav className="flex-1 overflow-y-auto p-3">
            <SessionList
              conversations={archivedMode ? archivedConversations : conversations}
              activeConversationId={activeConversationId}
              loading={archivedMode ? archivedLoading : loadingConversations}
              error={conversationError}
              archivedMode={archivedMode}
              onNew={newConversation}
              onOpenArchived={() => void openArchived()}
              onCloseArchived={closeArchived}
              onRetry={() => void (archivedMode ? openArchived() : refreshConversations())}
              actions={{
                onSelect: selectConversation,
                onRename: (conversationId, title) =>
                  void renameConversation(conversationId, title),
                onTogglePin: (conversationId) => void togglePinConversation(conversationId),
                onArchive: (conversationId) => void archiveConversation(conversationId),
                onRestore: (conversationId) => void restoreConversation(conversationId),
                onDelete: (conversationId) => void deleteConversation(conversationId),
              }}
            />
            <div className="mx-2 mt-3 border-t border-slate-100 pt-4 dark:border-slate-700">
              <p className="mb-2 px-3 text-[11px] font-bold uppercase tracking-[0.16em] text-slate-400 dark:text-slate-500">
                Workspace
              </p>
              <div className="space-y-1">
                {copilotWorkspaceItems.map((item) => (
                  <Link
                    key={item.path}
                    to={item.path}
                    className={`group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition ${
                      isActive(item.path)
                        ? 'bg-primary-50 font-semibold text-primary-700 dark:bg-primary-900/30 dark:text-primary-300'
                        : 'font-medium text-slate-600 hover:bg-slate-50 hover:text-slate-950 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white'
                    }`}
                  >
                    <item.icon className={`h-4 w-4 transition ${isActive(item.path) ? 'text-primary-600 dark:text-primary-300' : 'text-slate-400 group-hover:text-primary-500'}`} />
                    {item.label}
                  </Link>
                ))}
              </div>
            </div>
        </nav>

        {/* 底部信息 */}
        <div className="border-t border-slate-100 p-4 dark:border-slate-700">
          <div className="space-y-1">
              <button
                onClick={toggleTheme}
                className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-slate-600 transition hover:bg-slate-50 hover:text-slate-950 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white"
              >
                {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
                {theme === 'dark' ? '浅色模式' : '深色模式'}
              </button>
              <Link
                to={ROUTES.settings}
                className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-slate-600 transition hover:bg-slate-50 hover:text-slate-950 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white"
              >
                <Settings className="h-4 w-4" />
                设置
              </Link>
          </div>
        </div>
      </aside>

      {/* Copilot 保持沉浸式聊天，其余业务页共享同一工作台底色与留白。 */}
      <main className={`ml-64 min-h-screen flex-1 overflow-y-auto ${isCopilot ? 'h-screen overflow-hidden p-0' : 'p-6 lg:p-8 xl:p-10'}`}>
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
          className={isCopilot ? 'h-full' : ''}
        >
          <Outlet context={{
            openInterviewModalWithResume,
            conversations,
            activeConversationId,
            loadingConversations,
            refreshConversations,
            selectConversation,
            newConversation,
            deleteConversation,
            onConversationCreated: (conversation: ConversationItem) => {
              setConversations((prev) => [conversation, ...prev]);
            },
          } satisfies CopilotOutletContext & { openInterviewModalWithResume: (resumeId: number) => void }} />
        </motion.div>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />
    </div>
  );
}
