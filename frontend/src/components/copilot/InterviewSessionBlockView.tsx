import { useCallback, useEffect, useRef, useState } from 'react';
import { CheckCircle2, Clock, Loader2, RotateCcw, Send, Sparkles, Trophy } from 'lucide-react';
import { interviewApi } from '../../api/interview';
import type { ChoiceOption, InterviewSessionBlock, InterviewLiveStatus } from '../../types/copilot';
import type { InterviewQuestion, InterviewSession, InterviewReport } from '../../types/interview';

// P4-0 内嵌面试会话块：答题直连 Java Interview API，块内自管理状态机。
// - 拉取会话 → 展示当前题 → 提交答案（Java 决策引擎返回下一题/结束）→ 结束轮询整场评估 → 结果卡
// - 面试运行期由上层隐藏普通 Composer；本块不向 Conversation 写入每轮内容。
// P4-6a：结果卡提供「让 Copilot 复盘」→ 通过 onActionSelect 触发 REVIEW_INTERVIEW action。

const DIFFICULTY_LABELS: Record<string, string> = {
  junior: '校招',
  mid: '中级',
  senior: '高级',
};

const MODE_LABELS: Record<string, string> = { TEXT: '文字', VOICE: '语音' };

function formatSeconds(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export default function InterviewSessionBlockView({
  block,
  onActionSelect,
}: {
  block: InterviewSessionBlock;
  onActionSelect?: (option: ChoiceOption) => void;
}) {
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [question, setQuestion] = useState<InterviewQuestion | null>(null);
  const [status, setStatus] = useState<InterviewLiveStatus>('loading');
  const [answer, setAnswer] = useState('');
  const [error, setError] = useState('');
  const [report, setReport] = useState<InterviewReport | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const timerRef = useRef<number | null>(null);
  const pollRef = useRef<number | null>(null);

  const stopTimers = useCallback(() => {
    if (timerRef.current) window.clearInterval(timerRef.current);
    if (pollRef.current) window.clearInterval(pollRef.current);
    timerRef.current = null;
    pollRef.current = null;
  }, []);

  // 拉取会话（块挂载 / 重试时）
  const load = useCallback(async () => {
    setStatus('loading');
    setError('');
    try {
      const s = await interviewApi.getSession(block.sessionId);
      setSession(s);
      const idx = Math.min(s.currentQuestionIndex, s.questions.length - 1);
      const current = s.questions[idx] ?? null;
      setQuestion(current);
      if (s.status === 'COMPLETED' || s.status === 'EVALUATED') {
        setStatus('evaluating');
        // 已完成但可能仍在评估/已评估：轮询直到能取到报告
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(() => void pollEvaluation(), 3000);
      } else if (current) {
        setStatus('running');
        if (timerRef.current === null) {
          timerRef.current = window.setInterval(() => setElapsed((e) => e + 1), 1000);
        }
      } else {
        setStatus('completed');
      }
    } catch (err) {
      console.error('加载内嵌面试失败:', err);
      setError('面试会话加载失败，请刷新或稍后重试');
      setStatus('error');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [block.sessionId]);

  // 提交答案 → Java 决策引擎返回下一题 / 结束
  const submit = useCallback(async () => {
    if (!session || !question || !answer.trim()) return;
    const submitted = answer.trim();
    setAnswer('');
    setStatus('answering');
    try {
      const res = await interviewApi.submitAnswer({
        sessionId: block.sessionId,
        questionIndex: question.questionIndex,
        answer: submitted,
      });
      if (res.hasNextQuestion && res.nextQuestion) {
        setQuestion(res.nextQuestion);
        // 题单推进：把已答题目挂到本地会话（仅用于展示，Java 才是权威）
        setSession((prev) => (prev ? { ...prev, currentQuestionIndex: res.currentIndex } : prev));
        setStatus('running');
      } else {
        // 面试结束（自适应：主问题已全部作答）→ 进入异步整场评估轮询
        setStatus('evaluating');
        if (pollRef.current) window.clearInterval(pollRef.current);
        pollRef.current = window.setInterval(() => void pollEvaluation(), 3000);
      }
    } catch (err) {
      console.error('提交答案失败:', err);
      setError('提交答案失败，请重试');
      setStatus('running');
    }
  }, [session, question, answer, block.sessionId]);

  // 轮询评估结果（面试完成后 Java 异步整场评估）
  const pollEvaluation = useCallback(async () => {
    try {
      const s = await interviewApi.getSession(block.sessionId);
      if (s.status === 'EVALUATED') {
        if (pollRef.current) window.clearInterval(pollRef.current);
        const r = await interviewApi.getReport(block.sessionId);
        setReport(r);
        setSession(s);
        setStatus('completed');
        stopTimers();
      } else if (s.status === 'COMPLETED') {
        // 仍在评估中，继续轮询
        setSession(s);
      } else {
        // 意外回到未完成状态（理论上不会）
        if (pollRef.current) window.clearInterval(pollRef.current);
        setStatus('error');
        setError('面试状态异常，请到面试记录页查看');
      }
    } catch (err) {
      console.error('轮询评估失败:', err);
      // 网络抖动：保持轮询，不置错误
    }
  }, [block.sessionId, stopTimers]);

  useEffect(() => {
    void load();
    return stopTimers;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 展示字段（skillId/difficulty 由 Agent 创建块时给出；会话模型不带这些字段）
  const directionLabel = block.directionName ?? block.skillId ?? '模拟面试';
  const difficultyLabel = DIFFICULTY_LABELS[block.difficulty ?? ''] ?? '';
  const modeLabel = MODE_LABELS[block.mode] ?? '';

  const progressPct = useMemoProgress(session, question);

  return (
    <div className="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800">
      {/* Header */}
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/80 px-4 py-3 dark:border-slate-700 dark:bg-slate-800/80">
        <div className="min-w-0">
          <p className="truncate text-sm font-bold text-slate-900 dark:text-white">
            {directionLabel}{difficultyLabel ? ` · ${difficultyLabel}` : ''}{modeLabel ? ` · ${modeLabel}` : ''}
          </p>
          <p className="text-xs text-slate-400">
            内嵌模拟面试 {status === 'evaluating' ? '· 评估中' : status === 'completed' ? '· 已完成' : ''}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2 text-xs text-slate-400">
          {status === 'running' && (
            <span className="inline-flex items-center gap-1"><Clock className="h-3.5 w-3.5" />{formatSeconds(elapsed)}</span>
          )}
          {status === 'completed' && <CheckCircle2 className="h-4 w-4 text-emerald-500" />}
        </div>
      </div>

      {/* Body */}
      <div className="px-4 py-4">
        {status === 'loading' && (
          <div className="flex items-center justify-center gap-2 py-8 text-sm text-slate-400">
            <Loader2 className="h-4 w-4 animate-spin" /> 正在加载面试…
          </div>
        )}

        {status === 'error' && (
          <div className="py-6 text-center">
            <p className="text-sm text-red-500">{error}</p>
            <button
              onClick={() => void load()}
              className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white dark:bg-white dark:text-slate-900"
            >
              <RotateCcw className="h-3.5 w-3.5" /> 重试
            </button>
          </div>
        )}

        {(status === 'running' || status === 'answering') && question && (
          <div>
            {/* Progress */}
            {progressPct !== null && (
              <div className="mb-3">
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
                  <div className="h-full rounded-full bg-gradient-to-r from-primary-500 to-indigo-500 transition-all" style={{ width: `${progressPct}%` }} />
                </div>
                <p className="mt-1 text-right text-[11px] text-slate-400">
                  已推进 · {Math.min(question.questionIndex + 1, session?.totalQuestions ?? question.questionIndex + 1)} 题
                </p>
              </div>
            )}
            {/* Question */}
            <div className="rounded-xl bg-slate-50 px-4 py-3 dark:bg-slate-700/50">
              <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {question.category || '面试官'}
              </p>
              <p className="mt-1 text-base font-medium leading-relaxed text-slate-900 dark:text-white">
                {question.question}
              </p>
              {question.isFollowUp && (
                <p className="mt-1 inline-flex rounded bg-amber-50 px-1.5 py-0.5 text-[11px] font-semibold text-amber-600 dark:bg-amber-900/40 dark:text-amber-300">
                  追问
                </p>
              )}
            </div>
            {/* Answer */}
            <textarea
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) void submit();
              }}
              disabled={status === 'answering'}
              placeholder="输入你的回答…（Ctrl/⌘ + Enter 提交）"
              rows={3}
              className="mt-3 w-full resize-none rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 placeholder:text-slate-400 focus:border-primary-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 disabled:opacity-60 dark:border-slate-600 dark:bg-slate-900/60 dark:text-slate-100"
            />
            <div className="mt-2 flex items-center justify-between">
              {error ? <p className="text-xs text-red-500">{error}</p> : <span />}
              <button
                onClick={() => void submit()}
                disabled={status === 'answering' || !answer.trim()}
                className="inline-flex items-center gap-1.5 rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {status === 'answering' ? (
                  <><Loader2 className="h-4 w-4 animate-spin" /> 评估中…</>
                ) : (
                  <><Send className="h-4 w-4" /> 提交回答</>
                )}
              </button>
            </div>
          </div>
        )}

        {status === 'evaluating' && (
          <div className="flex items-center justify-center gap-2 py-8 text-sm text-slate-500 dark:text-slate-300">
            <Loader2 className="h-4 w-4 animate-spin text-primary-500" />
            面试已完成，正在生成评估报告…
          </div>
        )}

        {status === 'completed' && report && (
          <div className="text-center">
            <Trophy className="mx-auto h-7 w-7 text-amber-400" />
            <p className="mt-2 text-lg font-bold text-slate-900 dark:text-white">
              综合得分 {report.overallScore}
            </p>
            {report.categoryScores.length > 0 && (
              <div className="mx-auto mt-3 grid max-w-sm grid-cols-2 gap-2">
                {report.categoryScores.slice(0, 6).map((c) => (
                  <div key={c.category} className="rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-700/50">
                    <p className="text-xs text-slate-500 dark:text-slate-400">{c.category}</p>
                    <p className="text-sm font-bold text-slate-900 dark:text-white">{c.score}</p>
                  </div>
                ))}
              </div>
            )}
            <p className="mx-auto mt-3 max-w-md text-xs leading-relaxed text-slate-400">
              面试完成，评估已写入能力画像。
            </p>
            {onActionSelect && (
              <button
                onClick={() =>
                  onActionSelect({
                    action: 'REVIEW_INTERVIEW',
                    label: '让 Copilot 复盘这次面试',
                    payload: { sessionId: block.sessionId },
                  })
                }
                className="mt-4 inline-flex items-center gap-1.5 rounded-lg bg-gradient-to-r from-primary-500 to-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:-translate-y-0.5 hover:from-primary-600 hover:to-indigo-700"
              >
                <Sparkles className="h-4 w-4" /> 让 Copilot 复盘这次面试
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** 进度百分比：以「当前推进到的题」在题单中的位置估算；无题单时 null */
function useMemoProgress(session: InterviewSession | null, question: InterviewQuestion | null): number | null {
  if (!session || !question) return null;
  const total = session.totalQuestions || session.questions.length;
  if (total <= 0) return null;
  return Math.min(100, Math.round(((question.questionIndex + 1) / total) * 100));
}
