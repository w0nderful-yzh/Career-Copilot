import { useEffect, useState } from 'react';
import {
  BarChart3,
  BriefcaseBusiness,
  FileText,
  Link2,
  RefreshCw,
  Target,
} from 'lucide-react';
import type { CopilotMessage } from '../../types/copilot';
import {
  jobApi,
  skillProfileApi,
  type DeclaredSkill,
  type SkillProfileSkill,
} from '../../api/agentChat';

// 侧栏「求职上下文」只展示 Java 权威数据：能力画像来自 Evidence，
// 活跃资源来自会话绑定的简历与 JD。Preparation 尚未接入时不展示示例任务。

function findLatestResume(messages: CopilotMessage[]): string | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const match = messages[index].content.match(/(?:\[简历附件：|上传了简历附件：)(.+?)(?:]|$)/);
    if (match?.[1]) return match[1];
  }
  return null;
}

function findLatestJd(messages: CopilotMessage[]): string | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const match = messages[index].content.match(/(?:\[JD 附件：|上传了岗位 JD：)(.+?)(?:]|$)/);
    if (match?.[1]) return match[1];
  }
  return null;
}

function SectionTitle({ icon: Icon, children }: { icon: typeof Target; children: string }) {
  return (
    <div className="mb-3 flex items-center gap-2 text-xs font-bold uppercase tracking-[0.14em] text-slate-400 dark:text-slate-500">
      <Icon className="h-3.5 w-3.5" />
      {children}
    </div>
  );
}

/** 分数 → 条形颜色（与 SkillProfileBlock 渲染一致） */
function skillBarColor(score: number): string {
  if (score >= 80) return 'bg-emerald-500';
  if (score >= 60) return 'bg-lime-500';
  return 'bg-orange-500';
}

type ProfileState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'empty' }
  | { status: 'ready'; skills: SkillProfileSkill[]; declared: DeclaredSkill[] };

/** 会话绑定 JD 的标题（activeJobId 存在时拉详情；失败静默，回落附件文件名展示） */
function useBoundJobTitle(activeJobId: number | null | undefined): string | null {
  const [title, setTitle] = useState<string | null>(null);
  useEffect(() => {
    if (!activeJobId) return;
    let cancelled = false;
    (async () => {
      try {
        const job = await jobApi.get(activeJobId);
        if (!cancelled) setTitle(job.title);
      } catch {
        // 绑定 JD 可能已被删除：保持 null，面板回落显示附件名或空态
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [activeJobId]);
  return title;
}

function ProfileSection({
  refreshToken = 0,
  onStartFocusInterview,
}: {
  /** 变化即重取画像（P4-6b）：面试报告完成后证据刚写入，侧栏不能还显示旧分 */
  refreshToken?: number;
  /** 一键定向（P4-6b）：把「补强这个技能」的明确选择交给提案节点 */
  onStartFocusInterview?: (skill: string) => void;
}) {
  const [state, setState] = useState<ProfileState>({ status: 'loading' });
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const profile = await skillProfileApi.get();
        if (cancelled) return;
        const skills = profile.skills ?? [];
        const declared = profile.declaredSkills ?? [];
        setState(
          skills.length > 0 || declared.length > 0
            ? { status: 'ready', skills, declared }
            : { status: 'empty' },
        );
      } catch {
        if (!cancelled) setState({ status: 'error' });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshToken, retryKey]);

  return (
    <section className="mb-6">
      <SectionTitle icon={BarChart3}>能力画像</SectionTitle>
      <div className="space-y-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        {state.status === 'loading' && (
          <p className="text-xs text-slate-400">加载画像数据…</p>
        )}
        {state.status === 'error' && (
          <div className="flex items-center justify-between gap-3">
            <p className="text-xs text-amber-600 dark:text-amber-400">画像加载失败，当前不展示旧数据</p>
            <button
              type="button"
              onClick={() => {
                setState({ status: 'loading' });
                setRetryKey((key) => key + 1);
              }}
              className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-amber-200 bg-amber-50 px-2 py-1 text-[11px] font-semibold text-amber-700 transition hover:bg-amber-100 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300"
            >
              <RefreshCw className="h-3 w-3" />
              重试画像
            </button>
          </div>
        )}
        {state.status === 'empty' && (
          <p className="text-xs leading-5 text-slate-400 dark:text-slate-500">
            暂无画像数据。完成一场模拟面试后，这里会展示各技能的可追溯评分。
          </p>
        )}
        {state.status === 'ready' &&
          state.skills.map((skill) => (
            <div key={skill.skill} className="group">
              <div
                className="grid grid-cols-[5rem_1fr_2rem] items-center gap-2"
                title={`来自 ${skill.evidenceCount} 条面试证据`}
              >
                <span className="truncate text-xs font-semibold text-slate-600 dark:text-slate-300">
                  {skill.skill}
                </span>
                <div className="h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
                  <div
                    className={`h-full rounded-full ${skillBarColor(skill.score)}`}
                    style={{ width: `${Math.min(Math.max(skill.score, 0), 100)}%` }}
                  />
                </div>
                <span className="text-right text-xs font-bold tabular-nums text-slate-600 dark:text-slate-300">
                  {skill.score}
                </span>
              </div>
              {onStartFocusInterview && (
                <button
                  type="button"
                  onClick={() => onStartFocusInterview(skill.skill)}
                  className="mt-1 pl-[5rem] text-[11px] font-semibold text-primary-600 opacity-0 transition group-hover:opacity-100 focus:opacity-100 dark:text-primary-400"
                  title={`针对 ${skill.skill} 来一场定向面试（带上该技能作为重点与必要覆盖）`}
                >
                  定向补强 →
                </button>
              )}
            </div>
          ))}
        {state.status === 'ready' && state.declared.length > 0 && (
          <div className="border-t border-dashed border-slate-200 pt-2 dark:border-slate-600">
            <p
              className="text-[11px] font-semibold text-slate-400 dark:text-slate-500"
              title="结构化简历里列出了这些技能，但还没有面试证据可以评分"
            >
              简历已列 · 待验证
            </p>
            <div className="mt-1.5 flex flex-wrap gap-1">
              {state.declared.map((item) => (
                <button
                  key={item.skill}
                  type="button"
                  disabled={!onStartFocusInterview}
                  onClick={() => onStartFocusInterview?.(item.skill)}
                  className="rounded bg-slate-50 px-1.5 py-0.5 text-[11px] text-slate-500 transition enabled:hover:bg-primary-50 enabled:hover:text-primary-700 disabled:cursor-default dark:bg-slate-700/50 dark:text-slate-300 dark:enabled:hover:bg-primary-950/40 dark:enabled:hover:text-primary-300"
                  title="还没有面试证据：点一下针对它来一场定向面试"
                >
                  {item.skill}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

export default function ContextPanel({
  messages,
  activeJobId,
  profileRefreshToken,
  onStartFocusInterview,
}: {
  messages: CopilotMessage[];
  activeJobId?: number | null;
  /** 报告完成后 +1：侧栏画像随之重取（P4-6b） */
  profileRefreshToken?: number;
  onStartFocusInterview?: (skill: string) => void;
}) {
  const activeResume = findLatestResume(messages);
  // JD 展示优先级：会话绑定（Conversation Memory，跨轮有效）> 本轮附件文件名
  const boundJobTitle = useBoundJobTitle(activeJobId);
  const activeJd = boundJobTitle ?? findLatestJd(messages);

  return (
    <aside className="hidden h-full w-80 shrink-0 flex-col overflow-y-auto border-l border-slate-200/80 bg-[#f8f9fc] px-5 py-5 dark:border-slate-700 dark:bg-slate-900/80 xl:flex">
      <div className="mb-5 flex items-center justify-between">
        <div>
          <p className="font-display text-sm font-bold text-slate-900 dark:text-white">求职上下文</p>
          <p className="mt-0.5 text-xs text-slate-400">仅展示当前会话的真实资源与证据</p>
        </div>
        <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-1 text-[10px] font-bold text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300">
          Evidence
        </span>
      </div>

      <section className="mb-6">
        <SectionTitle icon={Target}>当前目标</SectionTitle>
        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-bold text-slate-900 dark:text-white">
                {activeJd ?? '尚未设定目标岗位'}
              </p>
              <p className="mt-1 text-xs leading-5 text-slate-400">
                {activeJd
                  ? '来自当前会话绑定的岗位 JD'
                  : '上传或绑定 JD 后，这里会显示本次求职目标'}
              </p>
            </div>
            <Target className={`h-5 w-5 shrink-0 ${activeJd ? 'text-primary-500' : 'text-slate-300 dark:text-slate-600'}`} />
          </div>
        </div>
      </section>

      <section className="mb-6">
        <SectionTitle icon={Link2}>活跃资源</SectionTitle>
        <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <div className="flex items-center gap-3 px-4 py-3.5">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/50 dark:text-blue-300">
              <FileText className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">
                {activeResume ?? '尚未选择简历'}
              </p>
              <p className="mt-0.5 text-xs text-slate-400">
                {activeResume ? '来自当前会话附件' : '拖入 PDF 后自动显示'}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3 border-t border-slate-100 px-4 py-3.5 dark:border-slate-700">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-amber-50 text-amber-600 dark:bg-amber-950/50 dark:text-amber-300">
              <BriefcaseBusiness className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">
                {activeJd ?? '尚未选择 JD'}
              </p>
              <p className="mt-0.5 text-xs text-slate-400">
                {activeJd ? '本会话的目标岗位 JD' : '拖入 JD 后自动绑定'}
              </p>
            </div>
          </div>
        </div>
      </section>

      <ProfileSection
        refreshToken={profileRefreshToken}
        onStartFocusInterview={onStartFocusInterview}
      />
    </aside>
  );
}
