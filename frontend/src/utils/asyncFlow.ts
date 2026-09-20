/**
 * 异步流程的统一状态契约（P6-1）。
 *
 * 背景：各异步流程（题目生成、语音评估、报告生成、向量化、简历分析）此前**各自实现**了
 * 自己的 loading / failed / retry 文案与超时出口，于是同一类问题在不同页面表现不一致：
 * 「任务本身失败」和「界面取数失败」都叫失败、「等太久」有的地方给重试有的地方只能干等、
 * 「用户主动停止」被当成错误显示。这里把**判据**收成一份词汇表，文案仍留在各自流程里——
 * 统一的是「这是什么状态、能不能重试」，不是把不同流程的措辞拉平。
 *
 * 五种失败彼此不能混：
 * - `task_failed`：任务本身失败（后端给了原因），重试是合理出口；
 * - `dependency_failed`：任务依赖的服务/模型不可用，重试可能仍有意义但要说清依赖；
 * - `timeout`：等待超过阈值仍无进展（可能根本没入队），必须给重试出口而不是无限转圈；
 * - `load_failed`：只是界面取数失败，业务状态未知——不能显示成「任务失败」；
 * - `user_stopped`：用户主动停止，不是错误，也不该自动重试。
 */

export type AsyncPhase = 'loading' | 'ready' | 'empty' | 'failed' | 'stopped';

export type AsyncFailureKind =
  | 'task_failed'
  | 'dependency_failed'
  | 'timeout'
  | 'load_failed'
  | 'user_stopped';

export interface AsyncFailure {
  kind: AsyncFailureKind;
  message: string;
  /** 是否应该给用户重试出口：用户主动停止不给（要重来得他自己发起） */
  retryable: boolean;
}

/**
 * 「等太久」的统一阈值：各流程默认共用，避免同一类等待在不同页面阈值不同。
 * 需要更宽的流程（如整场报告生成）可显式覆盖，但覆盖要在调用处写清理由。
 */
export const DEFAULT_STUCK_AFTER_MS = 2 * 60 * 1000;

const RETRYABLE: Record<AsyncFailureKind, boolean> = {
  task_failed: true,
  dependency_failed: true,
  timeout: true,
  load_failed: true,
  user_stopped: false,
};

export function asyncFailure(kind: AsyncFailureKind, message: string): AsyncFailure {
  return { kind, message, retryable: RETRYABLE[kind] };
}

export function taskFailed(message = '任务执行失败'): AsyncFailure {
  return asyncFailure('task_failed', message);
}

export function dependencyFailed(message = '依赖服务暂时不可用'): AsyncFailure {
  return asyncFailure('dependency_failed', message);
}

export function timeoutFailure(message = '等待时间过长，任务可能没有开始'): AsyncFailure {
  return asyncFailure('timeout', message);
}

export function loadFailed(message = '数据加载失败'): AsyncFailure {
  return asyncFailure('load_failed', message);
}

export function userStopped(message = '已停止'): AsyncFailure {
  return asyncFailure('user_stopped', message);
}

/** 是否处于「还在等」的阶段（前端据此决定要不要继续轮询） */
export function isWaitingPhase(phase: AsyncPhase): boolean {
  return phase === 'loading';
}

/**
 * 判定「卡住」：更新时间超过阈值仍没有进展。
 *
 * 没有时间戳时返回 false——宁可不给超时提示，也不凭空格猜「卡住了」。
 */
export function isStuck(
  updatedAt: string | null | undefined,
  stuckAfterMs: number = DEFAULT_STUCK_AFTER_MS,
  now: number = Date.now(),
): boolean {
  if (!updatedAt) return false;
  const updated = new Date(updatedAt).getTime();
  return Number.isFinite(updated) && now - updated >= stuckAfterMs;
}
