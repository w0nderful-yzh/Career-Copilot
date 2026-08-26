import { useNavigate } from 'react-router-dom';
import { ArrowRight, BookOpen, FileStack, Users } from 'lucide-react';
import type {
  AgentBlock,
  ActionBlock,
  InterviewSummaryBlock,
  KnowledgeCitationsBlock,
  ResumeSummaryBlock,
} from '../../types/copilot';
import { ACTION_ROUTE_MAP } from '../../types/copilot';

// Copilot 受控 Block 渲染器：只渲染白名单类型，未知类型静默忽略。
// Action 必须由用户点击执行，前端通过白名单映射跳转。

function ActionBlockView({ block }: { block: ActionBlock }) {
  const navigate = useNavigate();
  const target = ACTION_ROUTE_MAP[block.route];

  // 非白名单路由：不渲染按钮，防止任意跳转
  if (!target) {
    return null;
  }

  return (
    <button
      onClick={() => navigate(target.path)}
      className="mt-2 inline-flex items-center gap-2 rounded-lg bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 dark:bg-primary-500 dark:hover:bg-primary-600"
    >
      <ArrowRight className="h-4 w-4" />
      {block.label || target.label}
    </button>
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

export default function BlockRenderer({ block }: { block: AgentBlock }) {
  switch (block.type) {
    case 'text':
      return null; // 文本由消息内容统一渲染，不渲染独立 text 块
    case 'action':
      return <ActionBlockView block={block} />;
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