import assert from 'node:assert/strict';
import test from 'node:test';

import { decideEvaluationPhase, EVALUATION_MAX_ATTEMPTS } from './evaluatePolling.ts';

test('报告已生成时停止轮询并进入 ready', () => {
  const decision = decideEvaluationPhase({
    status: 'EVALUATED',
    evaluateStatus: 'COMPLETED',
    attempt: 3,
  });

  assert.equal(decision.phase, 'ready');
  assert.equal(decision.shouldContinue, false);
});

test('评估中继续轮询：PENDING 与 PROCESSING 都算等待', () => {
  for (const evaluateStatus of ['PENDING', 'PROCESSING', null] as const) {
    const decision = decideEvaluationPhase({
      status: 'COMPLETED',
      evaluateStatus,
      attempt: 2,
    });

    assert.equal(decision.phase, 'waiting');
    assert.equal(decision.shouldContinue, true);
  }
});

test('评估失败立即停止轮询并给出重试说明（不能一直显示评估中）', () => {
  const decision = decideEvaluationPhase({
    status: 'COMPLETED',
    evaluateStatus: 'FAILED',
    attempt: 1,
  });

  assert.equal(decision.phase, 'failed');
  assert.equal(decision.shouldContinue, false);
  assert.match(decision.message, /重试/);
});

test('失败优先于超时：先失败就不必等到上限', () => {
  const decision = decideEvaluationPhase({
    status: 'COMPLETED',
    evaluateStatus: 'FAILED',
    attempt: EVALUATION_MAX_ATTEMPTS + 10,
  });

  assert.equal(decision.phase, 'failed');
});

test('达到轮询上限仍未出结果按超时处理，并说明作答不会丢', () => {
  const decision = decideEvaluationPhase({
    status: 'COMPLETED',
    evaluateStatus: 'PROCESSING',
    attempt: EVALUATION_MAX_ATTEMPTS,
  });

  assert.equal(decision.phase, 'timeout');
  assert.equal(decision.shouldContinue, false);
  assert.match(decision.message, /超时/);
  assert.match(decision.message, /不会丢失/);
});

test('未达上限时正常等待（边界：上限前一次仍等待）', () => {
  const decision = decideEvaluationPhase({
    status: 'COMPLETED',
    evaluateStatus: 'PROCESSING',
    attempt: EVALUATION_MAX_ATTEMPTS - 1,
  });

  assert.equal(decision.phase, 'waiting');
});

test('status=EVALUATED 优先于 evaluateStatus=FAILED（报告已落库即视为可用）', () => {
  const decision = decideEvaluationPhase({
    status: 'EVALUATED',
    evaluateStatus: 'FAILED',
    attempt: 5,
  });

  assert.equal(decision.phase, 'ready');
});
