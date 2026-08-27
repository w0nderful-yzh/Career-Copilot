import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  BookOpen,
  BriefcaseBusiness,
  FileSearch,
  FileStack,
  MessagesSquare,
  Sparkles,
  Users,
} from 'lucide-react';
import type {
  AgentBlock,
  ActionBlock,
  ChoiceBlock,
  ChoiceOption,
  InterviewSummaryBlock,
  KnowledgeCitationsBlock,
  ResumeSummaryBlock,
} from '../../types/copilot';
import { resolveActionRoute } from '../../constants/routes';

// Copilot 受控 Block 渲染器：只渲染白名单类型，未知类型静默忽略。
// Action 必须由用户点击执行，前端通过白名单映射跳转。

function ActionBlockView({ block }: { block: ActionBlock }) {
  const navigate = useNavigate();
  const target = resolveActionRoute(block.route, block.params);

  // 非白名单路由：不渲染按钮，防止任意跳转
  if (!target) {
    return null;
  }

  return (
    <button
      onClick={() => navigate(target.path)}
      className="mt-3 inline-flex items-center gap-2 rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:bg-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 dark:bg-white dark:text-slate-900 dark:hover:bg-primary-400"
    >
      <ArrowRight className="h-4 w-4" />
      {block.label || target.label}
    </button>
  );
}

const CHOICE_ICONS: Record<string, typeof FileSearch> = {
  ANALYZE_RESUME: FileSearch,
  OPTIMIZE_RESUME: Sparkles,
  START_INTERVIEW: MessagesSquare,
  JOB_MATCH: BriefcaseBusiness,
};

function ChoiceBlockView({
  block,
  disabled,
  onSelect,
}: {
  block: ChoiceBlock;
  disabled: boolean;
  onSelect?: (option: ChoiceOption) => void;
}) {
  const [selectedAction, setSelectedAction] = useState<string | null>(null);

  if (block.options.length === 0) return null;

  return (
    <div className="mt-4 rounded-2xl border border-slate-200/80 bg-slate-50/80 p-3 dark:border-slate-700 dark:bg-slate-900/50">
      {block.title && (
        <p className="mb-3 px-1 text-sm font-semibold text-slate-800 dark:text-slate-100">
          {block.title}
        </p>
      )}
      <div className="grid gap-2 sm:grid-cols-2">
        {block.options.map((option) => {
          const Icon = CHOICE_ICONS[option.action] ?? ArrowRight;
          const selected = selectedAction === option.action;
          return (
            <button
              key={`${option.action}-${option.label}`}
              type="button"
              disabled={disabled || selectedAction !== null || !onSelect}
              onClick={() => {
                setSelectedAction(option.action);
                onSelect?.(option);
              }}
              className={`group flex min-h-12 items-center gap-3 rounded-xl border px-3.5 py-3 text-left text-sm font-semibold transition focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed ${
                selected
                  ? 'border-primary-300 bg-primary-50 text-primary-700 dark:border-primary-700 dark:bg-primary-950/50 dark:text-primary-300'
                  : 'border-slate-200 bg-white text-slate-700 hover:-translate-y-0.5 hover:border-primary-300 hover:text-primary-700 hover:shadow-sm disabled:opacity-55 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:hover:border-primary-600 dark:hover:text-primary-300'
              }`}
            >
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500 transition group-hover:bg-primary-50 group-hover:text-primary-600 dark:bg-slate-700 dark:text-slate-300 dark:group-hover:bg-primary-900/40 dark:group-hover:text-primary-300">
                <Icon className="h-4 w-4" />
              </span>
              <span className="min-w-0 flex-1">{option.label}</span>
              <ArrowRight className="h-4 w-4 shrink-0 text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-primary-500" />
            </button>
          );
        })}
      </div>
    </div>
  );
}

function ResumeSummaryBlockView({ block }: { block: ResumeSummaryBlock }) {
  if (block.resumes.length === 0) return null;
  return (
    <div className="mt-3 space-y-2 rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2 text-xs font-semibold text-slate-500 dark:text-slate-400">
        <FileStack className="h-3.5 w-3.5" />
        简历概览
      </div>
      {block.resumes.map((resume) => (
        <div
          key={resume.id}
          className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-700/50"
        >
          <span className="truncate text-sm text-slate-700 dark:text-slate-200">
            {resume.filename}
          </span>
          <span className="ml-3 shrink-0 text-xs">
            {resume.latestScore != null ? (
              <span className="font-semibold text-primary-600 dark:text-primary-400">
                {resume.latestScore} 分
              </span>
            ) : (
              <span className="text-slate-400 dark:text-slate-500">待分析</span>
            )}
          </span>
        </div>
      ))}
    </div>
  );
}

function InterviewSummaryBlockView({ block }: { block: InterviewSummaryBlock }) {
  if (block.interviews.length === 0) return null;
  return (
    <div className="mt-3 space-y-2 rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2 text-xs font-semibold text-slate-500 dark:text-slate-400">
        <Users className="h-3.5 w-3.5" />
        最近模拟面试
      </div>
      {block.interviews.map((interview) => (
        <div
          key={interview.sessionId}
          className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-700/50"
        >
          <span className="truncate text-sm text-slate-700 dark:text-slate-200">
            {interview.skillId ?? '未知方向'}
          </span>
          <span className="ml-3 shrink-0 text-xs text-slate-500 dark:text-slate-400">
            {interview.difficulty ?? '-'} · {interview.status ?? '-'} ·{' '}
            {interview.totalQuestions ?? 0} 题
          </span>
        </div>
      ))}
    </div>
  );
}

function KnowledgeCitationsBlockView({ block }: { block: KnowledgeCitationsBlock }) {
  if (block.citations.length === 0) return null;
  return (
    <div className="mt-3 rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2 text-xs font-semibold text-slate-500 dark:text-slate-400">
        <BookOpen className="h-3.5 w-3.5" />
        引用来源
      </div>
      <ul className="mt-1 space-y-1">
        {block.citations.map((citation) => (
          <li
            key={citation.knowledgeBaseId ?? citation.name}
            className="text-xs text-slate-600 dark:text-slate-300"
          >
            {citation.name ?? '知识库'}
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function BlockRenderer({
  block,
  actionDisabled = false,
  onActionSelect,
}: {
  block: AgentBlock;
  actionDisabled?: boolean;
  onActionSelect?: (option: ChoiceOption) => void;
}) {
  switch (block.type) {
    case 'text':
      return null; // 文本由消息内容统一渲染，不渲染独立 text 块
    case 'action':
      return <ActionBlockView block={block} />;
    case 'choice':
      return (
        <ChoiceBlockView
          block={block}
          disabled={actionDisabled}
          onSelect={onActionSelect}
        />
      );
    case 'resume_summary':
      return <ResumeSummaryBlockView block={block} />;
    case 'interview_summary':
      return <InterviewSummaryBlockView block={block} />;
    case 'knowledge_citations':
      return <KnowledgeCitationsBlockView block={block} />;
    default:
      return null; // 未知类型：受控忽略
  }
}
