import assert from 'node:assert/strict';
import test from 'node:test';

import {
  isAllPatchesSelected,
  toggleAllPatchSelection,
  togglePatchSelection,
} from './resumePatchSelection.ts';

const IDS = ['patch_1', 'patch_2', 'patch_3'];

test('togglePatchSelection 增加与移除单条勾选，且不修改原集合', () => {
  const origin = new Set(['patch_1']);
  const added = togglePatchSelection(origin, 'patch_2');
  assert.deepEqual([...added].sort(), ['patch_1', 'patch_2']);
  assert.deepEqual([...origin], ['patch_1'], '原集合不可被就地修改');

  const removed = togglePatchSelection(added, 'patch_1');
  assert.deepEqual([...removed], ['patch_2']);
});

test('isAllPatchesSelected 只在全部勾选时为真，空集合不算全选', () => {
  assert.equal(isAllPatchesSelected(new Set(IDS), IDS), true);
  assert.equal(isAllPatchesSelected(new Set(['patch_1']), IDS), false);
  assert.equal(isAllPatchesSelected(new Set(), IDS), false);
  assert.equal(isAllPatchesSelected(new Set(), []), false);
});

test('toggleAllPatchSelection：全选态 → 全不选', () => {
  const next = toggleAllPatchSelection(new Set(IDS), IDS);
  assert.equal(next.size, 0);
});

test('toggleAllPatchSelection：非全选态 → 全选（含部分选中时补全）', () => {
  assert.deepEqual([...toggleAllPatchSelection(new Set(), IDS)], IDS);
  assert.deepEqual([...toggleAllPatchSelection(new Set(['patch_2']), IDS)], IDS);
});

test('toggleAllPatchSelection 返回新集合，可安全交给预览重渲', () => {
  const origin = new Set(['patch_1']);
  const next = toggleAllPatchSelection(origin, IDS);
  assert.notEqual(next, origin);
  assert.deepEqual([...origin], ['patch_1']);
});
