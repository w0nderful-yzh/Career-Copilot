import assert from 'node:assert/strict';
import test from 'node:test';

import {
  applyTurnResult,
  initialTurnSyncState,
  isStaleTurnErrorCode,
  requestIdForAttempt,
  syncVersionFromSession,
} from './interviewTurnSync.ts';

test('同一次提交的重试复用同一个标识：服务端才能返回原结果而不是再推进一次', () => {
  const first = requestIdForAttempt(initialTurnSyncState(), 'answer:0:堆和方法区', () => 'turn-1');
  assert.equal(first.requestId, 'turn-1');

  // 同一题同一内容再来一次 = 重试，标识必须相同
  const retry = requestIdForAttempt(first.state, 'answer:0:堆和方法区', () => 'turn-2');
  assert.equal(retry.requestId, 'turn-1');

  // 换了答案就是一次新提交，换新标识
  const changed = requestIdForAttempt(retry.state, 'answer:0:换成别的答案', () => 'turn-3');
  assert.equal(changed.requestId, 'turn-3');
});

test('提交成功后版本推进到服务端返回的值，并清掉待确认标识', () => {
  const started = requestIdForAttempt(initialTurnSyncState(), 'answer:0：x', () => 'turn-1');

  const done = applyTurnResult(started.state, 4);

  assert.equal(done.turnVersion, 4);
  assert.equal(done.pending, null);
  // 下一次提交是一次全新的提交
  assert.equal(requestIdForAttempt(done, 'answer:0：x', () => 'turn-9').requestId, 'turn-9');
});

test('服务端未返回版本时不清空已同步的版本（避免自己把自己判成过期）', () => {
  const known = syncVersionFromSession(initialTurnSyncState(), 3);

  assert.equal(syncVersionFromSession(known, undefined).turnVersion, 3);
  assert.equal(syncVersionFromSession(known, null).turnVersion, 3);
  assert.equal(applyTurnResult(known, undefined).turnVersion, 3);
});

test('会话读取带回的版本覆盖旧值（刷新/切会话后按权威值提交）', () => {
  const state = syncVersionFromSession(syncVersionFromSession(initialTurnSyncState(), 3), 7);
  assert.equal(state.turnVersion, 7);
});

test('过期与已结束的错误码需要回源同步，网络类错误不需要', () => {
  // 3004 已完成 / 3010 同标识不同载荷 / 3011 版本过期 / 3013 不是当前待答题
  for (const code of [3004, 3010, 3011, 3013]) {
    assert.equal(isStaleTurnErrorCode(code), true);
  }
  // 3012（正在处理中）与网络失败都不该触发回源：原样重试即可
  for (const code of [3012, 3001, 500, undefined]) {
    assert.equal(isStaleTurnErrorCode(code), false);
  }
});
