// 简历优化 Patch 勾选（纯函数）：选择状态同时决定两件事
// 1) 应用哪些 patch（APPLY_RESUME_PATCHES 的 patchIds）
// 2) Preview PDF 的渲染输入（勾选变化必须重渲，否则「界面已全选、预览还是上一版」）
//
// 抽成独立模块的原因：全选/全不选此前只 setState、漏掉重渲调度，
// 使预览与选择状态脱钩；这条语义需要被单测固化而不是埋在组件里。

/** 单条勾选/取消（不可变返回新集合） */
export function togglePatchSelection(
  selected: ReadonlySet<string>,
  patchId: string,
): Set<string> {
  const next = new Set(selected);
  if (next.has(patchId)) {
    next.delete(patchId);
  } else {
    next.add(patchId);
  }
  return next;
}

/**
 * 是否处于全选。
 * 空集合不算全选（无 patch 的提案不会渲染卡片，此处只保证按钮语义不反转）。
 */
export function isAllPatchesSelected(
  selected: ReadonlySet<string>,
  patchIds: readonly string[],
): boolean {
  return patchIds.length > 0 && patchIds.every((id) => selected.has(id));
}

/** 全选/全不选：按「当前是否全选」翻转，返回目标集合（调用方需据此重渲预览） */
export function toggleAllPatchSelection(
  selected: ReadonlySet<string>,
  patchIds: readonly string[],
): Set<string> {
  return isAllPatchesSelected(selected, patchIds) ? new Set() : new Set(patchIds);
}
