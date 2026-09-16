import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildAnsweredTurns,
  currentQuestionOf,
  deriveInterviewView,
  interviewProgress,
  toInterviewerTurn,
} from './interviewTurns.ts';
import type { InterviewQuestion, InterviewSession } from '../types/interview.ts';

function question(overrides: Partial<InterviewQuestion> & { questionIndex: number }): InterviewQuestion {
  return {
    question: `Q${overrides.questionIndex}`,
    type: 'MAIN',
    category: 'Java',
    userAnswer: null,
    score: null,
    feedback: null,
    isFollowUp: false,
    parentQuestionIndex: null,
    ...overrides,
  };
}

function session(overrides: Partial<InterviewSession> = {}): InterviewSession {
  return {
    sessionId: 's1',
    resumeText: '',
    totalQuestions: 0,
    currentQuestionIndex: 0,
    questions: [],
    status: 'IN_PROGRESS',
    adaptive: true,
    ...overrides,
  };
}

/**
 * 自适应会话的典型题库：2 个主问题 + 各 1 条候选追问。
 * 策略在 Q1 答得不好时会跳过后面的 F1，直接问 Q2 —— F1 仍留在题库里但没有答案。
 */
function adaptivePool(): InterviewQuestion[] {
  return [
    question({ questionIndex: 0, question: 'Q1: JVM 内存模型？' }),
    question({
      questionIndex: 1,
      question: 'F1: 堆区分代？',
      isFollowUp: true,
      parentQuestionIndex: 0,
      followUpIndex: 1,
    }),
    question({ questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
    question({
      questionIndex: 3,
      question: 'F2: AOF 重写？',
      category: 'Redis',
      isFollowUp: true,
      parentQuestionIndex: 2,
      followUpIndex: 1,
    }),
  ];
}

test('刷新恢复：只重放已作答轮次，被策略跳过的追问题不得出现', () => {
  // Q1 答了（差）→ F1 被跳过 → Q2 已问未答（当前题）
  const pool = adaptivePool();
  pool[0] = { ...pool[0], userAnswer: '只记得堆和栈' };
  const s = session({ questions: pool, currentQuestionIndex: 2, totalQuestions: 4 });

  const turns = buildAnsweredTurns(s);

  assert.deepEqual(
    turns.map((turn) => turn.role),
    ['interviewer', 'user'],
  );
  assert.equal(turns[0].question, 'Q1: JVM 内存模型？');
  assert.equal(turns[1].answer, '只记得堆和栈');
  // 关键回归点：F1 从未被提问，不能因为它在题库里就渲染出来
  assert.ok(!turns.some((turn) => turn.question === 'F1: 堆区分代？'));
});

test('刷新恢复：视图包含已答题、当前题与进度分母，且不含候选追问', () => {
  const pool = adaptivePool();
  pool[0] = { ...pool[0], userAnswer: '答了一半' };
  const s = session({ questions: pool, currentQuestionIndex: 2 });

  const view = deriveInterviewView(s);

  assert.equal(view.adaptive, true);
  assert.equal(view.poolTotal, 4);
  // 主问题索引 = [0, 2]，候选追问（1、3）不计入
  assert.deepEqual(view.mainIndexes, [0, 2]);
  assert.equal(view.current?.question, 'Q2: Redis 持久化？');
  // 已答轮次 + 当前题，都出现在消息流里
  assert.deepEqual(
    view.turns.map((turn) => turn.question ?? turn.answer),
    ['Q1: JVM 内存模型？', '答了一半', 'Q2: Redis 持久化？'],
  );
});

test('进度分母用主问题数，不用题库总数（含候选择问会虚高）', () => {
  const pool = adaptivePool();
  const s = session({ questions: pool, currentQuestionIndex: 2 });
  const view = deriveInterviewView(s);

  const progress = interviewProgress(view.mainIndexes, view.current, 1);

  assert.equal(progress.answeredCount, 1);
  assert.equal(progress.mainCount, 2);
  assert.equal(progress.mainOrdinal, 2, '当前是第 2 个主题');
  // 若误用 totalQuestions 作分母会得到「第 3 / 4 题」这种虚高进度
  assert.notEqual(progress.mainCount, view.poolTotal);
});

test('已结束的会话不再展示当前题（currentQuestionIndex 可能停在题库末尾）', () => {
  const pool = adaptivePool();
  pool[0] = { ...pool[0], userAnswer: 'a' };
  pool[1] = { ...pool[1], userAnswer: 'b' };
  pool[2] = { ...pool[2], userAnswer: 'c' };
  // 提前结束时索引停在追问 F2（未被问过），不能当作当前题展示
  const s = session({ questions: pool, currentQuestionIndex: 3, status: 'COMPLETED' });

  assert.equal(currentQuestionOf(s), null);
  const view = deriveInterviewView(s);
  assert.equal(view.turns.length, 6, '三条已答轮次各渲染题+答');
  assert.ok(!view.turns.some((turn) => turn.question === 'F2: AOF 重写？'));
  assert.equal(interviewProgress(view.mainIndexes, view.current, 3).mainOrdinal, 0);
});

test('索引越界时不抛错，视为无当前题', () => {
  const s = session({ questions: [question({ questionIndex: 0 })], currentQuestionIndex: 99 });
  assert.equal(currentQuestionOf(s), null);
  assert.deepEqual(deriveInterviewView(s).turns, []);
});

test('追问轮次携带独立序号，技能名不含「（追问N）」后缀（P4Q-6）', () => {
  const pool = adaptivePool();
  pool[0] = { ...pool[0], userAnswer: '堆和栈' };
  pool[1] = { ...pool[1], userAnswer: '新生代老年代' };

  const turns = buildAnsweredTurns(session({ questions: pool }));

  // 追问身份由 followUpIndex 表达，展示层据此渲染「追问 N」
  assert.equal(turns[2].isFollowUp, true);
  assert.equal(turns[2].followUpIndex, 1);
  assert.equal(turns[0].followUpIndex, null);
  // 技能名恒为稳定标识：不能再把序号拼进去（那会变成画像伪技能）
  assert.ok(!String(turns[2].category).includes('追问'));
});

test('追问轮次保留追问徽标与所属分类', () => {
  // 主问题答得好 → 进入追问，两轮都作答
  const pool = adaptivePool();
  pool[0] = { ...pool[0], userAnswer: '堆和栈' };
  pool[1] = { ...pool[1], userAnswer: '新生代老年代' };
  const turns = buildAnsweredTurns(session({ questions: pool }));

  assert.deepEqual(
    turns.map((turn) => turn.role),
    ['interviewer', 'user', 'interviewer', 'user'],
  );
  assert.equal(turns[0].isFollowUp, false);
  assert.equal(turns[2].isFollowUp, true, '追问轮次要带追问徽标');
  assert.equal(turns[2].questionIndex, 1);
  assert.equal(turns[2].category, 'Java');
});

test('非自适应顺序会话：题库总数即真实总题数，进度语义不回归', () => {
  const pool = [
    question({ questionIndex: 0, question: 'Q1' }),
    question({ questionIndex: 1, question: 'Q2' }),
  ];
  const s = session({ questions: pool, currentQuestionIndex: 1, adaptive: false });
  const view = deriveInterviewView(s);

  assert.equal(view.adaptive, false);
  assert.equal(view.mainIndexes.length, 2);
  assert.equal(view.poolTotal, 2);
});

test('toInterviewerTurn 只搬运展示字段，不带答案', () => {
  const turn = toInterviewerTurn(
    question({ questionIndex: 5, question: '题面', userAnswer: '不该出现' }),
    5,
  );

  assert.deepEqual(turn, {
    role: 'interviewer',
    questionIndex: 5,
    question: '题面',
    category: 'Java',
    isFollowUp: false,
    followUpIndex: null,
  });
});
