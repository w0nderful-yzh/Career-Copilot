// 报告评估轮询的判定逻辑（纯函数）。
//
// 抽出来的原因与 interviewTurns / profileImpact 相同：「什么时候该继续等、什么时候算失败、
// 等到多久算超时」是有明确产品语义的状态机，必须可被单测固化。
//
// 关键背景（P4Q-4）：会话 status 只会走到 COMPLETED 或 EVALUATED，评估失败与评估中
// 在 status 上完全一样，只有 evaluateStatus 能区分——只用 status 判定就会出现
// 「评估已经失败，界面永远显示评估中」的无限转圈。

import type { AsyncTaskStatus, InterviewSession } from '../types/interview';

export type EvaluationPhase =
  /** 仍在评估：继续轮询 */
  | 'waiting'
  /** 报告已生成：停止轮询并展示结果 */
  | 'ready'
  /** 评估任务失败：停止轮询，给失败原因与重试入口 */
  | 'failed'
  /** 超过轮询上限仍未出结果：停止轮询，给重试入口 */
  | 'timeout';

export interface EvaluationDecision {
  phase: EvaluationPhase;
  /** 是否继续轮询 */
  shouldContinue: boolean;
  /** 展示给用户的说明（ready 时为空） */
  message: string;
}

/** 轮询间隔与上限：报告是一次 LLM 调用，正常几十秒；2 分钟仍无结果按超时处理 */
export const EVALUATION_POLL_INTERVAL_MS = 3000;
export const EVALUATION_MAX_ATTEMPTS = 40;

export function decideEvaluationPhase(params: {
  status: InterviewSession['status'];
  evaluateStatus?: AsyncTaskStatus | null;
  /** 已轮询次数（含本次） */
  attempt: number;
  maxAttempts?: number;
}): EvaluationDecision {
  const maxAttempts = params.maxAttempts ?? EVALUATION_MAX_ATTEMPTS;

  if (params.status === 'EVALUATED') {
    return { phase: 'ready', shouldContinue: false, message: '' };
  }
  if (params.evaluateStatus === 'FAILED') {
    return {
      phase: 'failed',
      shouldContinue: false,
      message: '报告生成失败。你可以重试，或先在「面试记录」查看本场作答。',
    };
  }
  if (params.attempt >= maxAttempts) {
    return {
      phase: 'timeout',
      shouldContinue: false,
      message: '报告生成超时。可以重试，已完成作答不会丢失。',
    };
  }
  return { phase: 'waiting', shouldContinue: true, message: '' };
}
