import type {
  QuestionGenStatus,
  QuestionGenStatusResponse,
} from '../api/knowledgebase';
import {
  DEFAULT_STUCK_AFTER_MS,
  type AsyncFailure,
  isStuck,
  taskFailed,
  timeoutFailure,
} from '../utils/asyncFlow.ts';

export interface QuestionGenerationNotice {
  tone: 'info' | 'success' | 'warning' | 'error';
  text: string;
  /**
   * 统一失败判据（P6-1）：null = 非失败态。
   * 「任务失败」与「等待超时」是两种不同的事，界面文案与重试口径都由它区分。
   */
  failure: AsyncFailure | null;
  /** 是否给「重新生成」出口（失败与等待超时都给，纯等待不给） */
  retryable: boolean;
}

type NoticeSource = Pick<
  QuestionGenStatusResponse,
  'questionGenStatus' | 'savedCount' | 'skippedCount'
> & Partial<Pick<QuestionGenStatusResponse, 'message' | 'error' | 'updatedAt'>>;

export function isQuestionGenerationActive(status?: QuestionGenStatus | null): boolean {
  return status === 'QUEUED' || status === 'PROCESSING';
}

export function shouldRefreshGeneratedQuestions(
  previousStatus: QuestionGenStatus | null | undefined,
  nextStatus: QuestionGenStatus,
  sameTask: boolean
): boolean {
  return sameTask
    && isQuestionGenerationActive(previousStatus)
    && nextStatus === 'COMPLETED';
}

/**
 * 生成状态 → 界面提示（P6-1 起带统一失败判据与超时出口）。
 *
 * 等待超过阈值（默认与其它流程同一阈值）仍没有进展时，从「生成中」转为**可重试**的提示：
 * 任务可能根本没入队，让用户一直看着转圈是假进展。
 */
export function getQuestionGenerationNotice(
  status: NoticeSource,
  options: { now?: number; stuckAfterMs?: number } = {}
): QuestionGenerationNotice | null {
  const { now = Date.now(), stuckAfterMs = DEFAULT_STUCK_AFTER_MS } = options;
  switch (status.questionGenStatus) {
    case 'QUEUED':
    case 'PROCESSING': {
      if (isStuck(status.updatedAt, stuckAfterMs, now)) {
        return {
          tone: 'warning',
          text: '生成任务等待时间较长，可以重新发起（已生成的题目不会丢失）',
          failure: timeoutFailure('生成任务等待超时'),
          retryable: true,
        };
      }
      return {
        tone: 'info',
        text: status.questionGenStatus === 'QUEUED'
          ? '任务已提交，正在等待生成题目…'
          : '正在生成题目，期间可以继续管理已有题目…',
        failure: null,
        retryable: false,
      };
    }
    case 'COMPLETED':
      return {
        tone: status.skippedCount > 0 ? 'warning' : 'success',
        text: status.message
          || `已生成 ${status.savedCount} 道题，跳过 ${status.skippedCount} 道题`,
        failure: null,
        retryable: false,
      };
    case 'FAILED':
      // 不回显 backend error：它可能带连接串/内部主机名（既有约定，别丢）
      return {
        tone: 'error',
        text: '题目生成失败，请稍后重试',
        failure: taskFailed('题目生成失败'),
        retryable: true,
      };
    case 'NONE':
    default:
      return null;
  }
}
