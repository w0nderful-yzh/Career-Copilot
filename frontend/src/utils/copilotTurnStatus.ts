import type { MessageStatus } from '../types/copilot';
import { taskFailed, userStopped, type AsyncFailure } from './asyncFlow';

/**
 * 把 Java 侧的消息终态映射为前端渲染态。
 *
 * Java `AgentMessageEntity.MessageStatus` 有三态（COMPLETED / STOPPED / FAILED），
 * 前端 MessageStatus 是渲染概念，两者需显式转换（P1 待收口：停止生成必须与正常完成区分，
 * 否则刷新后「已停止」会被当成回答完毕）。
 *
 * 未知或缺失取值一律按正常完成处理：历史数据与老接口不带 status 时，
 * 不能把正常消息误标成异常。
 */
export function toMessageStatus(serverStatus?: string | null): MessageStatus {
  switch (serverStatus) {
    case 'STOPPED':
      return 'stopped';
    case 'FAILED':
      return 'error';
    default:
      return 'done';
  }
}

/** 历史回放没有实时错误详情，失败轮次给一条兜底文案 */
export const FAILED_TURN_HINT = '本轮生成失败，可重新发送';

/** P6-1：失败可以重发，用户主动停止是正常终态，不显示自动重试。 */
export function getCopilotTurnFailure(
  status: MessageStatus,
  error?: string | null,
): AsyncFailure | null {
  if (status === 'error') {
    return taskFailed(error || '处理失败，请稍后重试');
  }
  if (status === 'stopped') {
    return userStopped('已停止生成');
  }
  return null;
}
