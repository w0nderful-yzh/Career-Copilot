import type { EvaluateStatus } from '../api/history';
import { type AsyncFailure, taskFailed } from '../utils/asyncFlow.ts';

export type KnowledgeBaseInterviewCompletion =
  | { kind: 'waiting' }
  | { kind: 'completed'; path: string }
  /** 失败带上统一判据（P6-1）：重试出口与文案不再各写一套 */
  | { kind: 'failed'; retryable: boolean; failure: AsyncFailure };

export function resolveKnowledgeBaseInterviewCompletion(
  evaluateStatus: EvaluateStatus | undefined,
  knowledgeBaseId: number,
  sessionId: string,
): KnowledgeBaseInterviewCompletion {
  if (evaluateStatus === 'COMPLETED') {
    return {
      kind: 'completed',
      path: `/knowledgebase-interview/${knowledgeBaseId}/interviews/${sessionId}`,
    };
  }
  if (evaluateStatus === 'FAILED') {
    const failure = taskFailed('评估报告生成失败');
    return { kind: 'failed', retryable: failure.retryable, failure };
  }
  return { kind: 'waiting' };
}
