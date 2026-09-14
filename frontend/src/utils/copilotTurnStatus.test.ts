import assert from 'node:assert/strict';
import test from 'node:test';

import { toMessageStatus } from './copilotTurnStatus.ts';

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
