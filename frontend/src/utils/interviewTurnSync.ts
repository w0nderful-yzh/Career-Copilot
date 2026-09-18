/**
 * 逐轮提交的同步状态（P4-9a）。
 *
 * 服务端用「请求标识 + 会话推进版本」保证重复提交只推进一次、过期提交被拒绝。
 * 前端要配合的只有两件事：
 *   1. **同一次提交的重试复用同一个标识** —— 服务端据此返回原结果而不是再推进一次；
 *   2. **把服务端返回的版本原样带回去** —— 否则自己就会被判成过期请求。
 *
 * 两者都不涉及 React，抽成纯函数以便单测（组件里只保留 ref 与调用）。
 */

/** 一次待确认的提交：key 用于识别「这是不是同一次提交的重试」 */
export interface TurnAttempt {
  key: string;
  requestId: string;
}

export interface TurnSyncState {
  /** 服务端最新已知的会话推进版本；null = 尚未从会话或响应里读到 */
  turnVersion: number | null;
  /** 上一次尚未确认成功的提交；重试时复用它 */
  pending: TurnAttempt | null;
}

/**
 * 「会话已被推进 / 已结束」这一类错误码（与 Java ErrorCode 对应）：
 * 3004 面试已完成、3010 同标识不同载荷、3011 版本过期、3013 不是当前待答题。
 *
 * 这些情况下服务端已经同步了权威进度，前端应当回源重读，而不是原样重试。
 */
const STALE_TURN_ERROR_CODES: ReadonlySet<number> = new Set([3004, 3010, 3011, 3013]);

export function initialTurnSyncState(): TurnSyncState {
  return { turnVersion: null, pending: null };
}

/** 从会话读取同步版本；服务端未返回版本时保持原值（不回退成 null） */
export function syncVersionFromSession(
  state: TurnSyncState,
  turnVersion?: number | null,
): TurnSyncState {
  if (typeof turnVersion !== 'number') {
    return state;
  }
  return { ...state, turnVersion };
}

/**
 * 取本次提交要用的请求标识。
 *
 * 同一个 key（会话 + 题号 + 载荷）视为「同一次提交的重试」，复用上一个标识；
 * 换了题或改了答案就是一次新提交，换新标识。
 */
export function requestIdForAttempt(
  state: TurnSyncState,
  key: string,
  generateRequestId: () => string,
): { state: TurnSyncState; requestId: string } {
  if (state.pending?.key === key) {
    return { state, requestId: state.pending.requestId };
  }
  const requestId = generateRequestId();
  return { state: { ...state, pending: { key, requestId } }, requestId };
}

/** 提交成功：推进版本并清掉待确认标识（下一次提交是新的一次） */
export function applyTurnResult(
  state: TurnSyncState,
  turnVersion?: number | null,
): TurnSyncState {
  return {
    turnVersion: typeof turnVersion === 'number' ? turnVersion : state.turnVersion,
    pending: null,
  };
}

/** 是否需要回源同步会话（而不是把这次失败当成网络抖动原样重试） */
export function isStaleTurnErrorCode(code?: number): boolean {
  return typeof code === 'number' && STALE_TURN_ERROR_CODES.has(code);
}
