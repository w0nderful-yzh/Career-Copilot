import assert from 'node:assert/strict';
import test from 'node:test';

import { getCopilotTurnFailure, toMessageStatus } from './copilotTurnStatus.ts';

test('COMPLETED 映射为正常完成', () => {
  assert.equal(toMessageStatus('COMPLETED'), 'done');
});

test('STOPPED 映射为已停止（不能与正常完成混淆）', () => {
  assert.equal(toMessageStatus('STOPPED'), 'stopped');
});

test('FAILED 映射为错误态', () => {
  assert.equal(toMessageStatus('FAILED'), 'error');
});

test('缺失或未知终态按正常完成处理，不误标异常', () => {
  assert.equal(toMessageStatus(null), 'done');
  assert.equal(toMessageStatus(undefined), 'done');
  assert.equal(toMessageStatus(''), 'done');
  assert.equal(toMessageStatus('CANCELLED'), 'done');
});

test('生成失败可重发，用户主动停止不是失败且不给自动重试', () => {
  assert.deepEqual(getCopilotTurnFailure('error', '模型调用失败'), {
    kind: 'task_failed',
    message: '模型调用失败',
    retryable: true,
  });
  assert.deepEqual(getCopilotTurnFailure('stopped'), {
    kind: 'user_stopped',
    message: '已停止生成',
    retryable: false,
  });
  assert.equal(getCopilotTurnFailure('done'), null);
});
