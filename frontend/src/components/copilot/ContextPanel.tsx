import { useEffect, useState } from 'react';
import {
  BarChart3,
  CheckCircle2,
  Circle,
  FileText,
  Link2,
  Target,
} from 'lucide-react';
import type { CopilotMessage } from '../../types/copilot';
import {
  skillProfileApi,
  type SkillProfileSkill,
} from '../../api/agentChat';

// P1 视觉预览：任务示例只用于界面占位；能力画像已接入真实 Evidence 数据（P3-2）。
const PREVIEW_TASKS = [
  { label: '复习 JVM GC', done: true },
  { label: '梳理消息可靠性', done: false },
  { label: '准备项目深挖', done: false },
] as const;

function findLatestResume(messages: CopilotMessage[]): string | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const match = messages[index].content.match(/(?:\[简历附件：|上传了简历附件：)(.+?)(?:]|$)/);
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
  | { status: 'ready'; skills: SkillProfileSkill[] };

function ProfileSection() {
  const [state, setState] = useState<ProfileState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const profile = await skillProfileApi.get();
        if (cancelled) return;
        const skills = profile.skills ?? [];
        setState(skills.length > 0 ? { status: 'ready', skills } : { status: 'empty' });
      } catch {
        if (!cancelled) setState({ status: 'error' });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <section className="mb-6">
      <SectionTitle icon={BarChart3}>能力画像</SectionTitle>
      <div className="space-y-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        {state.status === 'loading' && (
          <p className="text-xs text-slate-400">加载画像数据…</p>
        )}
        {state.status === 'error' && (
          <p className="text-xs text-amber-600 dark:text-amber-400">画像加载失败，请稍后重试</p>
        )}
        {state.status === 'empty' && (
          <p className="text-xs leading-5 text-slate-400 dark:text-slate-500">
            暂无画像数据。完成一场模拟面试后，这里会展示各技能的可追溯评分。
          </p>
        )}
        {state.status === 'ready' &&
          state.skills.map((skill) => (
            <div
              key={skill.skill}
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
          ))}
      </div>
    </section>
  );
}

export default function ContextPanel({ messages }: { messages: CopilotMessage[] }) {
  const activeResume = findLatestResume(messages);

  return (
    <aside className="hidden h-full w-80 shrink-0 flex-col overflow-y-auto border-l border-slate-200/80 bg-[#f8f9fc] px-5 py-5 dark:border-slate-700 dark:bg-slate-900/80 xl:flex">
      <div className="mb-5 flex items-center justify-between">
        <div>
          <p className="font-display text-sm font-bold text-slate-900 dark:text-white">求职上下文</p>
          <p className="mt-0.5 text-xs text-slate-400">保持当前任务信息可见</p>
        </div>
        <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-1 text-[10px] font-bold text-amber-700 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300">
          预览数据
        </span>
      </div>

      <section className="mb-6">
        <SectionTitle icon={Target}>当前目标</SectionTitle>
        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-bold text-slate-900 dark:text-white">Java 后端求职</p>
              <p className="mt-1 text-xs text-slate-400">示例目标 · P1-3 接入真实会话资源</p>
            </div>
            <span className="text-sm font-bold tabular-nums text-slate-700 dark:text-slate-200">72%</span>
          </div>
          <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
            <div className="h-full w-[72%] rounded-full bg-primary-500" />
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
          <div className="border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-700">
            JD 资源将在 P2-1 接入
          </div>
        </div>
      </section>

      <ProfileSection />

      <section>
        <SectionTitle icon={CheckCircle2}>今日任务 · 示例</SectionTitle>
        <div className="rounded-2xl border border-slate-200 bg-white p-2 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          {PREVIEW_TASKS.map((task) => (
            <div key={task.label} className="flex items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-sm text-slate-600 dark:text-slate-300">
              {task.done ? (
                <CheckCircle2 className="h-4 w-4 shrink-0 text-primary-500" />
              ) : (
                <Circle className="h-4 w-4 shrink-0 text-slate-300 dark:text-slate-600" />
              )}
              <span className={task.done ? 'text-slate-400 line-through' : ''}>{task.label}</span>
            </div>
          ))}
        </div>
      </section>
    </aside>
  );
}
