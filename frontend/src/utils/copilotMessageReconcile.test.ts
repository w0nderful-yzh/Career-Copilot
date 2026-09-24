import assert from 'node:assert/strict';
import test from 'node:test';

import {
  countFollowingMessages,
  reconcileMessageId,
  resolveJavaMessageId,
} from './copilotMessageReconcile.ts';
import type { ConversationMessage, CopilotMessage } from '../types/copilot.ts';

function local(id: string, role: CopilotMessage['role'], content: string): CopilotMessage {
  return { id, role, content, blocks: [], status: 'done' };
}

function remote(id: number, role: ConversationMessage['role'], content: string): ConversationMessage {
  return { id, role, content, blocks: null, status: 'COMPLETED', createdAt: '' };
}

test('按位置与角色对齐，返回 Java 消息 id', () => {
  const locals = [local('a', 'user', '第一问'), local('b', 'assistant', '第一答')];
  const remotes = [remote(11, 'USER', '第一问'), remote(12, 'ASSISTANT', '第一答')];

  assert.deepEqual(reconcileMessageId(locals, 0, remotes), { status: 'ok', javaId: 11 });
  assert.deepEqual(reconcileMessageId(locals, 1, remotes), { status: 'ok', javaId: 12 });
});

test('本地比后端长：最后一轮尚未落库，返回 pending 而不是删错', () => {
  const locals = [
    local('a', 'user', '第一问'),
    local('b', 'assistant', '第一答'),
    local('c', 'user', '第二问'),
  ];
  const remotes = [remote(11, 'USER', '第一问'), remote(12, 'ASSISTANT', '第一答')];

  // remoteCount 让调用方区分「这一轮没写完」与「整轮都没落库」
  assert.deepEqual(reconcileMessageId(locals, 2, remotes), { status: 'pending', remoteCount: 2 });
});

test('角色错位判为 mismatch', () => {
  const locals = [local('a', 'user', '第一问'), local('b', 'assistant', '第一答')];
  const remotes = [remote(11, 'ASSISTANT', '第一答'), remote(12, 'USER', '第一问')];

  assert.deepEqual(reconcileMessageId(locals, 0, remotes), { status: 'mismatch', remoteCount: 2 });
});

test('用户消息可要求内容一致，防止编辑到另一条消息上', () => {
  const locals = [local('a', 'user', '第一问')];
  const remotes = [remote(11, 'USER', '被别处改过的问题')];

  assert.deepEqual(reconcileMessageId(locals, 0, remotes), { status: 'ok', javaId: 11 });
  assert.deepEqual(
    reconcileMessageId(locals, 0, remotes, { requireContent: true }),
    { status: 'mismatch', remoteCount: 1 },
  );
});

test('助手消息不比对内容：停止生成时两端中断点可能差一段', () => {
  const locals = [
    local('a', 'user', '问'),
    { ...local('b', 'assistant', '已产出'), status: 'stopped' as const },
  ];
  const remotes = [remote(11, 'USER', '问'), remote(12, 'ASSISTANT', '已产出的更多内容')];

  assert.deepEqual(
    reconcileMessageId(locals, 1, remotes, { requireContent: true }),
    { status: 'ok', javaId: 12 },
  );
});

test('落在落库时延内的 pending 会重试直到成功', async () => {
  const locals = [local('a', 'user', '问'), local('b', 'assistant', '答')];
  let calls = 0;

  const outcome = await resolveJavaMessageId({
    localMessages: locals,
    index: 1,
    delayMs: 0,
    attempts: 3,
    loadRemote: async () => {
      calls += 1;
      return calls < 3 ? [remote(11, 'USER', '问')] : [remote(11, 'USER', '问'), remote(12, 'ASSISTANT', '答')];
    },
  });

  assert.deepEqual(outcome, { status: 'ok', javaId: 12 });
  assert.equal(calls, 3);
});

test('重试耗尽仍然是 pending，交由调用方提示稍后再试', async () => {
  const locals = [local('a', 'user', '问')];

  const outcome = await resolveJavaMessageId({
    localMessages: locals,
    index: 0,
    delayMs: 0,
    attempts: 2,
    loadRemote: async () => [],
  });

  assert.deepEqual(outcome, { status: 'pending', remoteCount: 0 });
});

test('countFollowingMessages 给出「将删除其后 N 条」', () => {
  const locals = [local('a', 'user', ''), local('b', 'assistant', ''), local('c', 'user', '')];

  assert.equal(countFollowingMessages(locals, 0), 2);
  assert.equal(countFollowingMessages(locals, 2), 0);
  assert.equal(countFollowingMessages(locals, 9), 0);
});
