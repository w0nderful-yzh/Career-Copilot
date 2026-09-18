// 面试视图推导（纯函数）：把 Java InterviewSession 还原成「消息流 + 当前题 + 进度」。
//
// P4-1 起数据来源变了，这个模块也随之简化：
// - 实际轨迹直接来自 `session.turns`（Java 只把「真的发生过」的轮次放进来），
//   不再需要「遍历候选池 × 判断有没有作答记录」这种推断——那正是素材与轨迹混在一起的后果；
// - 当前题由服务端按**题目标识**定位好（`session.currentQuestion`），前端不再用下标推。

import type { InterviewQuestion, InterviewSession, InterviewTurn as Turn } from '../types/interview';

export interface InterviewTurn {
  role: 'interviewer' | 'user';
  /** 题目稳定标识（P4-1）：轨迹里的身份 */
  questionId?: string | null;
  questionIndex?: number | null;
  question?: string;
  category?: string;
  topic?: string | null;
  isFollowUp?: boolean;
  /** 追问序号；与 isFollowUp 一起表达追问身份，不依赖技能名后缀 */
  followUpIndex?: number | null;
  answer?: string;
  /** 非真实作答的标记：跳过 / 明确不会 / 未作答（P4Q-5） */
  answerState?: 'ANSWERED' | 'SKIPPED' | 'DECLINED' | 'UNANSWERED' | null;
}

export interface InterviewView {
  /** 已发生的题/答流 + 当前待答题（用于渲染消息流） */
  turns: InterviewTurn[];
  /** 当前待答题；已结束或无题可答时为 null */
  current: InterviewQuestion | null;
  /** 当前待答题标识（提交 / 跳过都传它） */
  currentQuestionId: string | null;
  /** 主问题（主题）的**标识**列表（按候选池顺序），进度分母用它 */
  mainQuestionIds: string[];
  /** 候选素材总数（含候选择问），仅非自适应会话可作分母 */
  candidateTotal: number;
  adaptive: boolean;
}

export interface InterviewProgress {
  /** 实际已答 = 实际已提问数 */
  answeredCount: number;
  /** 当前题所属主题序号（1-based）；无当前题时为 0 */
  mainOrdinal: number;
  /** 主题总数 */
  mainCount: number;
}

/** 候选素材 → 面试官气泡 */
export function toInterviewerTurn(question: InterviewQuestion): InterviewTurn {
  return {
    role: 'interviewer',
    questionId: question.questionId,
    questionIndex: question.questionIndex,
    question: question.question,
    category: question.category ?? undefined,
    topic: question.topic ?? null,
    isFollowUp: question.isFollowUp,
    followUpIndex: question.followUpIndex ?? null,
  };
}

/** 轨迹轮次 → 面试官气泡 */
function turnToInterviewerTurn(
  turn: Turn,
  candidate: InterviewQuestion | undefined,
): InterviewTurn {
  return {
    role: 'interviewer',
    questionId: turn.questionId ?? null,
    questionIndex: turn.questionIndex ?? null,
    question: turn.question ?? undefined,
    category: turn.category ?? undefined,
    topic: turn.topic ?? candidate?.topic ?? null,
    // 轮次负责证明「发生过」，候选素材负责题型元数据；按稳定标识连接，不再靠下标猜。
    isFollowUp: candidate?.isFollowUp ?? false,
    followUpIndex: candidate?.followUpIndex ?? null,
  };
}

/**
 * 非真实作答的展示文案；真实作答返回 null。
 *
 * 判据是**状态**而不是答案文本：跳过时答案为空，只看文本会被当成「从没问过」（P4Q-5）。
 */
export function nonAnswerLabel(
  answerState: InterviewTurn['answerState'],
): string | null {
  switch (answerState) {
    case 'SKIPPED':
      return '（已跳过本题）';
    case 'DECLINED':
      return '（表示不会，未作答）';
    case 'UNANSWERED':
      return '（未作答）';
    default:
      return null;
  }
}

/**
 * 构建「已发生」的题/答流。
 *
 * 直接来自 `session.turns`（Java 侧只放真实轮次），因此不需要再判断「这题问过没有」——
 * 候选择问根本不在轨迹里。
 */
export function buildAnsweredTurns(session: InterviewSession): InterviewTurn[] {
  const turns: InterviewTurn[] = [];
  const candidateById = new Map(
    (session.candidates ?? []).map((candidate) => [candidate.questionId, candidate]),
  );
  for (const turn of session.turns ?? []) {
    const candidate = turn.questionId ? candidateById.get(turn.questionId) : undefined;
    turns.push(turnToInterviewerTurn(turn, candidate));
    turns.push({
      role: 'user',
      answer: nonAnswerLabel(turn.answerState) ?? turn.userAnswer ?? '',
      answerState: turn.answerState ?? null,
    });
  }
  return turns;
}

/**
 * 待作答的当前题：服务端已按标识定位，索引不再参与判断。
 *
 * 已结束的会话返回 null（服务端也不会给当前题）——这是「终态会话不得再产出当前题」的落点。
 */
export function currentQuestionOf(session: InterviewSession): InterviewQuestion | null {
  if (session.status === 'COMPLETED' || session.status === 'EVALUATED') {
    return null;
  }
  return session.currentQuestion ?? null;
}

/** 从 Java 会话推导完整视图（刷新恢复走这条路径） */
export function deriveInterviewView(session: InterviewSession): InterviewView {
  const current = currentQuestionOf(session);
  const turns = buildAnsweredTurns(session);
  // 当前题尚未作答，也要渲染，否则刷新后看不到正在回答的问题
  if (current) {
    turns.push(toInterviewerTurn(current));
  }
  const candidates = session.candidates ?? [];
  return {
    turns,
    current,
    currentQuestionId: session.currentQuestionId ?? current?.questionId ?? null,
    mainQuestionIds: candidates
      .filter((question) => !question.isFollowUp)
      .map((question) => question.questionId),
    candidateTotal: candidates.length,
    adaptive: Boolean(session.adaptive),
  };
}

/**
 * 计算进度。
 *
 * 分母绝不能取 totalQuestions（候选素材总数，含候选追问）：自适应会话会按作答质量跳过其中
 * 一部分，用它做分母会得出「只答了 3 题却显示第 10 / 12 题」这种虚高进度。
 * 唯一确定的分母是主问题（主题）数，分子用**实际已答**数。
 *
 * 主题序位按标识定位（当前题是追问时看它的父主问题），因此候选池顺序调整不会让进度错位。
 */
export function interviewProgress(
  mainQuestionIds: string[],
  current: InterviewQuestion | null,
  answeredCount: number,
): InterviewProgress {
  const anchorId = current?.isFollowUp ? current.parentQuestionId : current?.questionId;
  const position = anchorId ? mainQuestionIds.indexOf(anchorId) : -1;
  return {
    answeredCount,
    mainOrdinal: position >= 0 ? position + 1 : 0,
    mainCount: mainQuestionIds.length,
  };
}
