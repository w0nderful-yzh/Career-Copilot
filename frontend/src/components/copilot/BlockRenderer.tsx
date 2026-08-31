import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  BookOpen,
  BriefcaseBusiness,
  CheckSquare,
  ChevronDown,
  FileSearch,
  FileStack,
  MessagesSquare,
  Minus,
  Plus,
  SlidersHorizontal,
  Sparkles,
  Square,
  Target,
  Users,
} from 'lucide-react';
import type {
  AgentBlock,
  ActionBlock,
  ChoiceBlock,
  ChoiceOption,
  InterviewProposalBlock,
  InterviewSummaryBlock,
  KnowledgeCitationsBlock,
  NavigationBlock,
  ResumeOptimizationBlock,
  ResumeOptimizationPatch,
  ResumeSummaryBlock,
  SkillProfileBlock,
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

function InterviewProposalBlockView({
  block,
  actionDisabled,
  onConfirm,
  onAdjust,
}: {
  block: InterviewProposalBlock;
  actionDisabled: boolean;
  onConfirm: (option: ChoiceOption) => void;
  onAdjust: (option: ChoiceOption) => void;
}) {
  const focusNames = block.focus.length > 0 ? block.focus.join(' / ') : '综合考察';

  const confirmOption: ChoiceOption = {
    action: 'CREATE_INTERVIEW',
    label: '按推荐开始',
    payload: {
      direction: block.direction,
      difficulty: block.difficulty,
      focus: block.focus,
      questionCount: block.question_count,
      resumeId: block.resume_id ?? null,
    },
  };
  const adjustOption: ChoiceOption = {
    action: 'START_INTERVIEW',
    label: '调整配置',
    payload: {},
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
          <span className="font-semibold">题量：</span>
          {block.question_count} 题
        </span>
      </div>
      {block.summary && (
        <p className="mt-3 rounded-xl bg-white/70 px-3 py-2 text-sm leading-6 text-slate-700 dark:bg-slate-800/70 dark:text-slate-200">
          {block.summary}
        </p>
      )}
      <div className="mt-4 flex flex-wrap gap-2.5">
        <button
          type="button"
          disabled={actionDisabled}
          onClick={() => onConfirm(confirmOption)}
          className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:from-primary-600 hover:to-indigo-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55"
        >
          <Sparkles className="h-4 w-4" />
          按推荐开始
        </button>
        <button
          type="button"
          disabled={actionDisabled}
          onClick={() => onAdjust(adjustOption)}
          className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition hover:-translate-y-0.5 hover:border-primary-300 hover:text-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-200 dark:hover:border-primary-500 dark:hover:text-primary-300"
        >
          <SlidersHorizontal className="h-4 w-4" />
          调整配置
        </button>
      </div>
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
  if (block.skills.length === 0) return null;
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
    </div>
  );
}

/** patch 类型展示标签 */
const PATCH_TYPE_META: Record<ResumeOptimizationPatch['type'], { label: string; className: string }> = {
  REPLACE: { label: '改写', className: 'bg-amber-50 text-amber-600 dark:bg-amber-900/40 dark:text-amber-300' },
  ADD: { label: '新增', className: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-300' },
  DELETE: { label: '删除', className: 'bg-red-50 text-red-600 dark:bg-red-900/40 dark:text-red-300' },
};

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

  const toggle = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const selectedCount = selectedIds.size;
  const canApply = !actionDisabled && !applied && selectedCount > 0;

  const handleApply = () => {
    if (!canApply || !onActionSelect) return;
    setApplied(true);
    onActionSelect({
      action: 'APPLY_RESUME_PATCHES',
      label: '应用勾选修改',
      payload: {
        proposalId: block.proposalId,
        patchIds: [...selectedIds],
      },
    });
  };

  return (
    <div className="mt-4 overflow-hidden rounded-2xl border border-primary-200/70 bg-gradient-to-br from-primary-50/60 to-indigo-50/40 dark:border-primary-800/40 dark:from-primary-950/30 dark:to-indigo-950/20">
      <div className="flex items-center justify-between px-4 pt-4">
        <div className="flex items-center gap-2 text-sm font-bold text-slate-900 dark:text-white">
          <Sparkles className="h-4 w-4 text-primary-600 dark:text-primary-400" />
          简历优化建议
          <span className="rounded-full bg-white/80 px-2 py-0.5 text-xs font-semibold text-primary-600 dark:bg-slate-800/80 dark:text-primary-300">
            {block.patches.length} 条
          </span>
        </div>
        <button
          type="button"
          onClick={() =>
            setSelectedIds(
              selectedCount === block.patches.length
                ? new Set()
                : new Set(block.patches.map((patch) => patch.id)),
            )
          }
          disabled={applied}
          className="text-xs font-medium text-primary-600 hover:underline disabled:opacity-50 dark:text-primary-300"
        >
          {selectedCount === block.patches.length ? '全不选' : '全选'}
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
                    if (!applied) toggle(patch.id);
                  }}
                  className="mt-0.5 shrink-0 text-primary-500 disabled:opacity-50"
                  disabled={applied}
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
                  <p className="mt-1.5 text-[11px] text-slate-400">{patch.reason}</p>
                </div>
              </div>
            </label>
          );
        })}
      </div>

      <div className="px-4 pb-4 pt-3">
        <button
          type="button"
          disabled={!canApply || !onActionSelect}
          onClick={handleApply}
          className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:from-primary-600 hover:to-indigo-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55"
        >
          <CheckSquare className="h-4 w-4" />
          {applied
            ? '已提交应用'
            : `应用勾选修改（${selectedCount}/${block.patches.length}）`}
        </button>
        <p className="mt-2 text-[11px] text-slate-400">
          应用后生成新版本，原版本保持不变
        </p>
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
          onAdjust={(option) => onActionSelect?.(option)}
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
    case 'resume_optimization':
      return (
        <ResumeOptimizationBlockView
          block={block}
          actionDisabled={actionDisabled}
          onActionSelect={onActionSelect}
        />
      );
    default:
      return null; // 未知类型：受控忽略
  }
}
