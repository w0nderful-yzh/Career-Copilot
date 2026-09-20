import assert from 'node:assert/strict';
import test from 'node:test';
import {
  getQuestionGenerationNotice,
  isQuestionGenerationActive,
  shouldRefreshGeneratedQuestions,
} from './questionGenerationStatus.ts';
import { DEFAULT_STUCK_AFTER_MS } from '../utils/asyncFlow.ts';

test('QUEUED 和 PROCESSING 都属于活动生成状态', () => {
  assert.equal(isQuestionGenerationActive('QUEUED'), true);
  assert.equal(isQuestionGenerationActive('PROCESSING'), true);
  assert.equal(isQuestionGenerationActive('COMPLETED'), false);
  assert.equal(isQuestionGenerationActive('FAILED'), false);
});

test('只有正在跟踪的活动任务进入 COMPLETED 时刷新题目', () => {
  assert.equal(shouldRefreshGeneratedQuestions('PROCESSING', 'COMPLETED', true), true);
  assert.equal(shouldRefreshGeneratedQuestions('QUEUED', 'COMPLETED', true), true);
  assert.equal(shouldRefreshGeneratedQuestions('COMPLETED', 'COMPLETED', true), false);
  assert.equal(shouldRefreshGeneratedQuestions('PROCESSING', 'COMPLETED', false), false);
  assert.equal(shouldRefreshGeneratedQuestions(null, 'COMPLETED', true), false);
});

test('状态提示不暴露后端异常细节，但给出统一失败判据', () => {
  assert.deepEqual(getQuestionGenerationNotice({
    questionGenStatus: 'FAILED',
    message: null,
    error: '数据库连接 jdbc:postgresql://internal-host 失败',
    savedCount: 0,
    skippedCount: 0,
  }), {
    tone: 'error',
    text: '题目生成失败，请稍后重试',
    failure: { kind: 'task_failed', message: '题目生成失败', retryable: true },
    retryable: true,
  });
});

test('生成任务等待超时：给可重试提示，而不是一直转圈', () => {
  const now = Date.parse('2026-09-20T10:00:00Z');
  const base = {
    questionGenStatus: 'PROCESSING' as const,
    message: null,
    error: null,
    savedCount: 0,
    skippedCount: 0,
  };

  // 刚提交：纯等待，不给重试（否则用户会误以为失败）
  assert.deepEqual(getQuestionGenerationNotice(
    { ...base, updatedAt: new Date(now - 10_000).toISOString() },
    { now },
  ), {
    tone: 'info',
    text: '正在生成题目，期间可以继续管理已有题目…',
    failure: null,
    retryable: false,
  });

  // 超过统一阈值仍无进展：转成可重试的提示（任务可能根本没入队）
  assert.deepEqual(getQuestionGenerationNotice(
    { ...base, updatedAt: new Date(now - DEFAULT_STUCK_AFTER_MS - 1).toISOString() },
    { now },
  ), {
    tone: 'warning',
    text: '生成任务等待时间较长，可以重新发起（已生成的题目不会丢失）',
    failure: { kind: 'timeout', message: '生成任务等待超时', retryable: true },
    retryable: true,
  });

  // 没有时间戳时不猜「卡住」（老后端/字段缺失时保持原样）
  assert.deepEqual(getQuestionGenerationNotice({ ...base, updatedAt: null }, { now }), {
    tone: 'info',
    text: '正在生成题目，期间可以继续管理已有题目…',
    failure: null,
    retryable: false,
  });
});
