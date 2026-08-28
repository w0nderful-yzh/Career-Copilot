import assert from 'node:assert/strict';
import test from 'node:test';

import { resolveActionRoute } from './routes.ts';

test('RESUME_DETAIL 使用受控 resumeId 构造详情路由', () => {
  assert.deepEqual(resolveActionRoute('RESUME_DETAIL', { resumeId: 42 }), {
    path: '/history/42',
    label: '查看简历分析',
  });
});

test('RESUME_DETAIL 拒绝缺失或非法 resumeId', () => {
  assert.equal(resolveActionRoute('RESUME_DETAIL'), null);
  assert.equal(resolveActionRoute('RESUME_DETAIL', { resumeId: '../settings' }), null);
  assert.equal(resolveActionRoute('RESUME_DETAIL', { resumeId: -1 }), null);
});

test('未知 Agent 路由不会生成任意跳转地址', () => {
  assert.equal(resolveActionRoute('https://example.com'), null);
});

test('静态白名单路由保持可用', () => {
  assert.deepEqual(resolveActionRoute('INTERVIEW_CREATE', { resumeId: 42 }), {
    path: '/interview-hub',
    label: '开始模拟面试',
  });
});

test('INTERVIEW_SESSION 使用受控 sessionId 构造面试页路由（P1-4）', () => {
  assert.deepEqual(
    resolveActionRoute('INTERVIEW_SESSION', { sessionId: 'abc123def4567890' }),
    { path: '/interview/session/abc123def4567890', label: '进入面试' },
  );
});

test('INTERVIEW_SESSION 拒绝缺失或非法 sessionId（P1-4）', () => {
  assert.equal(resolveActionRoute('INTERVIEW_SESSION'), null);
  assert.equal(resolveActionRoute('INTERVIEW_SESSION', { sessionId: '../settings' }), null);
  assert.equal(resolveActionRoute('INTERVIEW_SESSION', { sessionId: 'ABC' }), null);
  assert.equal(resolveActionRoute('INTERVIEW_SESSION', { sessionId: 12345 }), null);
});
