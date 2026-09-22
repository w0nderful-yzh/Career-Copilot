import {
  DEFAULT_STUCK_AFTER_MS,
  type AsyncFailure,
  isStuck,
  taskFailed,
  timeoutFailure,
} from '../utils/asyncFlow.ts';

/**
 * 等待多久后提示「可能没入队」。
 *
 * P6-1 起复用统一阈值（{@link DEFAULT_STUCK_AFTER_MS}）：同类等待在不同页面阈值不同，
 * 用户会以为「有的地方快有的地方慢」。
 */
export const VOICE_EVALUATION_RETRY_AFTER_MS = DEFAULT_STUCK_AFTER_MS;

export type VoiceEvaluationTone = 'loading' | 'warning' | 'error' | 'success';

export interface VoiceEvaluationPresentation {
  tone: VoiceEvaluationTone;
  label: string;
  title: string;
  description: string;
  retryable: boolean;
  shouldPoll: boolean;
  /** 统一失败判据（P6-1）：null = 非失败态；「任务失败」与「等待超时」区分开 */
  failure: AsyncFailure | null;
}

interface VoiceEvaluationStatusInput {
  status?: string | null;
  statusUpdatedAt?: string | null;
  now?: number;
}

export function shouldRefreshVoiceEvaluationPresentation(
  status?: string | null,
): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

function hasWaitedPastRetryThreshold(
  statusUpdatedAt: string | null | undefined,
  now: number,
): boolean {
  return isStuck(statusUpdatedAt, VOICE_EVALUATION_RETRY_AFTER_MS, now);
}

export function getVoiceEvaluationPresentation({
  status,
  statusUpdatedAt,
  now = Date.now(),
}: VoiceEvaluationStatusInput): VoiceEvaluationPresentation {
  if (status === 'FAILED') {
    return {
      tone: 'error',
      label: '生成失败',
      title: '评估报告生成失败',
      description: '本次面试记录已保存，你可以重新生成评估报告。',
      retryable: true,
      shouldPoll: false,
      failure: taskFailed('评估报告生成失败'),
    };
  }

  if (status === 'COMPLETED') {
    return {
      tone: 'success',
      label: '已完成',
      title: '评估报告已生成',
      description: '本次面试的分析结果已经准备好。',
      retryable: false,
      shouldPoll: false,
      failure: null,
    };
  }

  if ((status === 'PENDING' || status === 'PROCESSING')
      && hasWaitedPastRetryThreshold(statusUpdatedAt, now)) {
    return {
      tone: 'warning',
      label: '评估延迟',
      title: '评估等待时间较长',
      description: '任务可能没有成功进入队列，你可以重新生成，已保存的面试记录不会丢失。',
      retryable: true,
      shouldPoll: false,
      failure: timeoutFailure('评估任务等待超时'),
    };
  }

  if (status === 'PROCESSING') {
    return {
      tone: 'loading',
      label: '评估中',
      title: 'AI 正在分析本次面试',
      description: '正在整理回答表现和改进建议，你可以先离开此页面。',
      retryable: false,
      shouldPoll: true,
      failure: null,
    };
  }

  return {
    tone: 'loading',
    label: '等待评估',
    title: '正在排队生成评估报告',
    description: '通常会在 10–30 秒内开始分析，你可以先离开此页面。',
    retryable: false,
    shouldPoll: true,
    failure: null,
  };
}
