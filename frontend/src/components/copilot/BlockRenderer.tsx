import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  AlertTriangle,
  BookOpen,
  BriefcaseBusiness,
  CheckCircle2,
  CheckSquare,
  ChevronDown,
  FileSearch,
  FileStack,
  Loader2,
  MessagesSquare,
  Minus,
  Plus,
  ShieldAlert,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Square,
  Target,
  Users,
  X,
} from 'lucide-react';
import type {
  AgentBlock,
  ActionBlock,
  ChoiceBlock,
  ChoiceOption,
  InterviewConfig,
  InterviewProposalBlock,
  InterviewSummaryBlock,
  KnowledgeCitationsBlock,
  NavigationBlock,
  ResumeOptimizationBlock,
  ResumeOptimizationPatch,
  ResumeGapAnalysisBlock,
  ResumeSummaryBlock,
  SkillProfileBlock,
} from '../../types/copilot';
import type { ResumeContentJson } from '../../api/history';
import { historyApi } from '../../api/history';
import { resolveActionRoute } from '../../constants/routes';
import {
  isAllPatchesSelected,
  toggleAllPatchSelection,
  togglePatchSelection,
} from '../../utils/resumePatchSelection';
import InterviewConfigPanel from './InterviewConfigPanel';

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

function NavigationBlockView({ block }: { block: NavigationBlock }) {
  const navigate = useNavigate();
  const target = resolveActionRoute(block.route, block.params);

  // 非白名单路由：不渲染按钮，防止任意跳转
  if (!target) {
    return null;
  }

  return (
    <button
      onClick={() => navigate(target.path)}
      className="mt-3 inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:from-primary-600 hover:to-indigo-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
    >
      <ArrowRight className="h-4 w-4" />
      {block.label || target.label}
    </button>
  );
}

/**
 * 生成创建面试的幂等键（ARCH-1）。
 *
 * 同一次「确认开始」的网络重试必须复用同一值，否则会重复建会话；
 * 但**配置一旦变化就是另一个创建意图，必须换新键**——Java 侧按 requestId 命中幂等缓存后
 * 会直接返回上一次的会话，沿用旧键会让用户拿到与自己选择不符的面试。
 */
function newInterviewRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `req-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function InterviewProposalBlockView({
  block,
  actionDisabled,
  onConfirm,
}: {
  block: InterviewProposalBlock;
  actionDisabled: boolean;
  /** 用户点「按推荐开始」或「应用自定义配置」时回传 CREATE_INTERVIEW action */
  onConfirm: (option: ChoiceOption) => void;
}) {
  const focusNames = block.focus.length > 0 ? block.focus.join(' / ') : '综合考察';
  const requiredNames = (block.required_topics ?? []).length > 0
    ? block.required_topics.join(' / ')
    : '未指定';
  // 手动调整后的配置（null = 使用 Agent 推荐）；与 Agent 推荐收敛到同一 InterviewConfig → CREATE_INTERVIEW
  const [customConfig, setCustomConfig] = useState<InterviewConfig | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);
  /** 幂等键：同一次确认流程（含重发/重试）复用同一个值，避免重复建会话 */
  const [createRequestId, setCreateRequestId] = useState(() => newInterviewRequestId());

  const activeConfig: InterviewConfig = customConfig ?? {
    direction: block.direction,
    difficulty: block.difficulty,
    planned_duration_minutes: block.planned_duration_minutes ?? 20,
    required_topics: block.required_topics ?? [],
    focus: block.focus,
  };

  const confirmOption: ChoiceOption = {
    action: 'CREATE_INTERVIEW',
    label: customConfig ? '按自定义配置开始' : '按推荐开始',
    payload: {
      direction: activeConfig.direction,
      difficulty: activeConfig.difficulty,
      focus: activeConfig.focus,
      plannedDurationMinutes: activeConfig.planned_duration_minutes,
      requiredTopics: activeConfig.required_topics,
      resumeId: block.resume_id ?? null,
      requestId: createRequestId,
    },
  };

  const applyConfig = (config: InterviewConfig) => {
    // 手动配置与 Agent 推荐收敛到同一 InterviewConfig → 立即 CREATE_INTERVIEW
    setCustomConfig(config);
    setPanelOpen(false);
    // 配置变了 = 新的创建意图：换幂等键，否则 Java 会按旧键返回上一次的会话
    const nextRequestId = newInterviewRequestId();
    setCreateRequestId(nextRequestId);
    onConfirm({
      action: 'CREATE_INTERVIEW',
      label: '按自定义配置开始',
      payload: {
        direction: config.direction,
        difficulty: config.difficulty,
        focus: config.focus,
        plannedDurationMinutes: config.planned_duration_minutes,
        requiredTopics: config.required_topics,
        resumeId: block.resume_id ?? null,
        requestId: nextRequestId,
      },
    });
  };

  return (
    <div className="mt-4 overflow-hidden rounded-2xl border border-primary-200/70 bg-gradient-to-br from-primary-50/80 to-indigo-50/60 p-4 dark:border-primary-800/40 dark:from-primary-950/40 dark:to-indigo-950/30">
      <div className="flex items-center gap-2 text-sm font-bold text-slate-900 dark:text-white">
        <Target className="h-4 w-4 text-primary-600 dark:text-primary-400" />
        面试推荐
      </div>
      <p className="mt-3 text-lg font-bold tracking-tight text-slate-900 dark:text-white">
        {block.direction_name} · {block.difficulty_name} · {block.mode === 'VOICE' ? '语音' : '文字'}
      </p>
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-slate-600 dark:text-slate-300">
        <span className="inline-flex items-center gap-1.5">
          <span className="font-semibold">重点：</span>
          {focusNames}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="font-semibold">时长：</span>
          约 {block.planned_duration_minutes ?? 20} 分钟
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="font-semibold">必要覆盖：</span>
          {requiredNames}
        </span>
      </div>
      {block.summary && (
        <p className="mt-3 rounded-xl bg-white/70 px-3 py-2 text-sm leading-6 text-slate-700 dark:bg-slate-800/70 dark:text-slate-200">
          {block.summary}
        </p>
      )}
      {/* 推荐依据（P4-6b）：画像里的真实事实，来自 Java 数据而非模型措辞。
          没有依据时整节不渲染——空标题会被误读成「没理由也推荐」。 */}
      {(block.reasons ?? []).length > 0 && (
        <div className="mt-2 rounded-xl border border-dashed border-primary-200/70 px-3 py-2 dark:border-primary-800/40">
          <p className="text-[11px] font-semibold text-primary-700 dark:text-primary-300">
            推荐依据
          </p>
          <ul className="mt-1 space-y-0.5">
            {(block.reasons ?? []).map((reason) => (
              <li key={reason} className="text-xs leading-5 text-slate-600 dark:text-slate-300">
                · {reason}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className="mt-4 flex flex-wrap gap-2.5">
        <button
          type="button"
          disabled={actionDisabled}
          onClick={() => onConfirm(confirmOption)}
          className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:from-primary-600 hover:to-indigo-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55"
        >
          <Sparkles className="h-4 w-4" />
          {customConfig ? '按自定义配置开始' : '按推荐开始'}
        </button>
        <button
          type="button"
          disabled={actionDisabled}
          onClick={() => setPanelOpen((open) => !open)}
          className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition hover:-translate-y-0.5 hover:border-primary-300 hover:text-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-200 dark:hover:border-primary-500 dark:hover:text-primary-300"
        >
          <SlidersHorizontal className="h-4 w-4" />
          调整配置
        </button>
      </div>

      {/* 内联配置面板：本地 state 展开，不触发 Agent、不发送聊天消息 */}
      {panelOpen && (
        <InterviewConfigPanel
          initial={{
            direction: block.direction,
            difficulty: block.difficulty,
            planned_duration_minutes: block.planned_duration_minutes ?? 20,
            required_topics: block.required_topics ?? [],
            focus: block.focus,
          }}
          disabled={actionDisabled}
          onApply={applyConfig}
          onCancel={() => setPanelOpen(false)}
        />
      )}
    </div>
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

/** 分数 → 条形颜色（≥80 绿 / ≥60 黄绿 / <60 橙） */
function skillBarColor(score: number): string {
  if (score >= 80) return 'bg-emerald-500';
  if (score >= 60) return 'bg-lime-500';
  return 'bg-orange-500';
}

/** 证据来源的可读描述：面试轮次 → 「面试 s1:2 · 55 分」 */
function evidenceLabel(sourceType: string | null | undefined): string {
  switch (sourceType) {
    case 'INTERVIEW_TURN':
      return '模拟面试答题';
    case 'INTERVIEW_SESSION':
      return '面试总评';
    case 'RESUME':
      return '简历分析';
    default:
      return '评分来源';
  }
}

function formatOccurredAt(occurredAt: string | null | undefined): string {
  if (!occurredAt) return '';
  const date = new Date(occurredAt);
  return Number.isNaN(date.getTime()) ? '' : ` · ${date.toLocaleDateString('zh-CN')}`;
}

/** 单个技能行：分数条 + 可展开的证据明细（可追溯验收：任一分数能点出 Evidence 来源） */
function SkillProfileRow({
  skill,
}: {
  skill: SkillProfileBlock['skills'][number];
}) {
  const [expanded, setExpanded] = useState(false);
  const score = skill.score ?? 0;
  const evidences = skill.evidences ?? [];
  const hasEvidence = evidences.length > 0;

  return (
    <div className="rounded-lg bg-slate-50 px-3 py-2.5 dark:bg-slate-700/50">
      <button
        type="button"
        disabled={!hasEvidence}
        onClick={() => setExpanded((prev) => !prev)}
        className={`grid w-full grid-cols-[5rem_1fr_4.5rem] items-center gap-2 text-left ${
          hasEvidence ? 'cursor-pointer' : 'cursor-default'
        }`}
      >
        <span className="truncate text-sm font-semibold text-slate-700 dark:text-slate-200">
          {skill.skill ?? '未知技能'}
        </span>
        <span className="h-1.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-600">
          <span
            className={`block h-full rounded-full ${skillBarColor(score)}`}
            style={{ width: `${Math.min(Math.max(score, 0), 100)}%` }}
          />
        </span>
        <span className="text-right text-xs font-bold tabular-nums text-slate-600 dark:text-slate-300">
          {score} 分
          {hasEvidence && (
            <ChevronDown
              className={`ml-1 inline h-3 w-3 text-slate-400 transition-transform ${
                expanded ? 'rotate-180' : ''
              }`}
            />
          )}
        </span>
      </button>
      {expanded && (
        <ul className="mt-2 space-y-1 border-t border-slate-200 pt-2 dark:border-slate-600">
          {evidences.map((evidence, index) => (
            <li
              key={evidence.sourceId ?? index}
              className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400"
            >
              <span className="min-w-0 truncate">
                {evidenceLabel(evidence.sourceType)}
                {evidence.sourceId ? `（${evidence.sourceId}）` : ''}
                {formatOccurredAt(evidence.occurredAt)}
              </span>
              <span className="ml-2 shrink-0 font-semibold tabular-nums">
                {evidence.score ?? '-'} 分
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function SkillProfileBlockView({ block }: { block: SkillProfileBlock }) {
  const declared = block.declaredSkills ?? [];
  if (block.skills.length === 0 && declared.length === 0) return null;
  return (
    <div className="mt-3 space-y-2 rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2 text-xs font-semibold text-slate-500 dark:text-slate-400">
        <Target className="h-3.5 w-3.5" />
        能力画像
        <span className="font-normal text-slate-400 dark:text-slate-500">
          （分数 = 面试证据均值，点击查看来源）
        </span>
      </div>
      {block.skills.map((skill) => (
        <SkillProfileRow key={skill.skill ?? 'unknown'} skill={skill} />
      ))}
      {declared.length > 0 && (
        <div className="border-t border-dashed border-slate-200 pt-2 dark:border-slate-600">
          <p className="text-xs font-semibold text-slate-400 dark:text-slate-500">
            简历已列 · 待验证
            <span className="ml-1 font-normal">（还没有面试证据，建议优先考察）</span>
          </p>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {declared.map((item) => (
              <span
                key={item.skill ?? 'unknown'}
                className="rounded-lg bg-slate-50 px-2 py-1 text-xs text-slate-500 dark:bg-slate-700/50 dark:text-slate-300"
              >
                {item.skill ?? '未知技能'}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/** patch 类型展示标签 */
const PATCH_TYPE_META: Record<ResumeOptimizationPatch['type'], { label: string; className: string }> = {
  REPLACE: { label: '改写', className: 'bg-amber-50 text-amber-600 dark:bg-amber-900/40 dark:text-amber-300' },
  ADD: { label: '新增', className: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-300' },
  DELETE: { label: '删除', className: 'bg-red-50 text-red-600 dark:bg-red-900/40 dark:text-red-300' },
};

const GAP_STATUS_META: Record<
  ResumeGapAnalysisBlock['items'][number]['status'],
  { label: string; className: string; icon: typeof CheckCircle2 }
> = {
  MATCHED: {
    label: '已匹配',
    className: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/35 dark:text-emerald-300',
    icon: CheckCircle2,
  },
  PARTIAL: {
    label: '部分匹配',
    className: 'bg-amber-50 text-amber-700 dark:bg-amber-900/35 dark:text-amber-300',
    icon: AlertTriangle,
  },
  MISSING: {
    label: '缺失',
    className: 'bg-red-50 text-red-700 dark:bg-red-900/35 dark:text-red-300',
    icon: ShieldAlert,
  },
  UNKNOWN: {
    label: '待确认',
    className: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
    icon: ShieldAlert,
  },
};

const MATCH_LEVEL_LABEL: Record<ResumeGapAnalysisBlock['matchLevel'], string> = {
  HIGH: '高匹配',
  MEDIUM: '中等匹配',
  LOW: '低匹配',
  UNKNOWN: '证据不足',
};

/** JD Gap 独立证据区：不把缺失要求偷换成可直接应用的 Patch。 */
function ResumeGapAnalysisBlockView({ block }: { block: ResumeGapAnalysisBlock }) {
  return (
    <section className="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900">
      <div className="border-b border-slate-100 bg-[linear-gradient(120deg,rgba(15,23,42,0.04),rgba(14,165,233,0.08))] px-4 py-4 dark:border-slate-800 dark:bg-[linear-gradient(120deg,rgba(15,23,42,0.9),rgba(14,165,233,0.12))]">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2 text-sm font-bold text-slate-950 dark:text-white">
              <FileSearch className="h-4 w-4 text-sky-600 dark:text-sky-400" />
              JD Gap 分析
            </div>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{block.jobTitle}</p>
          </div>
          <span className="rounded-full border border-sky-200 bg-white/80 px-2.5 py-1 text-xs font-semibold text-sky-700 dark:border-sky-800 dark:bg-slate-900/70 dark:text-sky-300">
            {MATCH_LEVEL_LABEL[block.matchLevel]}
          </span>
        </div>
        <p className="mt-3 text-xs leading-5 text-slate-600 dark:text-slate-300">{block.summary}</p>
      </div>

      <div className="space-y-2.5 p-4">
        {block.items.map((item, index) => {
          const meta = GAP_STATUS_META[item.status];
          const StatusIcon = meta.icon;
          return (
            <article key={`${item.requirement}-${index}`} className="rounded-xl border border-slate-100 p-3 dark:border-slate-800">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <p className="min-w-0 flex-1 text-sm font-semibold text-slate-800 dark:text-slate-100">
                  {item.requirement}
                </p>
                <span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] font-semibold ${meta.className}`}>
                  <StatusIcon className="h-3 w-3" />
                  {meta.label}
                </span>
              </div>
              {item.resumeEvidence.length > 0 ? (
                <div className="mt-2 border-l-2 border-sky-200 pl-2.5 dark:border-sky-800">
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">简历证据</p>
                  {item.resumeEvidence.map((evidence) => (
                    <p key={evidence} className="mt-1 text-xs leading-5 text-slate-600 dark:text-slate-300">“{evidence}”</p>
                  ))}
                </div>
              ) : (
                <p className="mt-2 text-xs text-slate-400">简历中暂未找到可支撑的原文证据。</p>
              )}
              <p className="mt-2 text-[11px] leading-5 text-slate-500 dark:text-slate-400">
                <span className="font-semibold">影响：</span>{item.impact}
              </p>
              {item.verificationRequired.length > 0 && (
                <div className="mt-2 rounded-lg bg-amber-50 px-2.5 py-2 text-[11px] text-amber-800 dark:bg-amber-950/30 dark:text-amber-300">
                  <span className="font-semibold">需你核实：</span>{item.verificationRequired.join('；')}
                </div>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}

/** 优化模式标签（P2 待修正）：让「通用 / 定向方向 / JD 定向」在卡片上可分辨 */
function optimizationModeLabel(
  type: ResumeOptimizationBlock['optimizationType'],
  direction?: string | null,
): string | null {
  switch (type) {
    case 'JD_TARGETED':
      return 'JD 定向';
    case 'TARGET_DIRECTION':
      return direction ? `定向 · ${direction}` : '定向方向';
    case 'GENERAL':
      return null; // 通用优化是默认语义，不额外加徽标
    default:
      return null;
  }
}

/** path → 可读位置描述 */
function patchPathLabel(path: string): string {
  const segmentNames: Record<string, string> = {
    basicInfo: '基本信息',
    education: '教育经历',
    experience: '工作经历',
    projects: '项目经历',
    skills: '技能',
    customSections: '其他段落',
  };
  const segment = path.split(/[.[]/, 1)[0];
  return segmentNames[segment] ?? segment;
}

/** 简历优化提案块（P2-3）：Diff 卡片 + 勾选 + 应用（CONFIRM_WRITE 确认入口） */
function ResumeOptimizationBlockView({
  block,
  actionDisabled,
  onActionSelect,
}: {
  block: ResumeOptimizationBlock;
  actionDisabled: boolean;
  onActionSelect?: (option: ChoiceOption) => void;
}) {
  // 默认全选（Agent 给出的建议默认全部推荐）
  const [selectedIds, setSelectedIds] = useState<Set<string>>(
    () => new Set(block.patches.map((patch) => patch.id)),
  );
  const [applied, setApplied] = useState(false);
  const [confirmingApply, setConfirmingApply] = useState(false);
  // 提案决策（P2 待修正）：拒绝入口 + 刷新回放时的权威状态回显。
  // 历史消息块只存 patches，不回显状态的话已应用/已忽略的提案刷新后仍显示成可操作。
  const [rejected, setRejected] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [decisionError, setDecisionError] = useState('');
  const [decidedStatus, setDecidedStatus] = useState<'APPLIED' | 'REJECTED' | null>(null);
  const locked = applied || rejected || decidedStatus !== null;

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const proposal = await historyApi.getResumeOptimizationProposal(block.proposalId);
        if (cancelled) return;
        if (proposal.status === 'APPLIED' || proposal.status === 'REJECTED') {
          setDecidedStatus(proposal.status);
        }
      } catch {
        // 状态回显失败不阻断操作：仍可点击应用，Java 侧状态机会拒绝重复决策
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [block.proposalId]);

  // ===== Preview PDF（P2-4 勾选即重渲）=====
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewState, setPreviewState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [previewError, setPreviewError] = useState('');
  // 原版内容只拉一次；勾选变化防抖 600ms 后重渲
  const contentRef = useRef<ResumeContentJson | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewUrlRef = useRef<string | null>(null);

  const fetchContentAndRender = async (ids: Set<string>) => {
    try {
      setPreviewState('loading');
      if (!contentRef.current) {
        const version = await historyApi.getResumeVersionDetail(block.versionId);
        if (!version.content) throw new Error('版本没有结构化内容');
        contentRef.current = version.content;
      }
      const content = contentRef.current;
      const selectedPatches = block.patches
        .filter((patch) => ids.has(patch.id))
        .map((patch) => ({
          id: patch.id,
          type: patch.type,
          path: patch.path,
          oldValue: patch.oldValue ?? null,
          newValue: patch.newValue ?? null,
          reason: patch.reason ?? null,
          evidence: patch.evidence ?? [],
          impact: patch.impact ?? null,
          verificationRequired: patch.verificationRequired ?? [],
        }));
      const blob = await historyApi.previewResumePdf(content, selectedPatches);
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
      const url = URL.createObjectURL(blob);
      previewUrlRef.current = url;
      setPreviewUrl(url);
      setPreviewState('ready');
    } catch (error) {
      setPreviewState('error');
      setPreviewError(error instanceof Error ? error.message : '预览生成失败');
    }
  };

  const schedulePreview = (ids: Set<string>) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => void fetchContentAndRender(ids), 600);
  };

  // 挂载即按默认全选渲染一次预览（「实时预览」语义），卸载时清理防抖计时器与 blob URL
  useEffect(() => {
    schedulePreview(selectedIds);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    };
    // 仅挂载时按初始全选渲染一次；勾选变化走 toggle → schedulePreview
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const patchIds = block.patches.map((patch) => patch.id);
  const allSelected = isAllPatchesSelected(selectedIds, patchIds);

  // 单条勾选与全选/全不选共用同一条路径：改状态 + 调度重渲。
  // 两者若各写一份，极易只改状态而漏掉预览（全选/全不选曾因此与预览不一致）。
  // 应用/拒绝后锁定不再重渲。
  const updateSelection = (compute: (prev: Set<string>) => Set<string>) => {
    setConfirmingApply(false);
    setSelectedIds((prev) => {
      const next = compute(prev);
      if (!locked) schedulePreview(next);
      return next;
    });
  };

  const toggle = (id: string) =>
    updateSelection((prev) => togglePatchSelection(prev, id));

  const toggleAll = () =>
    updateSelection((prev) => toggleAllPatchSelection(prev, patchIds));

  const selectedCount = selectedIds.size;
  const canApply = !actionDisabled && !locked && selectedCount > 0;

  const handleApply = () => {
    if (!canApply || !onActionSelect) return;
    setConfirmingApply(true);
  };

  const confirmApply = () => {
    if (!canApply || !onActionSelect) return;
    setApplied(true);
    setConfirmingApply(false);
    onActionSelect({
      action: 'APPLY_RESUME_PATCHES',
      label: '应用勾选修改',
      payload: {
        proposalId: block.proposalId,
        patchIds: [...selectedIds],
      },
    });
  };

  /** 放弃本轮全部建议：不动简历内容，只落 Java 审计状态（REJECTED） */
  const handleReject = async () => {
    if (locked || rejecting) return;
    setConfirmingApply(false);
    setRejecting(true);
    setDecisionError('');
    try {
      await historyApi.rejectResumeOptimizationProposal(block.proposalId);
      setRejected(true);
    } catch (error) {
      setDecisionError(error instanceof Error ? error.message : '操作失败，请稍后重试');
    } finally {
      setRejecting(false);
    }
  };

  const decisionNote =
    rejected || decidedStatus === 'REJECTED'
      ? '已忽略本次优化建议，简历内容未改动'
      : decidedStatus === 'APPLIED' || applied
        ? '本提案已应用并生成新版本，简历内容已更新'
        : '';

  return (
    <div className="mt-4 overflow-hidden rounded-2xl border border-primary-200/70 bg-gradient-to-br from-primary-50/60 to-indigo-50/40 dark:border-primary-800/40 dark:from-primary-950/30 dark:to-indigo-950/20">
      <div className="flex items-center justify-between px-4 pt-4">
        <div className="flex items-center gap-2 text-sm font-bold text-slate-900 dark:text-white">
          <Sparkles className="h-4 w-4 text-primary-600 dark:text-primary-400" />
          简历优化建议
          <span className="rounded-full bg-white/80 px-2 py-0.5 text-xs font-semibold text-primary-600 dark:bg-slate-800/80 dark:text-primary-300">
            {block.patches.length} 条
          </span>
          {optimizationModeLabel(block.optimizationType, block.targetDirection) && (
            <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-semibold text-indigo-600 dark:bg-indigo-900/40 dark:text-indigo-300">
              {optimizationModeLabel(block.optimizationType, block.targetDirection)}
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={toggleAll}
          disabled={locked}
          className="text-xs font-medium text-primary-600 hover:underline disabled:opacity-50 dark:text-primary-300"
        >
          {allSelected ? '全不选' : '全选'}
        </button>
      </div>

      {block.summary && (
        <p className="mx-4 mt-2 rounded-xl bg-white/70 px-3 py-2 text-xs leading-5 text-slate-600 dark:bg-slate-800/70 dark:text-slate-300">
          {block.summary}
        </p>
      )}
      {block.rejectedNote && (
        <p className="mx-4 mt-2 text-xs text-amber-600 dark:text-amber-400">
          ⚠ {block.rejectedNote}（不合规建议已自动剔除）
        </p>
      )}
      {decisionNote && (
        <p className="mx-4 mt-2 rounded-xl bg-slate-100/80 px-3 py-2 text-xs text-slate-500 dark:bg-slate-800/60 dark:text-slate-400">
          {decisionNote}
        </p>
      )}

      <div className="mt-3 space-y-2 px-4">
        {block.patches.map((patch) => {
          const selected = selectedIds.has(patch.id);
          const typeMeta = PATCH_TYPE_META[patch.type] ?? PATCH_TYPE_META.REPLACE;
          return (
            <label
              key={patch.id}
              className={`block cursor-pointer rounded-xl border p-3 transition ${
                selected
                  ? 'border-primary-300 bg-white dark:border-primary-700 dark:bg-slate-800'
                  : 'border-slate-200 bg-white/50 opacity-70 dark:border-slate-700 dark:bg-slate-800/50'
              }`}
            >
              <div className="flex items-start gap-2.5">
                <button
                  type="button"
                  role="checkbox"
                  aria-checked={selected}
                  onClick={(e) => {
                    e.preventDefault();
                    if (!locked) toggle(patch.id);
                  }}
                  className="mt-0.5 shrink-0 text-primary-500 disabled:opacity-50"
                  disabled={locked}
                >
                  {selected ? (
                    <CheckSquare className="h-4.5 w-4.5" />
                  ) : (
                    <Square className="h-4.5 w-4.5 text-slate-300 dark:text-slate-600" />
                  )}
                </button>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`px-1.5 py-0.5 rounded text-[11px] font-bold ${typeMeta.className}`}>
                      {typeMeta.label}
                    </span>
                    <span className="text-[11px] text-slate-400">{patchPathLabel(patch.path)}</span>
                  </div>
                  {patch.oldValue && (
                    <p className="mt-2 flex gap-1.5 rounded-lg bg-red-50/80 px-2.5 py-1.5 text-xs leading-5 text-slate-600 dark:bg-red-950/30 dark:text-slate-300">
                      <Minus className="mt-0.5 h-3 w-3 shrink-0 text-red-400" />
                      <span className="line-through decoration-red-300/60">{patch.oldValue}</span>
                    </p>
                  )}
                  {patch.newValue && (
                    <p className="mt-1 flex gap-1.5 rounded-lg bg-emerald-50/80 px-2.5 py-1.5 text-xs leading-5 text-slate-700 dark:bg-emerald-950/30 dark:text-slate-200">
                      <Plus className="mt-0.5 h-3 w-3 shrink-0 text-emerald-500" />
                      <span>{patch.newValue}</span>
                    </p>
                  )}
                  <div className="mt-2 grid gap-2 rounded-lg border border-slate-100 bg-slate-50/70 p-2.5 text-[11px] dark:border-slate-700 dark:bg-slate-900/40 sm:grid-cols-2">
                    <div>
                      <p className="font-semibold text-slate-500 dark:text-slate-300">修改理由</p>
                      <p className="mt-1 leading-5 text-slate-600 dark:text-slate-400">{patch.reason}</p>
                    </div>
                    <div>
                      <p className="font-semibold text-slate-500 dark:text-slate-300">影响范围</p>
                      <p className="mt-1 leading-5 text-slate-600 dark:text-slate-400">
                        {patch.impact || `影响 ${patchPathLabel(patch.path)} 中的对应内容`}
                      </p>
                    </div>
                    <div className="sm:col-span-2">
                      <p className="font-semibold text-slate-500 dark:text-slate-300">依据</p>
                      {(patch.evidence?.length ?? 0) > 0 ? (
                        <ul className="mt-1 space-y-1 text-slate-600 dark:text-slate-400">
                          {(patch.evidence ?? []).map((evidence) => <li key={evidence}>“{evidence}”</li>)}
                        </ul>
                      ) : (
                        <p className="mt-1 text-amber-600 dark:text-amber-400">未提供可核对依据，建议不勾选。</p>
                      )}
                    </div>
                    <div className="sm:col-span-2">
                      {(patch.verificationRequired?.length ?? 0) > 0 ? (
                        <div className="flex gap-1.5 rounded-md bg-amber-50 px-2 py-1.5 text-amber-800 dark:bg-amber-950/30 dark:text-amber-300">
                          <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                          <span><strong>需核实：</strong>{patch.verificationRequired?.join('；')}</span>
                        </div>
                      ) : (
                        <div className="flex items-center gap-1.5 text-emerald-700 dark:text-emerald-400">
                          <ShieldCheck className="h-3.5 w-3.5" />
                          仅重组已有事实，无需额外核实
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </label>
          );
        })}
      </div>

      <div className="px-4 pb-4 pt-3">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={!canApply || !onActionSelect}
            onClick={handleApply}
            className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:from-primary-600 hover:to-indigo-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55"
          >
            <CheckSquare className="h-4 w-4" />
            {applied || decidedStatus === 'APPLIED'
              ? '已提交应用'
              : `核对并应用（${selectedCount}/${block.patches.length}）`}
          </button>
          {/* 与「应用」对称的决策出口：拒绝只落审计状态，不改动简历内容 */}
          <button
            type="button"
            disabled={locked || rejecting}
            onClick={() => void handleReject()}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-medium text-slate-500 transition hover:border-slate-300 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-55 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
          >
            {rejecting ? <Loader2 className="h-4 w-4 animate-spin" /> : <X className="h-4 w-4" />}
            {rejected || decidedStatus === 'REJECTED' ? '已忽略' : '全部忽略'}
          </button>
        </div>
        {decisionError && (
          <p className="mt-2 text-[11px] text-red-500">操作失败：{decisionError}</p>
        )}
        {confirmingApply && (
          <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50/80 p-3 dark:border-amber-800 dark:bg-amber-950/25">
            <div className="flex gap-2">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
              <div className="min-w-0">
                <p className="text-sm font-semibold text-amber-900 dark:text-amber-200">确认生成新版本？</p>
                <p className="mt-1 text-xs leading-5 text-amber-800 dark:text-amber-300">
                  将严格按当前勾选的 {selectedCount} 条 Patch 生成新版本；
                  Diff、当前 PDF 预览与本次应用使用同一组选择，原版本不会被覆盖。
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={confirmApply}
                    className="rounded-lg bg-amber-700 px-3 py-2 text-xs font-semibold text-white transition hover:bg-amber-800"
                  >
                    确认生成新版本
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmingApply(false)}
                    className="rounded-lg border border-amber-300 bg-white px-3 py-2 text-xs font-medium text-amber-800 dark:bg-slate-900 dark:text-amber-300"
                  >
                    取消
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
        <p className="mt-2 text-[11px] text-slate-400">
          应用后生成新版本，原版本保持不变；忽略则不改动任何内容
        </p>

        {/* Preview PDF：勾选即重渲（防抖 600ms）；桌面渲染区，排版不满意时原上传件仍是退路 */}
        <div className="mt-3 rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800/60">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
              实时预览（已勾选 {selectedCount} 条的效果）
            </span>
            <div className="flex items-center gap-2">
              {previewState === 'loading' && (
                <span className="text-[11px] text-primary-500">渲染中…</span>
              )}
              {previewState !== 'ready' && previewState !== 'loading' && (
                <button
                  type="button"
                  onClick={() => void fetchContentAndRender(selectedIds)}
                  className="text-[11px] font-medium text-primary-600 hover:underline dark:text-primary-300"
                >
                  {previewState === 'error' ? '重试预览' : '生成预览'}
                </button>
              )}
            </div>
          </div>
          {previewState === 'error' && (
            <p className="mt-2 text-[11px] text-red-500">预览失败：{previewError}</p>
          )}
          {previewUrl ? (
            <iframe
              key={previewUrl}
              src={previewUrl}
              title="简历优化预览"
              className="mt-2 h-[480px] w-full rounded-lg border border-slate-100 dark:border-slate-700"
            />
          ) : (
            previewState !== 'loading' && (
              <p className="mt-2 text-[11px] text-slate-400">
                点击「生成预览」查看勾选修改后的 PDF 效果（预览不会保存，应用后才生成新版本）。
                排版不满意？原始上传件仍在你手里。
              </p>
            )
          )}
        </div>
      </div>
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
    case 'navigation':
      return <NavigationBlockView block={block} />;
    case 'interview_proposal':
      return (
        <InterviewProposalBlockView
          block={block}
          actionDisabled={actionDisabled}
          onConfirm={(option) => onActionSelect?.(option)}
        />
      );
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
    case 'skill_profile':
      return <SkillProfileBlockView block={block} />;
    case 'resume_gap_analysis':
      return <ResumeGapAnalysisBlockView block={block} />;
    case 'resume_optimization':
      return (
        <ResumeOptimizationBlockView
          block={block}
          actionDisabled={actionDisabled}
          onActionSelect={onActionSelect}
        />
      );
    case 'interview_session':
      // Interview Mode 重构：该块仅作为「进入 Interview Mode」的信号，
      // 由 CopilotPage 消费并切换到 InterviewWorkspace，不再渲染 Card。
      return null;
    default:
      return null; // 未知类型：受控忽略
  }
}
