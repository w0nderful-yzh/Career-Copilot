// 面试视图推导（纯函数）：把 Java InterviewSession 还原成「消息流 + 当前题 + 进度」。
//
// 抽成独立模块的原因有两个：
// 1. 刷新恢复（P4 待修正）是易错点——恢复时的重建规则必须可被单测固化，而不是埋在组件里；
// 2. 自适应会话的题库里含**候选择问**，被策略跳过的题仍留在 questions 数组中，
//    任何「按 currentQuestionIndex 遍历题库」的写法都会把没问过的题渲染成已问过。

import type { InterviewQuestion, InterviewSession } from '../types/interview';

export interface InterviewTurn {
  role: 'interviewer' | 'user';
  questionIndex?: number;
  question?: string;
  category?: string;
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
  /** 主问题（主题）在题库中的索引，进度分母用它 */
  mainIndexes: number[];
  /** 题库总数（含候选择问），仅非自适应会话可作分母 */
  poolTotal: number;
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

/** 把题转成面试官气泡的一条 turn */
export function toInterviewerTurn(question: InterviewQuestion, index: number): InterviewTurn {
  return {
    role: 'interviewer',
    questionIndex: index,
    question: question.question,
    category: question.category,
    isFollowUp: question.isFollowUp,
    followUpIndex: question.followUpIndex ?? null,
  };
}

/**
 * 这题是否「已经发生过」：有作答内容，或有作答状态（跳过/明确不会/未作答都算）。
 *
 * <p>只看答案文本是不够的——跳过时答案为空，会被当成「从没问过」而从轨迹里消失，
 * 刷新恢复后用户就看不到自己跳过过哪些题（P4Q-5）。
 */
export function wasAsked(question: InterviewQuestion): boolean {
  return Boolean(question.userAnswer) || Boolean(question.answerState);
}

/** 非真实作答的展示文案；真实作答返回 null */
export function nonAnswerLabel(question: InterviewQuestion): string | null {
  switch (question.answerState) {
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
 * 权威判据是「该题是否有作答记录」——**不能**按 currentQuestionIndex 遍历题库：
 * 自适应会话会跳过部分候选追问，那些被跳过的追问仍留在题库里，
 * 按索引遍历会把从未问过的题当成「已问过」渲染出来。
 */
export function buildAnsweredTurns(session: InterviewSession): InterviewTurn[] {
  const turns: InterviewTurn[] = [];
  session.questions.forEach((question, index) => {
    // 判据是「发生过」而不是「有答案」：跳过/未作答的轮次也要在轨迹里可见（P4Q-5）
    if (!wasAsked(question)) return;
    turns.push(toInterviewerTurn(question, index));
    const label = nonAnswerLabel(question);
    turns.push({
      role: 'user',
      answer: label ?? question.userAnswer ?? '',
      answerState: question.answerState ?? null,
    });
  });
  return turns;
}

/**
 * 待作答的当前题。
 *
 * 已结束的会话返回 null —— 此时 currentQuestionIndex 可能停在题库末尾（等于题库大小），
 * 也可能指向一个被策略跳过的候选追问，两者都不能当作「当前题」展示。
 */
export function currentQuestionOf(session: InterviewSession): InterviewQuestion | null {
  if (session.status === 'COMPLETED' || session.status === 'EVALUATED') {
    return null;
  }
  return session.questions[session.currentQuestionIndex] ?? null;
}

/** 从 Java 会话推导完整视图（刷新恢复走这条路径） */
export function deriveInterviewView(session: InterviewSession): InterviewView {
  const current = currentQuestionOf(session);
  const turns = buildAnsweredTurns(session);
  // 当前题尚未作答，也要渲染，否则刷新后看不到正在回答的问题
  if (current) {
    turns.push(toInterviewerTurn(current, current.questionIndex));
  }
  return {
    turns,
    current,
    mainIndexes: session.questions
      .filter((question) => !question.isFollowUp)
      .map((question) => question.questionIndex),
    poolTotal: session.questions.length,
    adaptive: Boolean(session.adaptive),
  };
}

/**
 * 计算进度。
 *
 * 分母绝不能取 totalQuestions（题库总数，含候选追问）：自适应会话会按作答质量跳过其中
 * 一部分，用它做分母会得出「只答了 3 题却显示第 10 / 12 题」这种虚高进度。
 * 唯一确定的分母是主问题（主题）数，分子用实际已答数。
 */
export function interviewProgress(
  mainIndexes: number[],
  current: InterviewQuestion | null,
  answeredCount: number,
): InterviewProgress {
  return {
    answeredCount,
    mainOrdinal: current
      ? mainIndexes.filter((index) => index <= current.questionIndex).length
      : 0,
    mainCount: mainIndexes.length,
  };
}
