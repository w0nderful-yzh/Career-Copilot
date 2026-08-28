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

  const refreshConversations = useCallback(async () => {
    try {
      const list = await conversationApi.list();
      setConversations(list);
    } catch (err) {
      console.error('Failed to load conversations:', err);
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

  const deleteConversation = useCallback(
    async (conversationId: number) => {
      const confirmed = window.confirm('确定删除这段对话吗？删除后不可恢复。');
      if (!confirmed) return;
      try {
        await conversationApi.remove(conversationId);
        setConversations((prev) => prev.filter((c) => c.id !== conversationId));
        if (activeConversationId === conversationId) {
          setActiveConversationId(null);
        }
      } catch (err) {
        console.error('Failed to delete conversation:', err);
      }
    },
    [activeConversationId],
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
        if (list.length > 0) {
          setActiveConversationId(list[0].id);
        }
      } catch (err) {
        console.error('Failed to restore conversation:', err);
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
              conversations={conversations}
              activeConversationId={activeConversationId}
              loading={loadingConversations}
              onNew={newConversation}
              onSelect={selectConversation}
              onDelete={deleteConversation}
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
