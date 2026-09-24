import { useLayoutEffect, useRef } from 'react';

/**
 * 受控 textarea 的自动增高。
 *
 * 受控 textarea 不会自己长高：不按 scrollHeight 重算高度，多行输入就只能在
 * min-height 那点空间里滚动，Shift+Enter 换行看起来像「没生效」。
 *
 * @param value 受控值（作为重算触发器）
 * @param maxHeight 最大高度（px），超过后改为内部滚动
 */
export function useAutoGrowTextarea(value: string, maxHeight: number) {
  const ref = useRef<HTMLTextAreaElement>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    // 先归零再读 scrollHeight：否则内容变短时高度不会回缩
    element.style.height = 'auto';
    element.style.height = `${Math.min(element.scrollHeight, maxHeight)}px`;
    element.style.overflowY = element.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, [value, maxHeight]);

  return ref;
}
