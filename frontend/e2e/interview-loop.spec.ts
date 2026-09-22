import { expect, test } from '@playwright/test';

/**
 * 面试闭环 E2E（P6-4）：成功、用户取消、依赖失败与重试。
 *
 * 与 interview-restore.spec.ts 的分工：那边守「刷新恢复 / 结束退出 / 复盘 / 画像」，
 * 这边守**从创建到报告的主流程**，以及两条最容易悄悄坏掉的边界——
 * 取消不能留下半创建的会话，依赖失败必须给出可见出口而不是停在「评估中」。
 */

const SESSION_ID = 'loop-session-0001';
const CONVERSATION_ID = 7;
const LIST_URL = /\/api\/agent\/conversations(\?|$)/;
const detailUrl = (id: number) => new RegExp(`/api/agent/conversations/${id}$`);
const sessionUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}$`);
const answersUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}/answers$`);
const completeUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}/complete$`);
const retryUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}/evaluate/retry$`);
const reportUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}/report$`);

function result<T>(code: number, message: string, data: T) {
  return { json: { code, message, data } };
}

function candidate(overrides: Record<string, unknown>) {
  return {
    questionId: `q${overrides.questionIndex ?? 0}`,
    type: 'MAIN',
    category: 'Java',
    topicSummary: null,
    isFollowUp: false,
    parentQuestionId: null,
    followUpIndex: null,
    ...overrides,
  };
}

const candidates = [
  candidate({ questionId: 'q1', questionIndex: 0, question: 'Q1: JVM 内存模型？' }),
  candidate({ questionId: 'q2', questionIndex: 1, question: 'Q2: Redis 持久化？', category: 'Redis' }),
];

const proposalBlock = {
  type: 'interview_proposal',
  direction: 'java-backend',
  direction_name: 'Java 后端',
  difficulty: 'mid',
  difficulty_name: '中级',
  mode: 'TEXT',
  focus: ['JAVA'],
  planned_duration_minutes: 20,
  required_topics: ['JAVA'],
  resume_id: null,
  summary: '按你的画像推荐',
  reasons: ['Java 当前 58 分（2 条面试证据）'],
};

const interviewSessionBlock = {
  type: 'interview_session',
  session_id: SESSION_ID,
  skill_id: 'java-backend',
  difficulty: 'mid',
  mode: 'TEXT',
  focus: ['JAVA'],
  question_count: 2,
  direction_name: 'Java 后端',
};

function conversationPayload(blocks: unknown[]) {
  return {
    id: CONVERSATION_ID,
    title: '来一场 Java 后端模拟面试',
    messageCount: 2,
    isPinned: false,
    updatedAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
    messages: [
      {
        id: 1,
        role: 'USER',
        content: '来一场 Java 后端模拟面试',
        blocks: null,
        status: 'COMPLETED',
        createdAt: new Date().toISOString(),
      },
      {
        id: 2,
        role: 'ASSISTANT',
        content: '已为你准备。',
        blocks: JSON.stringify(blocks),
        status: 'COMPLETED',
        createdAt: new Date().toISOString(),
      },
    ],
  };
}

async function mockConversation(page: import('@playwright/test').Page, blocks: unknown[]) {
  await page.route(LIST_URL, (route) =>
    route.fulfill(
      result(200, 'success', [
        { id: CONVERSATION_ID, title: '来一场 Java 后端模拟面试', updatedAt: new Date().toISOString() },
      ]),
    ),
  );
  await page.route(detailUrl(CONVERSATION_ID), (route) =>
    route.fulfill(result(200, 'success', conversationPayload(blocks))),
  );
}

/** 会话读取：面试进行中 → 交卷后进入评估 → 重试后成功 */
function sessionRoutes(page: import('@playwright/test').Page, state: {
  submitted: number;
  evaluateStatus: 'PENDING' | 'FAILED' | 'COMPLETED';
}) {
  return page.route(sessionUrl, (route) =>
    route.fulfill(
      result(200, 'success', {
        sessionId: SESSION_ID,
        resumeText: '',
        totalQuestions: candidates.length,
        currentQuestionIndex: state.submitted,
        currentQuestionId: state.submitted === 0 ? 'q1' : null,
        currentQuestion: state.submitted === 0 ? candidates[0] : null,
        candidates,
        turns: [],
        status:
          state.submitted === 0
            ? 'IN_PROGRESS'
            : state.evaluateStatus === 'COMPLETED'
              ? 'EVALUATED'
              : 'COMPLETED',
        adaptive: true,
        turnVersion: state.submitted,
        evaluateStatus: state.submitted === 0 ? null : state.evaluateStatus,
        evaluateError: state.evaluateStatus === 'FAILED' ? '模型超时' : null,
      }),
    ),
  );
}

function reportPayload() {
  return {
    sessionId: SESSION_ID,
    totalQuestions: 2,
    overallScore: 76,
    categoryScores: [
      {
        category: 'Java',
        score: 76,
        questionCount: 1,
        mainGroupCount: 1,
        evaluatedMainGroupCount: 1,
        aggregationNote: '单题均值',
      },
    ],
    questionDetails: [],
    overallFeedback: '整体稳定。',
    strengths: ['JVM 基础扎实'],
    improvements: ['补充细节'],
    referenceAnswers: [],
    scoringRuleVersion: 'main-group-v1',
    aggregationMethod: '按主问题组等权聚合',
    coverage: [
      {
        topic: 'Java',
        required: true,
        actualTurnCount: 1,
        evaluatedMainGroupCount: 1,
        status: 'ASSESSED',
      },
    ],
    unassessedTopics: [],
    skippedQuestionIds: [],
    insufficientEvidenceQuestionIds: [],
  };
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(
      result(200, 'success', {
        tool: 'get_skill_profile',
        data: { skills: [{ skill: 'Java', score: 58, evidenceCount: 2, evidences: [] }], declaredSkills: [] },
      }),
    ),
  );
});

test.describe('面试闭环（P6-4）', () => {
  test('成功：逐轮作答到末题自动收束，报告卡给出结果', async ({ page }) => {
    const state = { submitted: 0, evaluateStatus: 'PENDING' as const };
    await mockConversation(page, [interviewSessionBlock]);
    await sessionRoutes(page, state);
    await page.route(answersUrl, (route) => {
      state.submitted += 1;
      const hasNext = state.submitted < 2;
      return route.fulfill(
        result(200, 'success', {
          hasNextQuestion: hasNext,
          nextQuestion: hasNext ? candidates[1] : null,
          currentIndex: state.submitted,
          totalQuestions: candidates.length,
          turnVersion: state.submitted,
          consumedSeconds: 60 * state.submitted,
          remainingSeconds: 1200 - 60 * state.submitted,
        }),
      );
    });
    await page.route(reportUrl, (route) => route.fulfill(result(200, 'success', reportPayload())));
    await page.route(new RegExp(`/api/interview/sessions/${SESSION_ID}/profile-impact$`), (route) =>
      route.fulfill(result(200, 'success', { sessionId: SESSION_ID, skills: [] })),
    );

    await page.goto('/copilot');

    // 进入 Interview Mode：当前题可见
    await expect(page.getByText('Q1: JVM 内存模型？')).toBeVisible();

    // 第一轮：作答 → 提交 → 下一题出现
    await page.getByPlaceholder('输入你的回答…').fill('堆分新生代与老年代，方法区存类元信息');
    await page.getByRole('button', { name: '提交回答' }).click();
    await expect(page.getByText('Q2: Redis 持久化？')).toBeVisible();

    // 第二轮：作答 → 提交 → 末题自动收束到评估
    await page.getByPlaceholder('输入你的回答…').fill('RDB 快照 + AOF 追加日志');
    await page.getByRole('button', { name: '提交回答' }).click();
    state.evaluateStatus = 'COMPLETED';
    state.submitted = 2;

    // 评估完成 → 结果卡（含本场画像入口与完成返回）
    await expect(page.getByRole('button', { name: '完成并返回对话' })).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('可变路线综合分')).toBeVisible();
    await expect(page.getByText('考察覆盖')).toBeVisible();
  });

  test('用户取消：调整配置后取消，不创建任何会话', async ({ page }) => {
    let chatCalled = 0;
    await mockConversation(page, [proposalBlock]);
    await page.route('**/api/chat/stream', (route) => {
      chatCalled += 1;
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: ['data: {"type":"run_status","payload":{"status":"COMPLETED"}}', 'data: {"type":"done","payload":{}}', ''].join('\n\n'),
      });
    });

    await page.goto('/copilot');

    // 打开配置面板再取消：面板收起、提案卡还在、没有发起创建
    await page.getByRole('button', { name: '调整配置' }).click();
    await expect(page.getByRole('button', { name: '应用并开始面试' })).toBeVisible();
    await page.getByRole('button', { name: '取消', exact: true }).click();

    await expect(page.getByRole('button', { name: '应用并开始面试' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '按推荐开始' })).toBeVisible();
    expect(chatCalled).toBe(0);
    await expect(page.getByText('结束面试')).toHaveCount(0);
  });

  test('依赖失败与重试：报告失败给出可见出口，重试后拿到结果', async ({ page }) => {
    const state = { submitted: 0, evaluateStatus: 'PENDING' as const };
    await mockConversation(page, [interviewSessionBlock]);
    await sessionRoutes(page, state);
    await page.route(completeUrl, (route) => {
      // 交卷后会话进入 COMPLETED，且报告生成失败——这正是要验证的依赖失败场景
      state.submitted = 1;
      state.evaluateStatus = 'FAILED';
      return route.fulfill(result(200, 'success', null));
    });
    let retried = 0;
    await page.route(retryUrl, (route) => {
      retried += 1;
      state.evaluateStatus = 'COMPLETED';
      return route.fulfill(result(200, 'success', { evaluateEpoch: 2 }));
    });
    await page.route(reportUrl, (route) => route.fulfill(result(200, 'success', reportPayload())));

    await page.goto('/copilot');
    await expect(page.getByText('Q1: JVM 内存模型？')).toBeVisible();

    // 交卷 → 报告生成失败：必须给出原因与重试入口，而不是停在「评估中」
    await page.getByRole('button', { name: '结束面试' }).click();
    await expect(page.getByRole('button', { name: '重新生成报告' })).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('模型超时')).toBeVisible();

    await page.getByRole('button', { name: '重新生成报告' }).click();
    expect(retried).toBe(1);
    await expect(page.getByRole('button', { name: '完成并返回对话' })).toBeVisible({ timeout: 15000 });
  });
});
