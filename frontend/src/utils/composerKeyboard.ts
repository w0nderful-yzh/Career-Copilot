/**
 * Copilot 输入框的键盘判定：Enter 到底该「发送」还是「换行」。
 *
 * 单独成模块而不是写在 Composer 里，是为了能用 node --test 直接覆盖：
 * 这里最容易错的不是 Enter 分支，而是**输入法合成态**。
 *
 * 中文 / 日文输入法在选词阶段也会派发 Enter：不做合成守卫时，用户按 Enter
 * 确认候选词会被当成发送，消息带着半截拼音直接发出去 —— 这是「回车就直接发送」
 * 最主要的来源。Safari 更麻烦：它先派发 compositionend，再派发那次 keydown，
 * 所以此时 isComposing 已经是 false，只能靠「合成刚刚结束」的时间窗兜住。
 */

/** 合成结束后的守卫窗口（毫秒）：Safari 的 compositionend → keydown 落在同一帧内 */
export const COMPOSITION_END_GUARD_MS = 60;

/** 合成态判定所需的 keydown 字段子集（KeyboardEvent 与 React 合成事件都能满足） */
export interface ComposerKeyEvent {
  key: string;
  shiftKey: boolean;
  /** event.isComposing：标准字段，Chrome / Firefox 合成期间为 true */
  isComposing?: boolean;
  /** 各家浏览器在合成期间给 keydown 的哨兵 keyCode（229） */
  keyCode?: number;
  /** event.timeStamp：与 performance.now() 同一时间基准 */
  timeStamp?: number;
}

/** 输入框维护的合成态（由 compositionstart / compositionend 更新） */
export interface CompositionState {
  composing: boolean;
  /** 最近一次 compositionend 的时间戳，0 表示从未合成过 */
  endedAt: number;
}

export const IDLE_COMPOSITION: CompositionState = { composing: false, endedAt: 0 };

/** React 键盘事件的最小结构（不依赖 React 类型，便于用 node --test 覆盖） */
export interface ReactKeyboardLike {
  key: string;
  shiftKey: boolean;
  nativeEvent?: {
    isComposing?: boolean;
    keyCode?: number;
    timeStamp?: number;
  } | null;
}

/**
 * 从 React 键盘事件提取判定字段。
 *
 * 字段映射集中在此处：Composer 与消息内联编辑器各需要一份，
 * 分散写两遍最容易在后续改动中漂移（漏掉 isComposing 就会重现误发）。
 */
export function toComposerKeyEvent(event: ReactKeyboardLike): ComposerKeyEvent {
  const native = event.nativeEvent;
  return {
    key: event.key,
    shiftKey: event.shiftKey,
    isComposing: native?.isComposing,
    keyCode: native?.keyCode,
    timeStamp: native?.timeStamp,
  };
}

/**
 * 事件是否仍处于输入法合成过程（含 Safari 的「刚结束」窗口）。
 *
 * 用于两处：Enter 不发送、Escape 不当作「停止生成」（输入法用 Esc 取消候选）。
 */
export function isComposingEvent(
  event: ComposerKeyEvent,
  state: CompositionState = IDLE_COMPOSITION,
): boolean {
  if (event.isComposing) return true;
  if (event.keyCode === 229) return true;
  if (state.composing) return true;
  const timestamp = event.timeStamp;
  if (typeof timestamp !== 'number' || state.endedAt === 0) return false;
  // 时间戳回绕 / 逆向差值视为非合成：宁可漏判一次，也不能把正常 Enter 永久吞掉
  const elapsed = timestamp - state.endedAt;
  return elapsed >= 0 && elapsed < COMPOSITION_END_GUARD_MS;
}

/**
 * Enter 是否应该触发发送。
 *
 * 返回 false 的四种情况：非 Enter、按住 Shift（换行）、合成中、合成刚结束。
 */
export function shouldSendOnEnter(
  event: ComposerKeyEvent,
  state: CompositionState = IDLE_COMPOSITION,
): boolean {
  if (event.key !== 'Enter') return false;
  if (event.shiftKey) return false;
  if (isComposingEvent(event, state)) return false;
  return true;
}
