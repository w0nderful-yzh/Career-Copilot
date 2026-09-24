import type { ConversationMessage, CopilotMessage } from '../types/copilot';

/**
 * 本地消息 ↔ Java 消息的对账。
 *
 * 「编辑已发送消息 / 重新生成回答」都要先按 Java messageId 截断，但本地消息 id 是前端生成的
 * （`msg_...`），只有历史加载回来的才是 `saved_<javaId>`；而每轮落库又是 Python 的**脱手任务**
 * （见 agent-service/api/chat.py：停止生成时生成器会被关闭，落库必须与请求生命周期解耦），
 * SSE 结束的那一刻数据可能还没写完。
 *
 * 因此不做「删最后 N 条」这种按数量的猜测——脱手落库有竞态时它会删错历史且不可恢复。
 * 这里改成：先按顺序把本地消息对齐到 Java 消息（本地列表是后端列表的前缀），
 * 拿不到就明确返回 pending，让调用方提示用户稍后再试。
 */

/**
 * 对账结果：
 * - ok：拿到确定的 Java 消息 id；
 * - pending：该位置还没有落库（`remoteCount` 给出后端当前条数，调用方可据此判断
 *   「这一轮还没写完」还是「整轮都没落库」——后者说明请求没能到达后端，按新一轮重发即可）；
 * - mismatch：顺序/角色对不上，禁止做任何破坏性操作。
 */
export type ReconcileOutcome =
  | { status: 'ok'; javaId: number }
  | { status: 'pending'; remoteCount: number }
  | { status: 'mismatch'; remoteCount: number };

/** 本地角色 → Java 角色（两端命名不同，必须显式转换） */
function toJavaRole(role: CopilotMessage['role']): ConversationMessage['role'] {
  return role === 'user' ? 'USER' : 'ASSISTANT';
}

export interface ReconcileOptions {
  /**
   * 是否校验用户消息的 content 完全一致。
   *
   * 只对用户消息生效：它的内容由前端写入后不再变化，可用于编辑前的一致性校验；
   * 助手消息**不能**比对 content——用户「停止生成」时两端各自记录到中断点，
   * Python 是在 yield 之前 append 的，可能比前端多出最后一段增量。
   */
  requireContent?: boolean;
}

/**
 * 在 Java 消息列表里定位本地第 index 条消息。
 *
 * 只按位置 + 角色对齐，不依赖内容：本地列表与后端列表的轮次顺序天然一致，
 * 而角色序列严格交替，错位时角色必然对不上，足以发现异常。
 */
export function reconcileMessageId(
  localMessages: CopilotMessage[],
  index: number,
  remoteMessages: ConversationMessage[],
  options: ReconcileOptions = {},
): ReconcileOutcome {
  const local = localMessages[index];
  const remoteCount = remoteMessages.length;
  if (!local) return { status: 'mismatch', remoteCount };
  // 本地比后端长 = 最后一轮还没落库；短 = 本地状态本身就不完整
  if (index >= remoteCount) return { status: 'pending', remoteCount };
  const remote = remoteMessages[index];
  if (remote.role !== toJavaRole(local.role)) return { status: 'mismatch', remoteCount };
  // 内容校验只对用户消息生效，助手消息容忍中断点差异（见 ReconcileOptions 说明）
  if (options.requireContent && local.role === 'user'
    && (remote.content ?? '') !== local.content) {
    return { status: 'mismatch', remoteCount };
  }
  return { status: 'ok', javaId: remote.id };
}

/** 本地第 index 条之后还有多少条消息（编辑前提示「将删除其后 N 条」） */
export function countFollowingMessages(messages: CopilotMessage[], index: number): number {
  return Math.max(0, messages.length - index - 1);
}

export interface ResolveJavaMessageIdOptions extends ReconcileOptions {
  localMessages: CopilotMessage[];
  index: number;
  /** 拉取权威会话详情（失败应抛出，由调用方决定降级方式） */
  loadRemote: () => Promise<ConversationMessage[]>;
  /** 落库时延内的重试次数（每次都会重新拉取） */
  attempts?: number;
  delayMs?: number;
}

/**
 * 带重试的对账入口：把「落库还没写完」与「确实对不上」区分开。
 *
 * 只在 pending 时重试——mismatch 是状态异常，重试不会变好。
 */
export async function resolveJavaMessageId({
  localMessages,
  index,
  loadRemote,
  requireContent,
  attempts = 3,
  delayMs = 400,
}: ResolveJavaMessageIdOptions): Promise<ReconcileOutcome> {
  let outcome: ReconcileOutcome = { status: 'pending', remoteCount: 0 };
  for (let attempt = 0; attempt < Math.max(1, attempts); attempt += 1) {
    const remoteMessages = await loadRemote();
    outcome = reconcileMessageId(localMessages, index, remoteMessages, { requireContent });
    if (outcome.status !== 'pending') return outcome;
    if (attempt < attempts - 1 && delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  return outcome;
}
