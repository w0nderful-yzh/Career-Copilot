import assert from 'node:assert/strict';
import test from 'node:test';

import {
  appendListItem,
  bulletsToText,
  createEmptyContent,
  hasCriticalGap,
  isMissingField,
  removeListItem,
  textToBullets,
  updateBasicInfo,
  updateListItem,
} from './resumeContentEdit.ts';

test('createEmptyContent 给出可编辑的骨架，列表为空但 basicInfo 已就位', () => {
  const content = createEmptyContent();
  assert.deepEqual(content.education, []);
  assert.deepEqual(content.experience, []);
  assert.equal(content.basicInfo?.name, '');
});

test('updateBasicInfo 浅合并且不修改原对象', () => {
  const origin = createEmptyContent();
  const next = updateBasicInfo(origin, { name: '张三' });
  assert.equal(next.basicInfo?.name, '张三');
  assert.equal(origin.basicInfo?.name, '', '原内容不可被就地修改');
  // 未提交字段保持原值
  const again = updateBasicInfo(next, { phone: '138' });
  assert.equal(again.basicInfo?.name, '张三');
  assert.equal(again.basicInfo?.phone, '138');
});

test('updateListItem 只改目标项，且容忍 null/越界', () => {
  const items = [{ company: '甲' }, { company: '乙' }];
  const next = updateListItem(items, 1, { company: '丙' });
  assert.deepEqual(next, [{ company: '甲' }, { company: '丙' }]);
  assert.deepEqual(items, [{ company: '甲' }, { company: '乙' }]);
  // 越界不抛错，原样返回
  assert.deepEqual(updateListItem(items, 9, { company: '丁' }), items);
  assert.deepEqual(updateListItem(null, 0, { company: '丁' }), []);
});

test('appendListItem / removeListItem 不可变增删，null 视为空列表', () => {
  const items = ['a'];
  assert.deepEqual(appendListItem(items, 'b'), ['a', 'b']);
  assert.deepEqual(items, ['a']);
  assert.deepEqual(appendListItem(null, 'a'), ['a']);

  assert.deepEqual(removeListItem(['a', 'b', 'c'], 1), ['a', 'c']);
  assert.deepEqual(removeListItem(null, 0), []);
});

test('bullets 与多行文本互转：丢弃空行并去首尾空白', () => {
  assert.equal(bulletsToText(['第一条', '第二条']), '第一条\n第二条');
  assert.equal(bulletsToText(null), '');
  assert.deepEqual(textToBullets('  第一条  \n\n第二条\n   '), ['第一条', '第二条']);
  assert.deepEqual(textToBullets(''), []);
});

test('isMissingField 覆盖精确 path、点号前缀与组合项', () => {
  const missing = ['basicInfo.name', 'education', 'experience/projects'];
  assert.equal(isMissingField(missing, 'basicInfo.name'), true);
  assert.equal(isMissingField(missing, 'basicInfo'), true, '父级前缀应命中');
  assert.equal(isMissingField(missing, 'education'), true);
  assert.equal(isMissingField(missing, 'experience'), true, '组合项 experience/projects 应命中');
  assert.equal(isMissingField(missing, 'projects'), true);
  assert.equal(isMissingField(missing, 'basicInfo.phone'), false);
  assert.equal(isMissingField(null, 'basicInfo.name'), false);
});

test('hasCriticalGap：姓名与联系方式是确认门槛', () => {
  const content = createEmptyContent();
  assert.equal(hasCriticalGap(content), true);
  assert.equal(
    hasCriticalGap(updateBasicInfo(content, { name: '张三' })),
    true,
    '只有姓名仍缺联系方式',
  );
  assert.equal(
    hasCriticalGap(updateBasicInfo(content, { name: '张三', email: 'a@b.com' })),
    false,
  );
});
