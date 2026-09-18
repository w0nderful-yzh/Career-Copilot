import { useCallback, useEffect, useRef, useState } from 'react';
import { Bot, Clock, Loader2, RotateCcw, Sparkles, User, X } from 'lucide-react';
import { interviewApi } from '../../api/interview';
import { ApiError } from '../../api/request';
import type { InterviewModeState } from '../../types/copilot';
import type { InterviewQuestion, ProfileImpact } from '../../types/interview';
import {
  deriveInterviewView,
  interviewPlanProgress,
  questionMatchesTopic,
  toInterviewerTurn,
  type InterviewTurn as Turn,
} from '../../utils/interviewTurns';
import {
  applyTurnResult,
  initialTurnSyncState,
  isStaleTurnErrorCode,
  requestIdForAttempt,
  syncVersionFromSession,
  type TurnSyncState,
} from '../../utils/interviewTurnSync';
import {
  decideEvaluationPhase,
  EVALUATION_POLL_INTERVAL_MS,
} from '../../utils/evaluatePolling';
import ProfileImpactCard from './ProfileImpactCard';

// Interview Mode 主工作区（Interview Mode 重构）：
// - 顶部轻量状态栏：当前话题 · 必要覆盖 · 已用时间 · [换话题/调整时间/结束]
// - 中部消息流直接渲染「面试官题 / 用户答」——复用普通消息气泡视觉（非 Card）
// - Java InterviewSession 为权威状态；本题答完由 Java 决策引擎返回下一题
// - 切走/刷新不销毁：本组件只拉取 Java 会话渲染，重新进入由 CopilotPage 恢复 mode

const DIFFICULTY_LABELS: Record<string, string> = {
  junior: '校招',
  mid: '中级',
  senior: '高级',
};

function formatSeconds(total: number): string {
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

/**
 * 是否属于「提交一致性冲突」（P4-9a）。
 *
 * 服务端在这些情况下已经同步了权威进度（会话被另一处推进 / 已结束 / 版本过期 /
 * 不是当前待答题），前端应当回源重读，而不是把它当成网络抖动原样重试。
 */
function isTurnConflict(err: unknown): boolean {
  return err instanceof ApiError && isStaleTurnErrorCode(err.code);
}

export default function InterviewWorkspace({
  mode,
  onChangeStatus,
  onExit,
  onReview,
  onViewSession,
}: {
  mode: InterviewModeState;
  /** 顶层状态变化（completed/error 时退出 Interview Mode 前回调） */
  onChangeStatus: (next: InterviewModeState) => void;
  /** 用户点「完成并返回对话」：由上层写入面试完成摘要 artifact 并退出 Interview Mode */
  onExit?: () => void;
  /** 用户点「让 Copilot 复盘」：由上层退出 Interview Mode 并发送 REVIEW_INTERVIEW action */
  onReview?: () => void;
  /** 追溯入口（P3 待收口）：跳到面试记录页并定位该场次 */
  onViewSession?: (sessionId: string) => void;
}) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [current, setCurrent] = useState<InterviewQuestion | null>(null);
  const [coverage, setCoverage] = useState<{ required: string[]; covered: string[] }>({
    required: [],
    covered: [],
  });
  const [coverageOpen, setCoverageOpen] = useState(false);
  const [answer, setAnswer] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  /**
   * 时间预算（P4Q-2）：服务端权威（只统计用户答题时间，模型等待不算）。
   * 剩余 = 服务端 remaining − 本地自同步以来流逝的秒数；无计划的旧会话为 null。
   */
  const [budget, setBudget] = useState<{
    planned: number | null;
    consumed: number;
    remaining: number | null;
  } | null>(null);
  /** 预算调整输入（P4Q-2）：用户说「只剩五分钟」时当轮生效 */
  const [budgetDraft, setBudgetDraft] = useState<number | null>(null);
  const [summary, setSummary] = useState<{ overallScore: number; categoryScores: Array<{ category: string; score: number }> } | null>(null);
  // 本场带来的画像变化（P3 待收口）；拉取失败静默降级（结果卡本身不依赖它）
  const [impact, setImpact] = useState<ProfileImpact | null>(null);
  const timerRef = useRef<number | null>(null);
  const pollRef = useRef<number | null>(null);
  /** 已轮询次数：用于给评估加时间上限，避免失败时无限「评估中」（P4Q-4） */
  const pollAttemptRef = useRef(0);
  /** 错误来自「报告生成失败/超时」而非会话加载：决定错误态给哪个重试动作 */
  const [evaluationFailed, setEvaluationFailed] = useState(false);
  /** 逐轮提交的同步状态（P4-9a）：会话版本 + 待确认的请求标识 */
  const turnSyncRef = useRef<TurnSyncState>(initialTurnSyncState());
  /** 提交一致性冲突的可见说明（原文由服务端给出，前端只负责呈现与回源） */
  const [turnNotice, setTurnNotice] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const stopTimers = useCallback(() => {
    if (timerRef.current) window.clearInterval(timerRef.current);
    if (pollRef.current) window.clearInterval(pollRef.current);
    timerRef.current = null;
    pollRef.current = null;
  }, []);

  // 恢复/拉取会话（权威）：重建「已发生」的题答流 + 定位当前题
  const load = useCallback(async () => {
    try {
      const s = await interviewApi.getSession(mode.sessionId);
      // 恢复规则（只重放已作答轮次 + 定位当前题 + 进度分母）统一在 deriveInterviewView 里，可单测
      const view = deriveInterviewView(s);
      const progress = interviewPlanProgress(s);
      setTurns(view.turns);
      setCurrent(view.current);
      setCoverage({
        required: progress.requiredTopics,
        covered: progress.coveredRequiredTopics,
      });
      setBudget({
        planned: s.plannedDurationMinutes ?? null,
        consumed: s.consumedSeconds ?? 0,
        remaining: s.remainingSeconds ?? null,
      });
      // 顶栏计时与会话状态同步：本地流逝秒数从零重新累计
      setElapsed(0);
      // 会话推进版本跟随权威会话（P4-9a）：提交时原样回传，服务端据此拒绝过期请求
      turnSyncRef.current = syncVersionFromSession(turnSyncRef.current, s.turnVersion);
      if (s.status === 'COMPLETED' || s.status === 'EVALUATED') {
        onChangeStatus({ ...mode, status: 'evaluating' });
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(() => void pollEvaluation(), 3000);
      } else if (view.current) {
        onChangeStatus({ ...mode, status: 'running' });
        if (timerRef.current === null) {
          timerRef.current = window.setInterval(() => setElapsed((e) => e + 1), 1000);
        }
      }
    } catch (err) {
      console.error('InterviewWorkspace 加载会话失败:', err);
      onChangeStatus({ ...mode, status: 'error', error: '面试加载失败，请重试' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode.sessionId]);

  const markCurrentTopicCovered = useCallback(() => {
    if (!current || current.isFollowUp) return;
    setCoverage((previous) => ({
      ...previous,
      covered: previous.required.reduce<string[]>((covered, topic) => {
        if ((covered.includes(topic) || questionMatchesTopic(current, topic))) {
          if (!covered.includes(topic)) covered.push(topic);
        }
        return covered;
      }, [...previous.covered]),
    }));
  }, [current]);

  /** 提交一致性冲突：给出来自服务端的可见说明，并回源同步权威进度（P4-9a） */
  const resyncAfterTurnConflict = useCallback(
    (err: unknown) => {
      setTurnNotice(err instanceof Error ? err.message : '会话已更新，已为你同步最新进度');
      void load();
    },
    [load],
  );

  // 提交答案 → Java 决策引擎返回下一题
  const submit = useCallback(async () => {
    if (!current || !answer.trim() || submitting) return;
    const text = answer.trim();
    const questionId = current.questionId;
    setAnswer('');
    setSubmitting(true);
    setTurnNotice(null);
    // 乐观追加用户答（Java 为权威，失败可重试）
    setTurns((prev) => [...prev, { role: 'user', answer: text }]);
    // 同一次提交（同题同内容）的重试复用同一个标识：服务端据此返回原结果，不再推进第二次
    const attempt = requestIdForAttempt(
      turnSyncRef.current,
      `answer:${questionId}:${text}`,
      () => `turn-${crypto.randomUUID()}`,
    );
    turnSyncRef.current = attempt.state;
    try {
      const res = await interviewApi.submitAnswer({
        sessionId: mode.sessionId,
        questionId,
        answer: text,
        requestId: attempt.requestId,
        expectedVersion: turnSyncRef.current.turnVersion ?? undefined,
      });
      // 版本跟随服务端提交结果：下一次提交必须基于它，否则会被判成过期请求
      turnSyncRef.current = applyTurnResult(turnSyncRef.current, res.turnVersion);
      // 预算随载荷刷新（P4Q-2）：本地计时清零，剩余从服务端权威值重新起算
      setBudget((prev) =>
        prev
          ? { ...prev, consumed: res.consumedSeconds ?? prev.consumed, remaining: res.remainingSeconds ?? null }
          : prev,
      );
      setElapsed(0);
      markCurrentTopicCovered();
      const next = res.hasNextQuestion ? res.nextQuestion : null;
      if (res.transitionMessage) {
        setTurns((prev) => [
          ...prev,
          { role: 'interviewer', transition: true, question: res.transitionMessage ?? undefined },
        ]);
      }
      if (next) {
        setCurrent(next);
        setTurns((prev) => [...prev, toInterviewerTurn(next)]);
      } else {
        // 面试结束 → 异步整场评估轮询
        setCurrent(null);
        setEvaluationFailed(false);
        onChangeStatus({ ...mode, status: 'evaluating' });
        pollAttemptRef.current = 0;
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(
          () => void pollEvaluation(),
          EVALUATION_POLL_INTERVAL_MS,
        );
      }
    } catch (err) {
      console.error('提交答案失败:', err);
      // 移除乐观的用户答，允许重试
      setTurns((prev) => prev.slice(0, -1));
      setAnswer(text);
      if (isTurnConflict(err)) resyncAfterTurnConflict(err);
    } finally {
      setSubmitting(false);
    }
  }, [current, answer, submitting, mode, onChangeStatus, resyncAfterTurnConflict, markCurrentTopicCovered]);

  const pollEvaluation = useCallback(async () => {
    try {
      const s = await interviewApi.getSession(mode.sessionId);
      pollAttemptRef.current += 1;
      // 评估中与评估失败在 status 上都是 COMPLETED，必须靠 evaluateStatus 与轮询上限区分
      const decision = decideEvaluationPhase({
        status: s.status,
        evaluateStatus: s.evaluateStatus,
        attempt: pollAttemptRef.current,
      });
      if (decision.phase === 'waiting') return;

      if (pollRef.current) window.clearInterval(pollRef.current);
      if (decision.phase === 'ready') {
        const report = await interviewApi.getReport(mode.sessionId);
        setSummary({
          overallScore: report.overallScore,
          categoryScores: report.categoryScores,
        });
        // 画像变化是旁路增强：拉不到就降级为只显示报告分，不阻塞结果卡
        interviewApi
          .getProfileImpact(mode.sessionId)
          .then((result) => setImpact(result))
          .catch((err) => console.error('拉取画像变化失败:', err));
        stopTimers();
        onChangeStatus({ ...mode, status: 'completed' });
        return;
      }

      // 评估失败 / 超时：停止轮询并给出可见状态与重试入口（此前只会静默停在「评估中」）
      const reason = s.evaluateError ? `${decision.message}（${s.evaluateError}）` : decision.message;
      setEvaluationFailed(true);
      onChangeStatus({ ...mode, status: 'error', error: reason });
    } catch (err) {
      // 会话不存在（已删除/被清理）→ 停止轮询，避免无限「评估中」
      if (pollRef.current) window.clearInterval(pollRef.current);
      console.error('轮询面试评估失败（可能会话已删除）:', err);
      setEvaluationFailed(false);
      onChangeStatus({ ...mode, status: 'error', error: '面试会话不存在或已删除，请返回对话。' });
    }
  }, [mode, onChangeStatus, stopTimers]);

  /**
   * 跳过当前题（P4Q-5 一等动作）。
   *
   * Java 侧语义：不调模型、不追问、不计分、不产生画像证据——与「答错」严格区分。
   * 与 submit 一样先乐观追加轨迹，失败回滚。
   */
  const skip = useCallback(async () => {
    if (!current || submitting) return;
    const skippedQuestionId = current.questionId;
    setSubmitting(true);
    setAnswer('');
    setTurnNotice(null);
    setTurns((prev) => [...prev, { role: 'user', answer: '（已跳过本题）', answerState: 'SKIPPED' }]);
    // 跳过与提交走同一条推进链路，因此用同一套标识 / 版本约定（P4-9a）
    const attempt = requestIdForAttempt(
      turnSyncRef.current,
      `skip:${skippedQuestionId}`,
      () => `turn-${crypto.randomUUID()}`,
    );
    turnSyncRef.current = attempt.state;
    try {
      const res = await interviewApi.skipQuestion(
        mode.sessionId,
        skippedQuestionId,
        attempt.requestId,
        turnSyncRef.current.turnVersion ?? undefined,
      );
      turnSyncRef.current = applyTurnResult(turnSyncRef.current, res.turnVersion);
      setBudget((prev) =>
        prev
          ? { ...prev, consumed: res.consumedSeconds ?? prev.consumed, remaining: res.remainingSeconds ?? null }
          : prev,
      );
      setElapsed(0);
      markCurrentTopicCovered();
      const next = res.hasNextQuestion ? res.nextQuestion : null;
      if (next) {
        setCurrent(next);
        setTurns((prev) => [...prev, toInterviewerTurn(next)]);
      } else {
        setCurrent(null);
        setEvaluationFailed(false);
        onChangeStatus({ ...mode, status: 'evaluating' });
        pollAttemptRef.current = 0;
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(
          () => void pollEvaluation(),
          EVALUATION_POLL_INTERVAL_MS,
        );
      }
    } catch (err) {
      console.error('跳过失败:', err);
      setTurns((prev) => prev.slice(0, -1));
      if (isTurnConflict(err)) resyncAfterTurnConflict(err);
    } finally {
      setSubmitting(false);
    }
  }, [current, submitting, mode, onChangeStatus, pollEvaluation, resyncAfterTurnConflict, markCurrentTopicCovered]);

  /** 重试生成报告：重置轮询计数后重新入队并继续轮询 */
  const retryEvaluation = useCallback(async () => {
    try {
      await interviewApi.retryEvaluation(mode.sessionId);
      pollAttemptRef.current = 0;
      setEvaluationFailed(false);
      onChangeStatus({ ...mode, status: 'evaluating', error: null });
      if (pollRef.current) window.clearInterval(pollRef.current);
      pollRef.current = window.setInterval(
        () => void pollEvaluation(),
        EVALUATION_POLL_INTERVAL_MS,
      );
    } catch (err) {
      console.error('重试报告生成失败:', err);
      onChangeStatus({ ...mode, status: 'error', error: '重试失败，请稍后再试。' });
    }
  }, [mode, onChangeStatus, pollEvaluation]);

  // 结束面试（提前交卷）→ Java 置 COMPLETED → 进入评估轮询
  const finish = useCallback(async () => {
    setTurnNotice(null);
    // 结束与逐轮推进同一并发边界：带标识即可安全重试，不会重复入队评估（P4-9a）
    const attempt = requestIdForAttempt(
      turnSyncRef.current,
      'complete',
      () => `turn-${crypto.randomUUID()}`,
    );
    turnSyncRef.current = attempt.state;
    try {
      await interviewApi.completeInterview(
        mode.sessionId,
        attempt.requestId,
        turnSyncRef.current.turnVersion ?? undefined,
      );
      setCurrent(null);
      setEvaluationFailed(false);
      onChangeStatus({ ...mode, status: 'evaluating' });
      pollAttemptRef.current = 0;
      if (pollRef.current) window.clearInterval(pollRef.current);
      pollRef.current = window.setInterval(
        () => void pollEvaluation(),
        EVALUATION_POLL_INTERVAL_MS,
      );
    } catch (err) {
      console.error('结束面试失败:', err);
      if (isTurnConflict(err)) resyncAfterTurnConflict(err);
    }
  }, [mode, onChangeStatus, pollEvaluation, resyncAfterTurnConflict]);

  useEffect(() => {
    void load();
    return stopTimers;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 底部自动滚动（新题/新答）
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [turns, current]);

  const isEvaluating = mode.status === 'evaluating';
  const isDone = mode.status === 'completed';
  const answeredCount = turns.filter((turn) => turn.role === 'user').length;
  const currentTopic = current?.topic || current?.category || '未标注话题';
  const usedSeconds = (budget?.consumed ?? 0) + elapsed;

  return (
    <div className="flex h-full min-w-0 flex-col overflow-hidden">
      {/* 轻量顶部状态栏 */}
      <div className="flex shrink-0 items-center justify-between gap-3 border-b border-slate-200/70 bg-white/85 px-5 py-2.5 backdrop-blur dark:border-slate-700 dark:bg-slate-900/85">
        <div className="flex min-w-0 items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
          <Bot className="h-4 w-4 shrink-0 text-primary-500" />
          <span className="truncate">{mode.title}</span>
          {mode.difficulty && (
            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-medium text-slate-500 dark:bg-slate-700 dark:text-slate-300">
              {DIFFICULTY_LABELS[mode.difficulty] ?? mode.difficulty}
            </span>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-3 text-xs text-slate-400">
          {!isDone && !isEvaluating && current && (
            <span className="max-w-36 truncate" title={currentTopic}>话题：{currentTopic}</span>
          )}
          {!isDone && !isEvaluating && (
            <span className="tabular-nums">已答 {answeredCount} 轮</span>
          )}
          {!isDone && !isEvaluating && coverage.required.length > 0 && (
            <div className="relative">
              <button
                type="button"
                aria-expanded={coverageOpen}
                onClick={() => setCoverageOpen((open) => !open)}
                className="rounded-md px-1.5 py-0.5 tabular-nums text-emerald-600 transition hover:bg-emerald-50 dark:text-emerald-400 dark:hover:bg-emerald-950/40"
                title="查看必要覆盖明细"
              >
                覆盖 {coverage.covered.length}/{coverage.required.length}
              </button>
              {coverageOpen && (
                <div
                  data-testid="interview-coverage-detail"
                  className="absolute right-0 top-full z-20 mt-2 w-48 rounded-xl border border-slate-200 bg-white p-2.5 shadow-lg dark:border-slate-700 dark:bg-slate-800"
                >
                  <p className="mb-2 text-[11px] font-semibold text-slate-500 dark:text-slate-300">
                    必要覆盖
                  </p>
                  <div className="space-y-1.5">
                    {coverage.required.map((topic) => {
                      const covered = coverage.covered.includes(topic);
                      return (
                        <div key={topic} className="flex items-center justify-between gap-2 text-xs">
                          <span className="truncate text-slate-600 dark:text-slate-200">{topic}</span>
                          <span className={covered ? 'text-emerald-600' : 'text-slate-400'}>
                            {covered ? '已覆盖' : '待覆盖'}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          )}
          {!isDone && !isEvaluating && budget?.remaining != null && (
            <span
              className="inline-flex items-center gap-1 tabular-nums text-slate-500 dark:text-slate-400"
              title="只统计答题时间，模型思考与等待不扣时"
            >
              <Clock className="h-3.5 w-3.5" />
              已用 {formatSeconds(usedSeconds)} · 剩余 {Math.max(0, Math.ceil((budget.remaining - elapsed) / 60))} 分钟
            </span>
          )}
          {!isDone && !isEvaluating && budget?.remaining == null && (
            <span className="inline-flex items-center gap-1 tabular-nums"><Clock className="h-3.5 w-3.5" />已用 {formatSeconds(usedSeconds)}</span>
          )}
          {!isDone && !isEvaluating && budget?.planned != null && budgetDraft != null && (
            <input
              type="number"
              min={1}
              max={120}
              value={budgetDraft}
              onChange={(event) => setBudgetDraft(Number(event.target.value))}
              onBlur={() => {
                if (budgetDraft && budgetDraft >= 1) {
                  void interviewApi
                    .updateBudget(mode.sessionId, budgetDraft)
                    .then(() => void load());
                }
                setBudgetDraft(null);
              }}
              className="w-14 rounded-md border border-slate-200 px-1.5 py-0.5 text-xs tabular-nums dark:border-slate-700"
              title="输入剩余分钟数后回车/失焦生效"
            />
          )}
          {!isDone && !isEvaluating && budget?.planned != null && budgetDraft == null && (
            <button
              onClick={() => setBudgetDraft(Math.max(1, Math.round((budget.remaining ?? 0) / 60)))}
              className="rounded-md px-1.5 py-0.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-800"
              title="告诉面试官你还剩多少时间（当轮生效）"
            >
              调整时间
            </button>
          )}
          {isEvaluating && (
            <span className="inline-flex items-center gap-1 text-amber-500">
              <Loader2 className="h-3.5 w-3.5 animate-spin" /> 评估中…
            </span>
          )}
          {!isDone && !isEvaluating && (
            <button
              onClick={() => void skip()}
              disabled={submitting}
              className="rounded-md px-2 py-1 font-medium text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 disabled:opacity-40 dark:hover:bg-slate-800"
              title="跳过当前问题并切换到下一个主话题"
            >
              换话题
            </button>
          )}
          {!isDone && !isEvaluating && (
            <button
              onClick={() => void finish()}
              className="inline-flex items-center gap-1 rounded-md px-2 py-1 font-medium text-slate-400 transition hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/30"
            >
              <X className="h-3.5 w-3.5" /> 结束面试
            </button>
          )}
        </div>
      </div>

      {/* 中部：面试题/答消息流（复用普通气泡视觉） */}
      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-4xl space-y-5 px-5 py-6 lg:px-8">
      {/* 错误态：报告生成失败的重试是「重新入队评估」，加载失败的重试是「重新拉会话」 */}
      {mode.status === 'error' && (
        <div className="py-12 text-center">
          <p className="text-sm text-red-500">{mode.error ?? '面试加载失败'}</p>
          <button
            onClick={() => void (evaluationFailed ? retryEvaluation() : load())}
            className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white dark:bg-white dark:text-slate-900"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            {evaluationFailed ? '重新生成报告' : '重试'}
          </button>
        </div>
      )}

          {mode.status === 'starting' && !current && (
            <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-400">
              <Loader2 className="h-4 w-4 animate-spin" /> 正在进入面试…
            </div>
          )}

          {turns.map((turn, i) =>
            turn.role === 'interviewer' ? (
              <div key={`i-${i}`} className="flex gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-primary-500 to-indigo-600 text-white">
                  <Bot className="h-4 w-4" />
                </div>
                <div className="max-w-[80%] rounded-2xl rounded-tl-sm bg-white px-4 py-3 shadow-sm ring-1 ring-slate-100 dark:bg-slate-800 dark:ring-slate-700">
                  <p className="flex items-center gap-2 text-[11px] font-semibold text-slate-400">
                    {turn.transition ? '面试官' : (turn.category || '面试官')}
                    {turn.isFollowUp && (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold text-amber-600 dark:bg-amber-900/40 dark:text-amber-300">
                        追问{turn.followUpIndex ? ` ${turn.followUpIndex}` : ''}
                      </span>
                    )}
                  </p>
                  <p className="mt-1 text-sm leading-relaxed text-slate-800 dark:text-slate-100">{turn.question}</p>
                </div>
              </div>
            ) : (
              <div key={`u-${i}`} className="flex flex-row-reverse gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                  <User className="h-4 w-4" />
                </div>
                <div
                  className={`max-w-[80%] rounded-2xl rounded-tr-sm px-4 py-3 text-sm leading-relaxed shadow-sm ring-1 ${
                    turn.answerState && turn.answerState !== 'ANSWERED'
                      ? 'bg-slate-50 italic text-slate-400 ring-slate-200 dark:bg-slate-800 dark:text-slate-400 dark:ring-slate-700'
                      : 'bg-white text-slate-800 ring-slate-200 dark:bg-slate-700 dark:text-slate-100'
                  }`}
                >
                  {turn.answer}
                </div>
              </div>
            ),
          )}

          {/* 评估中 / 结束态 */}
          {isEvaluating && (
            <div className="flex items-center justify-center gap-2 py-10 text-sm text-slate-500 dark:text-slate-300">
              <Loader2 className="h-4 w-4 animate-spin text-primary-500" />
              面试已完成，正在生成评估报告…
            </div>
          )}

          {isDone && summary && (
            <div className="mx-auto max-w-md rounded-2xl bg-white px-5 py-4 text-center shadow-sm ring-1 ring-slate-100 dark:bg-slate-800 dark:ring-slate-700">
              <p className="text-xs font-bold uppercase tracking-widest text-slate-400">面试完成</p>
              <p className="mt-1 text-3xl font-bold text-slate-900 dark:text-white">{summary.overallScore}</p>
              <div className="mt-3 flex flex-wrap justify-center gap-2">
                {summary.categoryScores.slice(0, 6).map((c) => (
                  <span key={c.category} className="rounded-lg bg-slate-50 px-2 py-1 text-xs dark:bg-slate-700/50">
                    <span className="text-slate-500 dark:text-slate-300">{c.category}</span>{' '}
                    <span className="font-bold text-slate-800 dark:text-white">{c.score}</span>
                  </span>
                ))}
              </div>
              {/* P3 待收口：不止展示最新静态分，还要说清「这场让画像变了什么、凭什么」 */}
              {impact && impact.skills.length > 0 && onViewSession && (
                <ProfileImpactCard impact={impact} onViewSession={onViewSession} />
              )}
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {onReview && (
                  <button
                    onClick={onReview}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition hover:bg-primary-600 dark:bg-white dark:text-slate-900 dark:hover:bg-primary-400"
                  >
                    <Sparkles className="h-4 w-4" />
                    让 Copilot 复盘
                  </button>
                )}
                {onExit && (
                  <button
                    onClick={onExit}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-600 transition hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
                  >
                    完成并返回对话
                  </button>
                )}
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      {/* 底部输入：Interview Mode 复用 Composer 区（由 CopilotPage 渲染，见 InterviewComposer） */}
      <InterviewAnswerBar
        visible={!isEvaluating && !isDone && !!current}
        submitting={submitting}
        answer={answer}
        notice={turnNotice}
        onAnswerChange={setAnswer}
        onSubmit={() => void submit()}
        onSkip={() => void skip()}
      />
    </div>
  );
}

/** Interview Mode 的底部答题输入（视觉结构与 Composer 一致：底部圆角输入条） */
function InterviewAnswerBar({
  visible,
  submitting,
  answer,
  notice,
  onAnswerChange,
  onSubmit,
  onSkip,
}: {
  visible: boolean;
  submitting: boolean;
  answer: string;
  /** 提交一致性提示（P4-9a）：服务端已同步权威进度时告知用户，而不是静默失败 */
  notice: string | null;
  onAnswerChange: (value: string) => void;
  onSubmit: () => void;
  /** 跳过本题：一等动作，不等同于答错（P4Q-5） */
  onSkip: () => void;
}) {
  if (!visible) return null;
  return (
    <div className="mx-auto w-full max-w-4xl px-5 pb-4 lg:px-8">
      {notice && (
        <p className="mb-2 rounded-xl bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:bg-amber-900/30 dark:text-amber-200">
          {notice}
        </p>
      )}
      <div className="rounded-2xl border border-slate-200 bg-white p-2.5 shadow-[0_12px_35px_rgba(15,23,42,0.08)] transition dark:border-slate-700 dark:bg-slate-800 dark:shadow-none">
        <div className="flex items-end gap-2">
          <textarea
            value={answer}
            onChange={(e) => onAnswerChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                onSubmit();
              }
            }}
            rows={1}
            placeholder="输入你的回答…"
            disabled={submitting}
            className="max-h-32 min-h-[2.5rem] flex-1 resize-none bg-transparent px-2 py-1.5 text-sm leading-6 text-slate-800 outline-none placeholder:text-slate-400 disabled:opacity-60 dark:text-white dark:placeholder:text-slate-500"
          />
          <button
            onClick={onSkip}
            disabled={submitting}
            title="跳过本题（不计分，也不算答错）"
            className="flex h-10 shrink-0 items-center gap-1 rounded-xl border border-slate-300 px-3 text-sm font-semibold text-slate-500 transition hover:bg-slate-50 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
          >
            跳过
          </button>
          <button
            onClick={onSubmit}
            disabled={submitting || !answer.trim()}
            className="flex h-10 shrink-0 items-center gap-1 rounded-xl bg-primary-600 px-4 text-sm font-semibold text-white shadow-md transition hover:-translate-y-0.5 hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : '提交回答'}
          </button>
        </div>
      </div>
    </div>
  );
}
