import type { StreamEvent } from '../types/copilot';

/**
 * 发送消息并消费 SSE 流式响应。
 *
 * 通过 AbortSignal 支持取消：取消后抛出 DOMException(AbortError)，
 * 由调用方决定如何标记消息状态。
 */
export async function streamChat(
  message: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
    signal,
  });

  if (!response.ok) {
    throw new Error(`请求失败: HTTP ${response.status}`);
  }
  if (!response.body) {
    throw new Error('浏览器不支持流式响应');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // SSE 帧以空行分隔，逐帧解析
    let separatorIndex: number;
    while ((separatorIndex = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      const dataLine = frame
        .split('\n')
        .find((line) => line.startsWith('data: '));
      if (!dataLine) continue;
      try {
        onEvent(JSON.parse(dataLine.slice(6)) as StreamEvent);
      } catch {
        // 忽略无法解析的帧，不影响后续事件
      }
    }
  }
}