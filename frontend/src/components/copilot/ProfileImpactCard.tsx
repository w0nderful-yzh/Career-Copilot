import { useState } from 'react';
import { ChevronDown, ChevronRight, Sparkles, TrendingDown, TrendingUp } from 'lucide-react';
import type { ProfileImpact, SkillImpact } from '../../types/interview';
import {
  deltaText,
  evidenceSourceText,
  evidenceTimeText,
  impactTone,
  orderImpacts,
  sessionIdOf,
  type ImpactTone,
} from '../../utils/profileImpact';

// 本场面试的画像变化卡（P3 待收口）：回答「这场让我哪项变了、凭什么变的」。
//
// 每条变化都落回具体证据（面试场次 + 题号 + 时间），并可跳转到面试记录页定位该场次；
// 分数变化由 Java 按证据重算，这里不做任何二次计算，避免展示口径与画像分不一致。

const TONE_BADGE: Record<ImpactTone, string> = {
  up: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-300',
  down: 'bg-orange-50 text-orange-600 dark:bg-orange-900/40 dark:text-orange-300',
  flat: 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-300',
  new: 'bg-primary-50 text-primary-600 dark:bg-primary-900/40 dark:text-primary-300',
};

const TONE_ICON: Record<ImpactTone, typeof TrendingUp> = {
  up: TrendingUp,
  down: TrendingDown,
  flat: TrendingUp,
  new: Sparkles,
};

/** 分数条颜色（与侧栏画像面板同一色阶，保证两个口径一致） */
function barColor(score: number): string {
  if (score >= 80) return 'bg-emerald-500';
  if (score >= 60) return 'bg-lime-500';
  return 'bg-orange-500';
}

export default function ProfileImpactCard({
  impact,
  onViewSession,
}: {
  impact: ProfileImpact;
  /** 追溯入口：跳到面试记录页并定位该场次 */
  onViewSession: (sessionId: string) => void;
}) {
  const skills = orderImpacts(impact.skills);
  if (skills.length === 0) return null;

  return (
    <div className="mt-4 rounded-xl border border-slate-200 bg-white p-4 text-left dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2 text-xs font-semibold text-slate-500 dark:text-slate-400">
        <Sparkles className="h-3.5 w-3.5" />
        本场画像变化
        <span className="font-normal text-slate-400 dark:text-slate-500">
          （分数 = 面试证据均值，点开看来源）
        </span>
      </div>
      <div className="mt-3 space-y-1.5">
        {skills.map((skill) => (
          <ImpactRow key={skill.skill} impact={skill} onViewSession={onViewSession} />
        ))}
      </div>
    </div>
  );
}

function ImpactRow({
  impact,
  onViewSession,
}: {
  impact: SkillImpact;
  onViewSession: (sessionId: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const tone = impactTone(impact);
  const BadgeIcon = TONE_ICON[tone];
  // 追溯跳转去重：同一场面试的多条证据只留一个入口
  const sessionIds = [
    ...new Set(
      impact.sessionEvidences
        .map((evidence) => sessionIdOf(evidence))
        .filter((id): id is string => id !== null),
    ),
  ];

  return (
    <div className="rounded-lg px-2 py-1.5 transition hover:bg-slate-50 dark:hover:bg-slate-700/40">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        className="flex w-full items-center gap-2 text-left"
        aria-expanded={expanded}
      >
        {expanded ? (
          <ChevronDown className="h-3.5 w-3.5 shrink-0 text-slate-400" />
        ) : (
          <ChevronRight className="h-3.5 w-3.5 shrink-0 text-slate-400" />
        )}
        <span className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-700 dark:text-slate-200">
          {impact.skill}
        </span>
        {/* 分数条只反映当前分；before → after 的变化由右侧徽标表达 */}
        <div className="h-1.5 w-20 shrink-0 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
          <div
            className={`h-full rounded-full ${barColor(impact.afterScore)}`}
            style={{ width: `${Math.min(Math.max(impact.afterScore, 0), 100)}%` }}
          />
        </div>
        <span className="w-16 shrink-0 text-right text-xs tabular-nums text-slate-500 dark:text-slate-400">
          {impact.beforeScore === null ? '—' : `${impact.beforeScore} → ${impact.afterScore}`}
        </span>
        <span
          className={`inline-flex w-20 shrink-0 items-center justify-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-bold tabular-nums ${TONE_BADGE[tone]}`}
        >
          <BadgeIcon className="h-3 w-3" />
          {deltaText(impact)}
        </span>
      </button>

      {expanded && (
        <div className="mt-1.5 space-y-1 border-l-2 border-slate-100 pl-3 dark:border-slate-700">
          {impact.sessionEvidences.length === 0 ? (
            <p className="text-xs text-slate-400">本场没有可计分的证据</p>
          ) : (
            impact.sessionEvidences.map((evidence) => (
              <p key={evidence.sourceId} className="text-xs text-slate-500 dark:text-slate-400">
                {evidenceSourceText(evidence)}
                <span className="mx-1.5 text-slate-300 dark:text-slate-600">·</span>
                <span className="tabular-nums">{evidence.score} 分</span>
                {evidenceTimeText(evidence.occurredAt) && (
                  <>
                    <span className="mx-1.5 text-slate-300 dark:text-slate-600">·</span>
                    <span className="tabular-nums">{evidenceTimeText(evidence.occurredAt)}</span>
                  </>
                )}
              </p>
            ))
          )}
          {sessionIds.map((sessionId) => (
            <button
              key={sessionId}
              type="button"
              onClick={() => onViewSession(sessionId)}
              className="mt-0.5 inline-flex items-center gap-1 text-xs font-semibold text-primary-600 transition hover:text-primary-700 dark:text-primary-400"
            >
              查看该场面试 →
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
