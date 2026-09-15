import assert from 'node:assert/strict';
import test from 'node:test';

import {
  deltaText,
  evidenceSourceText,
  evidenceTimeText,
  impactTone,
  orderImpacts,
  sessionIdOf,
} from './profileImpact.ts';
import type { SkillImpact } from '../types/interview.ts';

function impact(overrides: Partial<SkillImpact>): SkillImpact {
  return {
    skill: 'Java',
    beforeScore: 60,
    afterScore: 70,
    delta: 10,
    sessionEvidences: [],
    ...overrides,
  };
}

test('色调：涨/跌/平/新增四态，首次考到不报涨幅', () => {
  assert.equal(impactTone(impact({ beforeScore: 60, delta: 10 })), 'up');
  assert.equal(impactTone(impact({ beforeScore: 60, delta: -8 })), 'down');
  assert.equal(impactTone(impact({ beforeScore: 60, delta: 0 })), 'flat');
  assert.equal(impactTone(impact({ beforeScore: null, delta: 0 })), 'new');
});

test('幅度文本：首次考到显示「本场新增」，其余带符号', () => {
  assert.equal(deltaText(impact({ beforeScore: null })), '本场新增');
  assert.equal(deltaText(impact({ beforeScore: 60, delta: 10 })), '+10');
  assert.equal(deltaText(impact({ beforeScore: 60, delta: -8 })), '-8');
  assert.equal(deltaText(impact({ beforeScore: 60, delta: 0 })), '0');
});

test('展示排序：变化幅度大的在前（含下跌），无变化的殿后', () => {
  const ordered = orderImpacts([
    impact({ skill: '无变化', delta: 0 }),
    impact({ skill: '小涨', delta: 2 }),
    impact({ skill: '大跌', delta: -20 }),
    impact({ skill: '大涨', delta: 15 }),
    impact({ skill: '新增', beforeScore: null, delta: 0 }),
  ]);

  assert.deepEqual(
    ordered.map((item) => item.skill),
    ['新增', '大跌', '大涨', '小涨', '无变化'],
  );
});

test('同幅度时按技能名稳定排序', () => {
  const ordered = orderImpacts([
    impact({ skill: 'Redis', delta: 5 }),
    impact({ skill: 'Java', delta: 5 }),
  ]);

  assert.deepEqual(ordered.map((item) => item.skill), ['Java', 'Redis']);
});

test('证据来源：题号从 0 计，显示为第 N 题；缺失时退化', () => {
  assert.equal(evidenceSourceText({ sourceId: 'abc:2', questionIndex: 2, score: 80 }), '面试 · 第 3 题');
  assert.equal(
    evidenceSourceText({ sourceId: 'abc:0', questionIndex: null, score: 80 }),
    '面试 · 场次记录',
  );
});

test('证据时间格式化；缺失或非法时返回空串（不显示占位假时间）', () => {
  assert.equal(evidenceTimeText('2026-09-15T10:05:00'), '2026-09-15 10:05');
  assert.equal(evidenceTimeText(null), '');
  assert.equal(evidenceTimeText('not-a-date'), '');
});

test('从证据 sourceId 解析场次 ID，用于追溯跳转', () => {
  assert.equal(sessionIdOf({ sourceId: 'abc123:4', questionIndex: 4, score: 80 }), 'abc123');
  assert.equal(sessionIdOf({ sourceId: 'abc123', questionIndex: null, score: 80 }), null);
  assert.equal(sessionIdOf({ sourceId: ':3', questionIndex: 3, score: 80 }), null);
});
