import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {BookOpen, Bot, Calendar, ChevronRight, Database, FileStack, MessageSquare, Moon, Settings, Sparkles, Sun, Users,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useCallback, useEffect, useState} from 'react';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';
import {ROUTES} from '../constants/routes';
import {conversationApi} from '../api/agentChat';
import SessionList from './copilot/SessionList';
import type {ConversationItem} from '../types/copilot';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

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
  }, []);

  const newConversation = useCallback(() => {
    setActiveConversationId(null);
  }, []);

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

  // 首次进入 /copilot：加载会话列表并恢复最近一个会话
  useEffect(() => {
    if (!isCopilot) return;
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
  }, [isCopilot]);

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

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    {
      id: 'copilot',
      title: 'Career Copilot',
      items: [
        { id: 'copilot', path: ROUTES.copilot, label: 'Agent 工作台', icon: Bot, description: '描述目标，Agent 帮你完成' },
      ],
    },
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Sparkles, description: '文字/语音面试练习' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
        { id: 'interview-schedule', path: '/interview-schedule', label: '面试日程', icon: Calendar, description: '管理面试安排' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'kb-interview', path: '/knowledgebase-interview', label: '知识库面试', icon: BookOpen, description: '题库维护与面试' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
      ],
    },
    {
      id: 'system',
      title: '系统',
      items: [
        { id: 'settings', path: '/settings', label: '设置', icon: Settings, description: '管理模型和语音服务' },
      ],
    },
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

  // 渲染导航分组（/copilot 时隐藏 Career Copilot 分组，该入口已由会话列表取代）
  const renderNavGroups = (groups: NavGroup[]) => (
    <div className="space-y-6">
      {groups.map((group) => (
        <div key={group.id}>
          <div className="px-3 mb-2">
            <span className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
              {group.title}
            </span>
          </div>
          <div className="space-y-1">
            {group.items.map((item) => {
              const active = isActive(item.path);

              return (
                <Link
                  key={item.id}
                  to={item.path}
                  className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200
                    ${active
                      ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                      : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white'
                    }`}
                >
                  <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors
                    ${active
                      ? 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400'
                      : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:hover:text-white'
                    }`}
                  >
                    <item.icon className="w-5 h-5" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                      {item.label}
                    </span>
                    {item.description && (
                      <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                        {item.description}
                      </span>
                    )}
                  </div>
                  {active && <ChevronRight className="w-4 h-4 text-primary-400" />}
                </Link>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      {/* 左侧边栏 */}
      <aside className="w-64 bg-white dark:bg-slate-900 border-r border-slate-100 dark:border-slate-700 fixed h-screen left-0 top-0 z-50 flex flex-col">
        {/* Logo */}
        <div className="p-6 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between">
          <Link to={ROUTES.copilot} className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-600 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-800 dark:text-white tracking-tight block">AI Interview</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">智能面试助手</span>
            </div>
          </Link>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-4 pb-2">
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

{/* 导航菜单 / 历史对话（/copilot 时展示会话列表 + 功能入口，复用最左侧栏） */}
        {isCopilot ? (
          <nav className="flex-1 p-3 overflow-y-auto">
            <SessionList
              conversations={conversations}
              activeConversationId={activeConversationId}
              loading={loadingConversations}
              onNew={newConversation}
              onSelect={selectConversation}
              onDelete={deleteConversation}
            />
            <div className="mt-3 border-t border-slate-100 dark:border-slate-700 pt-3">
              {renderNavGroups(navGroups.filter((group) => group.id !== 'copilot'))}
            </div>
          </nav>
        ) : (
          <nav className="flex-1 p-4 overflow-y-auto">
            {renderNavGroups(navGroups)}
          </nav>
        )}

        {/* 底部信息 */}
        <div className="p-4 border-t border-slate-100 dark:border-slate-700">
          <div className="px-3 py-2 bg-gradient-to-r from-primary-50 to-indigo-50 dark:from-primary-900/30 dark:to-slate-800 rounded-xl">
            <p className="text-xs text-primary-600 dark:text-primary-400 font-medium">AI 面试助手 v1.0</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">Powered by AI</p>
          </div>
        </div>
      </aside>

      {/* 主内容区：/copilot 为全屏工作台（无 padding），其余业务页保留原布局 */}
      <main className={`flex-1 ml-64 min-h-screen overflow-y-auto ${isCopilot ? 'p-0 h-screen overflow-hidden' : 'p-10'}`}>
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
