import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildAnsweredTurns,
  currentQuestionOf,
  deriveInterviewView,
  interviewPlanProgress,
  interviewProgress,
  nonAnswerLabel,
  toInterviewerTurn,
} from './interviewTurns.ts';
import type { InterviewQuestion, InterviewSession, InterviewTurn } from '../types/interview.ts';

/** 候选素材（只描述「可以问什么」） */
function candidate(
  overrides: Partial<InterviewQuestion> & { questionIndex: number },
): InterviewQuestion {
  return {
    questionId: `q${overrides.questionIndex}`,
    question: `Q${overrides.questionIndex}`,
    type: 'MAIN',
    category: 'Java',
    isFollowUp: false,
    parentQuestionId: null,
    ...overrides,
  };
}

/** 实际轨迹的一轮（只描述「实际发生了什么」） */
function turn(overrides: Partial<InterviewTurn> & { questionId: string }): InterviewTurn {
  return {
    ordinal: 1,
    questionIndex: 0,
    question: `Q${overrides.questionId}`,
    category: 'Java',
    userAnswer: null,
    answerState: 'ANSWERED',
    ...overrides,
  };
}

function session(overrides: Partial<InterviewSession> = {}): InterviewSession {
  return {
    sessionId: 's1',
    resumeText: '',
    totalQuestions: 0,
    currentQuestionIndex: 0,
    candidates: [],
    turns: [],
    status: 'IN_PROGRESS',
    adaptive: true,
    ...overrides,
  };
}

/**
 * 自适应会话的典型候选池：2 个主问题 + 各 1 条候选追问。
 * 策略可能跳过 F1 —— 它仍在**候选素材**里，但不该出现在**实际轨迹**里。
 */
function adaptivePool(): InterviewQuestion[] {
  return [
    candidate({ questionIndex: 0, question: 'Q1: JVM 内存模型？' }),
    candidate({
      questionIndex: 1,
      question: 'F1: 堆区分代？',
      isFollowUp: true,
      parentQuestionId: 'q0',
      followUpIndex: 1,
    }),
    candidate({ questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
    candidate({
      questionIndex: 3,
      question: 'F2: AOF 重写？',
      category: 'Redis',
      isFollowUp: true,
      parentQuestionId: 'q2',
      followUpIndex: 1,
    }),
  ];
}

test('刷新恢复：轨迹只来自实际轮次，候选池里没问过的追问不会出现', () => {
  // Q1 答了（差）→ F1 被策略跳过（不在轨迹里）→ Q2 是当前题
  const s = session({
    candidates: adaptivePool(),
    turns: [turn({ questionId: 'q0', ordinal: 1, question: 'Q1: JVM 内存模型？', userAnswer: '只记得堆和栈' })],
    currentQuestion: candidate({ questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
    currentQuestionId: 'q2',
    totalQuestions: 4,
  });

  const turns = buildAnsweredTurns(s);

  assert.deepEqual(
    turns.map((item) => item.role),
    ['interviewer', 'user'],
  );
  assert.equal(turns[0].question, 'Q1: JVM 内存模型？');
  assert.equal(turns[1].answer, '只记得堆和栈');
  // 关键回归点：F1 从未被提问，不能因为它在候选池里就渲染出来
  assert.ok(!turns.some((item) => item.question === 'F1: 堆区分代？'));
});

test('刷新恢复：已接纳的承接语跟随决策轮次恢复，不伪装成新问题', () => {
  const s = session({
    candidates: adaptivePool(),
    turns: [
      turn({
        questionId: 'q0',
        ordinal: 1,
        userAnswer: '只记得堆和栈',
        transitionMessage: '基础点已经覆盖，下面转到 Redis。',
      }),
    ],
  });

  const turns = buildAnsweredTurns(s);

  assert.deepEqual(turns.map((item) => item.role), ['interviewer', 'user', 'interviewer']);
  assert.equal(turns[2].question, '基础点已经覆盖，下面转到 Redis。');
  assert.equal(turns[2].transition, true);
});

test('计划进度按实际主问题轨迹计算必要覆盖，不把候选择问算作已覆盖', () => {
  const s = session({
    candidates: adaptivePool(),
    requiredTopics: ['Java', 'Redis'],
    turns: [
      turn({ questionId: 'q0', ordinal: 1, userAnswer: '堆和栈' }),
      turn({
        questionId: 'q3',
        ordinal: 2,
        questionIndex: 3,
        category: 'Redis',
        userAnswer: 'AOF 重写',
      }),
    ],
    currentQuestion: candidate({
      questionIndex: 2,
      question: 'Q2: Redis 持久化？',
      category: 'Redis',
    }),
  });

  const progress = interviewPlanProgress(s);

  assert.equal(progress.answeredCount, 2);
  assert.equal(progress.currentTopic, 'Redis');
  assert.deepEqual(progress.coveredRequiredTopics, ['Java']);
});

test('必要覆盖支持分类 key：PROJECT 能匹配题目展示名“项目经历”', () => {
  const project = candidate({
    questionIndex: 0,
    type: 'PROJECT',
    category: '项目经历',
    question: '介绍一个你主导的项目',
  });
  const progress = interviewPlanProgress(session({
    candidates: [project],
    requiredTopics: ['PROJECT'],
    turns: [turn({
      questionId: project.questionId,
      ordinal: 1,
      category: '项目经历',
      userAnswer: '订单系统',
    })],
  }));

  assert.deepEqual(progress.coveredRequiredTopics, ['PROJECT']);
});

test('刷新恢复：视图包含已答轮次、当前题与主问题分母，且不含候选追问', () => {
  const s = session({
    candidates: adaptivePool(),
    turns: [turn({ questionId: 'q0', ordinal: 1, question: 'Q1: JVM 内存模型？', userAnswer: '答了一半' })],
    currentQuestion: candidate({ questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
    currentQuestionId: 'q2',
  });

  const view = deriveInterviewView(s);

  assert.equal(view.adaptive, true);
  assert.equal(view.candidateTotal, 4);
  // 主问题**标识** = [q0, q2]，候选追问（q1、q3）不计入
  assert.deepEqual(view.mainQuestionIds, ['q0', 'q2']);
  assert.equal(view.current?.question, 'Q2: Redis 持久化？');
  assert.equal(view.currentQuestionId, 'q2');
  assert.deepEqual(
    view.turns.map((item) => item.question ?? item.answer),
    ['Q1: JVM 内存模型？', '答了一半', 'Q2: Redis 持久化？'],
  );
});

test('进度分母用主问题数，不用候选总数（含候选择问会虚高）', () => {
  const s = session({
    candidates: adaptivePool(),
    currentQuestion: candidate({ questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
    currentQuestionId: 'q2',
  });
  const view = deriveInterviewView(s);

  const progress = interviewProgress(view.mainQuestionIds, view.current, 1);

  assert.equal(progress.answeredCount, 1);
  assert.equal(progress.mainCount, 2);
  assert.equal(progress.mainOrdinal, 2, '当前是第 2 个主题');
  // 若误用候选总数作分母会得到「第 3 / 4 题」这种虚高进度
  assert.notEqual(progress.mainCount, view.candidateTotal);
});

test('当前是追问时，主题序位看它的父主问题（标识定位，不看下标）', () => {
  const s = session({
    candidates: adaptivePool(),
    currentQuestion: candidate({
      questionIndex: 3,
      question: 'F2: AOF 重写？',
      category: 'Redis',
      isFollowUp: true,
      parentQuestionId: 'q2',
      followUpIndex: 1,
    }),
    currentQuestionId: 'q3',
  });

  const progress = interviewProgress(deriveInterviewView(s).mainQuestionIds, deriveInterviewView(s).current, 2);

  assert.equal(progress.mainOrdinal, 2);
});

test('已结束的会话不再展示当前题（服务端也不再给当前题）', () => {
  const s = session({
    status: 'COMPLETED',
    candidates: adaptivePool(),
    turns: [
      turn({ questionId: 'q0', ordinal: 1, userAnswer: 'a' }),
      turn({ questionId: 'q1', ordinal: 2, userAnswer: 'b' }),
      turn({ questionId: 'q2', ordinal: 3, userAnswer: 'c' }),
    ],
    // 即便服务端误给了当前题，终态会话也不展示
    currentQuestion: candidate({ questionIndex: 3, question: 'F2: AOF 重写？' }),
    currentQuestionId: 'q3',
  });

  assert.equal(currentQuestionOf(s), null);
  const view = deriveInterviewView(s);
  assert.equal(view.turns.length, 6, '三条已答轮次各渲染题+答');
  assert.ok(!view.turns.some((item) => item.question === 'F2: AOF 重写？'));
  assert.equal(interviewProgress(view.mainQuestionIds, view.current, 3).mainOrdinal, 0);
});

test('没有当前题时视图只有轨迹，不抛错', () => {
  const s = session({ candidates: [candidate({ questionIndex: 0 })], currentQuestion: null });

  assert.equal(currentQuestionOf(s), null);
  assert.deepEqual(deriveInterviewView(s).turns, []);
});

test('追问轮次携带独立序号，技能名不含「（追问N）」后缀（P4Q-6）', () => {
  const s = session({
    candidates: adaptivePool(),
    turns: [
      turn({ questionId: 'q0', ordinal: 1, userAnswer: '堆和栈' }),
      turn({
        questionId: 'q1',
        ordinal: 2,
        questionIndex: 1,
        question: 'F1: 堆区分代？',
        userAnswer: '新生代老年代',
      }),
    ],
  });

  const turns = buildAnsweredTurns(s);

  // 追问身份由轨迹的题目元数据表达，技能名恒为稳定标识
  assert.equal(turns[2].question, 'F1: 堆区分代？');
  assert.equal(turns[2].isFollowUp, true);
  assert.equal(turns[2].followUpIndex, 1);
  assert.ok(!String(turns[2].category).includes('追问'));
});

test('轨迹按发生顺序渲染，构成题/答交替的消息流', () => {
  const s = session({
    candidates: adaptivePool(),
    turns: [
      turn({ questionId: 'q0', ordinal: 1, userAnswer: '堆和栈' }),
      turn({
        questionId: 'q1',
        ordinal: 2,
        questionIndex: 1,
        question: 'F1: 堆区分代？',
        userAnswer: '新生代老年代',
      }),
    ],
  });

  const turns = buildAnsweredTurns(s);

  assert.deepEqual(
    turns.map((item) => item.role),
    ['interviewer', 'user', 'interviewer', 'user'],
  );
  assert.equal(turns[0].isFollowUp, false);
  assert.equal(turns[2].isFollowUp, true, '追问轮次刷新后仍要保留追问徽标');
  assert.equal(turns[2].category, 'Java');
});

test('非自适应顺序会话：主问题数与候选总数一致，进度语义不回归', () => {
  const s = session({
    adaptive: false,
    candidates: [candidate({ questionIndex: 0, question: 'Q1' }), candidate({ questionIndex: 1, question: 'Q2' })],
    currentQuestion: candidate({ questionIndex: 1, question: 'Q2' }),
    currentQuestionId: 'q1',
  });
  const view = deriveInterviewView(s);

  assert.equal(view.adaptive, false);
  assert.equal(view.mainQuestionIds.length, 2);
  assert.equal(view.candidateTotal, 2);
});

test('跳过的轮次仍在轨迹里（轨迹由服务端给出，不靠答案文本推断）（P4Q-5）', () => {
  const s = session({
    candidates: adaptivePool(),
    turns: [
      turn({ questionId: 'q0', ordinal: 1, question: 'Q1: JVM 内存模型？', userAnswer: null, answerState: 'SKIPPED' }),
      turn({ questionId: 'q2', ordinal: 2, questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis', userAnswer: 'RDB 和 AOF' }),
    ],
  });

  const turns = buildAnsweredTurns(s);

  assert.deepEqual(
    turns.map((item) => item.role),
    ['interviewer', 'user', 'interviewer', 'user'],
  );
  assert.equal(turns[1].answer, '（已跳过本题）');
  assert.equal(turns[1].answerState, 'SKIPPED');
  assert.equal(turns[3].answerState, 'ANSWERED');
});

test('非作答状态的展示文案：跳过/明确不会/未作答各不相同', () => {
  assert.equal(nonAnswerLabel('SKIPPED'), '（已跳过本题）');
  assert.equal(nonAnswerLabel('DECLINED'), '（表示不会，未作答）');
  assert.equal(nonAnswerLabel('UNANSWERED'), '（未作答）');
  assert.equal(nonAnswerLabel('ANSWERED'), null);
  assert.equal(nonAnswerLabel(null), null);
});

test('toInterviewerTurn 只搬运展示字段，不带答案', () => {
  const turn = toInterviewerTurn(candidate({ questionIndex: 5, question: '题面' }));

  assert.deepEqual(turn, {
    role: 'interviewer',
    questionId: 'q5',
    questionIndex: 5,
    question: '题面',
    category: 'Java',
    topic: null,
    isFollowUp: false,
    followUpIndex: null,
  });
});
