import { useCallback, useEffect, useRef, useState } from 'react';
import { Bot, Clock, Loader2, RotateCcw, User, X } from 'lucide-react';
import { interviewApi } from '../../api/interview';
import type { InterviewModeState } from '../../types/copilot';
import type { InterviewQuestion, InterviewSession } from '../../types/interview';

// Interview Mode 主工作区（Interview Mode 重构）：
// - 顶部轻量状态栏：方向 · 题号进度 · 计时 · [结束面试]
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

interface Turn {
  role: 'interviewer' | 'user';
  questionIndex?: number;
  question?: string;
  category?: string;
  isFollowUp?: boolean;
  answer?: string;
}

export default function InterviewWorkspace({
  mode,
  onChangeStatus,
  onExit,
}: {
  mode: InterviewModeState;
  /** 顶层状态变化（completed/error 时退出 Interview Mode 前回调） */
  onChangeStatus: (next: InterviewModeState) => void;
  /** 用户点「完成并返回对话」：由上层写入面试完成摘要 artifact 并退出 Interview Mode */
  onExit?: () => void;
}) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [current, setCurrent] = useState<InterviewQuestion | null>(null);
  const [sessionMeta, setSessionMeta] = useState<Pick<InterviewSession, 'totalQuestions'> | null>(null);
  const [answer, setAnswer] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [summary, setSummary] = useState<{ overallScore: number; categoryScores: Array<{ category: string; score: number }> } | null>(null);
  const timerRef = useRef<number | null>(null);
  const pollRef = useRef<number | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const stopTimers = useCallback(() => {
    if (timerRef.current) window.clearInterval(timerRef.current);
    if (pollRef.current) window.clearInterval(pollRef.current);
    timerRef.current = null;
    pollRef.current = null;
  }, []);

  // 从 Java 会话构建「已发生」的题/答流（权威渲染）
  const buildTurns = useCallback((s: InterviewSession): Turn[] => {
    const list: Turn[] = [];
    for (let i = 0; i <= s.currentQuestionIndex && i < s.questions.length; i++) {
      const q = s.questions[i];
      list.push({
        role: 'interviewer',
        questionIndex: i,
        question: q.question,
        category: q.category,
        isFollowUp: q.isFollowUp,
      });
      if (q.userAnswer) {
        list.push({ role: 'user', answer: q.userAnswer });
      }
    }
    return list;
  }, []);

  // 恢复/拉取会话（权威）：重建已答流 + 定位当前题
  const load = useCallback(async () => {
    try {
      const s = await interviewApi.getSession(mode.sessionId);
      setSessionMeta({ totalQuestions: s.totalQuestions });
      setTurns(buildTurns(s));
      const idx = Math.min(s.currentQuestionIndex, s.questions.length - 1);
      const q = s.questions[idx] ?? null;
      setCurrent(q);
      if (s.status === 'COMPLETED' || s.status === 'EVALUATED') {
        onChangeStatus({ ...mode, status: 'evaluating' });
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(() => void pollEvaluation(), 3000);
      } else if (q) {
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

  // 提交答案 → Java 决策引擎返回下一题
  const submit = useCallback(async () => {
    if (!current || !answer.trim() || submitting) return;
    const text = answer.trim();
    setAnswer('');
    setSubmitting(true);
    // 乐观追加用户答（Java 为权威，失败可重试）
    setTurns((prev) => [...prev, { role: 'user', answer: text }]);
    try {
      const res = await interviewApi.submitAnswer({
        sessionId: mode.sessionId,
        questionIndex: current.questionIndex,
        answer: text,
      });
      const next = res.hasNextQuestion ? res.nextQuestion : null;
      if (next) {
        setCurrent(next);
        setTurns((prev) => [
          ...prev,
          {
            role: 'interviewer',
            questionIndex: next.questionIndex,
            question: next.question,
            category: next.category,
            isFollowUp: next.isFollowUp,
          },
        ]);
      } else {
        // 面试结束 → 异步整场评估轮询
        setCurrent(null);
        onChangeStatus({ ...mode, status: 'evaluating' });
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(() => void pollEvaluation(), 3000);
      }
    } catch (err) {
      console.error('提交答案失败:', err);
      // 移除乐观的用户答，允许重试
      setTurns((prev) => prev.slice(0, -1));
      setAnswer(text);
    } finally {
      setSubmitting(false);
    }
  }, [current, answer, submitting, mode, onChangeStatus]);

  const pollEvaluation = useCallback(async () => {
    try {
      const s = await interviewApi.getSession(mode.sessionId);
      if (s.status === 'EVALUATED') {
        if (pollRef.current) window.clearInterval(pollRef.current);
        const report = await interviewApi.getReport(mode.sessionId);
        setSummary({
          overallScore: report.overallScore,
          categoryScores: report.categoryScores,
        });
        stopTimers();
        onChangeStatus({ ...mode, status: 'completed' });
      } else if (s.status === 'COMPLETED') {
        setSessionMeta({ totalQuestions: s.totalQuestions });
      } else {
        // 意外回到未完成（理论不发生），继续轮询
      }
    } catch (err) {
      // 会话不存在（已删除/被清理）→ 停止轮询，避免无限「评估中」
      if (pollRef.current) window.clearInterval(pollRef.current);
      console.error('轮询面试评估失败（可能会话已删除）:', err);
      onChangeStatus({ ...mode, status: 'error', error: '面试会话不存在或已删除，请返回对话。' });
    }
  }, [mode, onChangeStatus, stopTimers]);

  // 结束面试（提前交卷）→ Java 置 COMPLETED → 进入评估轮询
  const finish = useCallback(async () => {
    try {
      await interviewApi.completeInterview(mode.sessionId);
      setCurrent(null);
      onChangeStatus({ ...mode, status: 'evaluating' });
      if (pollRef.current) window.clearInterval(pollRef.current);
      pollRef.current = window.setInterval(() => void pollEvaluation(), 3000);
    } catch (err) {
      console.error('结束面试失败:', err);
    }
  }, [mode, onChangeStatus]);

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
  const total = sessionMeta?.totalQuestions ?? 0;

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
            <span className="tabular-nums">第 {current.questionIndex + 1} / {total} 题</span>
          )}
          {!isDone && !isEvaluating && (
            <span className="inline-flex items-center gap-1 tabular-nums"><Clock className="h-3.5 w-3.5" />{formatSeconds(elapsed)}</span>
          )}
          {isEvaluating && (
            <span className="inline-flex items-center gap-1 text-amber-500">
              <Loader2 className="h-3.5 w-3.5 animate-spin" /> 评估中…
            </span>
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
          {mode.status === 'error' && (
            <div className="py-12 text-center">
              <p className="text-sm text-red-500">{mode.error ?? '面试加载失败'}</p>
              <button
                onClick={() => void load()}
                className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white dark:bg-white dark:text-slate-900"
              >
                <RotateCcw className="h-3.5 w-3.5" /> 重试
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
                    {turn.category || '面试官'}
                    {turn.isFollowUp && (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold text-amber-600 dark:bg-amber-900/40 dark:text-amber-300">
                        追问
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
                <div className="max-w-[80%] rounded-2xl rounded-tr-sm bg-white px-4 py-3 text-sm leading-relaxed text-slate-800 shadow-sm ring-1 ring-slate-200 dark:bg-slate-700 dark:text-slate-100">
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
              {onExit && (
                <button
                  onClick={onExit}
                  className="mt-4 inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition hover:bg-primary-600 dark:bg-white dark:text-slate-900 dark:hover:bg-primary-400"
                >
                  完成并返回对话
                </button>
              )}
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
        onAnswerChange={setAnswer}
        onSubmit={() => void submit()}
      />
    </div>
  );
}

/** Interview Mode 的底部答题输入（视觉结构与 Composer 一致：底部圆角输入条） */
function InterviewAnswerBar({
  visible,
  submitting,
  answer,
  onAnswerChange,
  onSubmit,
}: {
  visible: boolean;
  submitting: boolean;
  answer: string;
  onAnswerChange: (value: string) => void;
  onSubmit: () => void;
}) {
  if (!visible) return null;
  return (
    <div className="mx-auto w-full max-w-4xl px-5 pb-4 lg:px-8">
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
