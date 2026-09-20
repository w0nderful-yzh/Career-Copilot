import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DEFAULT_STUCK_AFTER_MS,
  asyncFailure,
  dependencyFailed,
  isStuck,
  isWaitingPhase,
  loadFailed,
  taskFailed,
  timeoutFailure,
  userStopped,
} from './asyncFlow.ts';

test('失败类别彼此区分，且重试出口按类别决定', () => {
  assert.equal(taskFailed('x').kind, 'task_failed');
  assert.equal(dependencyFailed().kind, 'dependency_failed');
  assert.equal(timeoutFailure().kind, 'timeout');
  assert.equal(loadFailed().kind, 'load_failed');
  assert.equal(userStopped().kind, 'user_stopped');

  // 「加载失败」与「任务失败」是两件事：前者业务状态未知，不能显示成任务失败
  assert.notEqual(loadFailed().kind, taskFailed().kind);
  assert.equal(loadFailed('取数失败').retryable, true);
  // 用户主动停止不是错误，也不自动重试
  assert.equal(userStopped().retryable, false);
  assert.equal(taskFailed().retryable, true);
});

test('失败对象自带默认文案，调用方可覆盖', () => {
  assert.equal(taskFailed().message, '任务执行失败');
  assert.equal(
    asyncFailure('dependency_failed', '模型服务不可用').message,
    '模型服务不可用',
  );
});

test('等待阶段只有 loading（轮询判据）', () => {
  assert.equal(isWaitingPhase('loading'), true);
  for (const phase of ['ready', 'empty', 'failed', 'stopped'] as const) {
    assert.equal(isWaitingPhase(phase), false);
  }
});

test('卡住判据：超过阈值才算，没有时间戳不猜', () => {
  const now = Date.parse('2026-09-20T10:00:00Z');
  const fresh = new Date(now - 30_000).toISOString();
  const stale = new Date(now - DEFAULT_STUCK_AFTER_MS - 1).toISOString();

  assert.equal(isStuck(fresh, DEFAULT_STUCK_AFTER_MS, now), false);
  assert.equal(isStuck(stale, DEFAULT_STUCK_AFTER_MS, now), true);
  // 没有时间戳：宁可不提示超时，也不凭空格猜「卡住了」
  assert.equal(isStuck(null, DEFAULT_STUCK_AFTER_MS, now), false);
  assert.equal(isStuck('not-a-date', DEFAULT_STUCK_AFTER_MS, now), false);
});
