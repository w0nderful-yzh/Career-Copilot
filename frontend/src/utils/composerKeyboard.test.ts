import assert from 'node:assert/strict';
import test from 'node:test';

import {
  COMPOSITION_END_GUARD_MS,
  IDLE_COMPOSITION,
  isComposingEvent,
  shouldSendOnEnter,
  type ComposerKeyEvent,
  type CompositionState,
} from './composerKeyboard.ts';

const enter: ComposerKeyEvent = { key: 'Enter', shiftKey: false };

test('普通 Enter 发送', () => {
  assert.equal(shouldSendOnEnter(enter), true);
});

test('Shift+Enter 换行，不发送', () => {
  assert.equal(shouldSendOnEnter({ ...enter, shiftKey: true }), false);
});

test('其他按键不发送（即使按住 Shift）', () => {
  assert.equal(shouldSendOnEnter({ key: 'a', shiftKey: false }), false);
  assert.equal(shouldSendOnEnter({ key: 'Escape', shiftKey: false }), false);
});

test('输入法合成中的 Enter 不发送（选词不能触发误发）', () => {
  // Chrome / Firefox：合成期间 isComposing=true
  assert.equal(shouldSendOnEnter({ ...enter, isComposing: true }), false);
  // 部分输入法只给哨兵 keyCode
  assert.equal(shouldSendOnEnter({ ...enter, keyCode: 229 }), false);
  assert.equal(
    shouldSendOnEnter(enter, { composing: true, endedAt: 0 }),
    false,
  );
});

test('Safari：compositionend 之后的同帧 keydown 仍不发送', () => {
  const state: CompositionState = { composing: false, endedAt: 1000 };
  assert.equal(shouldSendOnEnter({ ...enter, timeStamp: 1030 }, state), false);
  // 超过守卫窗口后恢复正常发送
  assert.equal(
    shouldSendOnEnter({ ...enter, timeStamp: 1000 + COMPOSITION_END_GUARD_MS }, state),
    true,
  );
});

test('时间戳回绕时按非合成处理，不能永久吞掉 Enter', () => {
  const state: CompositionState = { composing: false, endedAt: 5000 };
  assert.equal(shouldSendOnEnter({ ...enter, timeStamp: 10 }, state), true);
});

test('从未合成过时不受时间窗影响', () => {
  assert.equal(shouldSendOnEnter({ ...enter, timeStamp: 10 }, IDLE_COMPOSITION), true);
});

test('Escape 的合成判定与 Enter 一致（输入法用 Esc 取消候选）', () => {
  assert.equal(isComposingEvent({ key: 'Escape', shiftKey: false, isComposing: true }), true);
  assert.equal(
    isComposingEvent({ key: 'Escape', shiftKey: false }, { composing: true, endedAt: 0 }),
    true,
  );
  assert.equal(isComposingEvent({ key: 'Escape', shiftKey: false }), false);
});
