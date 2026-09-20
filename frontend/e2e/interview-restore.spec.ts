import { expect, test } from '@playwright/test';

// Interview Mode 的刷新恢复与退出回归（P4 待修正）。
//
// 两条主线：
// 1. 恢复时按「实际已提问轮次」重建消息流 —— 题库里的候选追问若被策略跳过，
//    绝不能被当成已问过渲染出来（此前按 currentQuestionIndex 遍历题库，会全部显示）；
// 2. 面试完成退出后，不会被历史消息里那个旧的 interview_session 信号块重新拉回面试模式。
//
// 全部打桩，不依赖 Java 与数据库（与 copilot-states.spec.ts 同策略）。

const CONVERSATION_ID = 7;
const SESSION_ID = 'sess-restore';

const LIST_URL = /\/api\/agent\/conversations(\?.*)?$/;
const detailUrl = (id: number) => new RegExp(`/api/agent/conversations/${id}$`);
const sessionUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}$`);
const reportUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}/report$`);
const completeUrl = new RegExp(`/api/interview/sessions/${SESSION_ID}/complete$`);

const okConversation = {
  id: CONVERSATION_ID,
  title: 'Java 后端模拟面试',
  messageCount: 2,
  isPinned: false,
  updatedAt: new Date().toISOString(),
};

/** 统一 Result 信封：项目约定 HTTP 200 + code != 200 表示业务失败 */
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

/**
 * 自适应候选池：2 个主问题 + 各 1 条候选追问（P4-1：素材只描述「可以问什么」）。
 * 实际发生：Q1 已答 → F1 被策略跳过（没有轮次）→ Q2 为当前题。
 */
const sessionCandidates = [
  candidate({ questionId: 'q1', questionIndex: 0, question: 'Q1: JVM 内存模型？' }),
  candidate({
    questionId: 'q2',
    questionIndex: 1,
    question: 'F1: 堆区分代？',
    isFollowUp: true,
    parentQuestionId: 'q1',
    followUpIndex: 1,
  }),
  candidate({ questionId: 'q3', questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
  candidate({
    questionId: 'q4',
    questionIndex: 3,
    question: 'F2: AOF 重写？',
    category: 'Redis',
    isFollowUp: true,
    parentQuestionId: 'q3',
    followUpIndex: 1,
  }),
];

/** 实际轨迹：只有 Q1 真实发生过（F1 被策略跳过，从未提问） */
const sessionTurns = [
  {
    questionId: 'q1',
    ordinal: 1,
    questionIndex: 0,
    question: 'Q1: JVM 内存模型？',
    category: 'Java',
    userAnswer: '堆和栈',
    answerState: 'ANSWERED',
    decidedAction: 'NEXT_MAIN',
  },
];

/**
 * 报告桩（P4-5 之后的口径）。
 *
 * 结果卡会渲染覆盖明细、评分规则版本与聚合说明——这些是后端真实契约的一部分，
 * 桩里缺字段会让结果卡在渲染时抛错（页面变空白，而不是「展示不全」）。
 */
function reportPayload() {
  return {
    sessionId: SESSION_ID,
    totalQuestions: 4,
    overallScore: 72,
    categoryScores: [
      {
        category: 'Java',
        score: 72,
        questionCount: 2,
        mainGroupCount: 1,
        evaluatedMainGroupCount: 1,
        aggregationNote: '按主问题组内均值',
      },
    ],
    questionDetails: [],
    overallFeedback: '整体表现稳定，深挖空间在追问细节。',
    strengths: ['JVM 基础扎实'],
    improvements: ['追问时给出更具体的排查过程'],
    referenceAnswers: [],
    scoringRuleVersion: 'main-group-v1',
    aggregationMethod: '同一主问题组内取均值，跨主题按组平均',
    coverage: [
      {
        topic: 'JVM',
        required: true,
        actualTurnCount: 2,
        evaluatedMainGroupCount: 1,
        status: 'ASSESSED',
      },
      {
        topic: '数据库',
        required: false,
        actualTurnCount: 0,
        evaluatedMainGroupCount: 0,
        status: 'NOT_ASSESSED',
      },
    ],
    unassessedTopics: ['数据库'],
    skippedQuestionIds: [],
    insufficientEvidenceQuestionIds: [],
  };
}

/** 会话读取的标准桩（P4-1：候选 / 轨迹 / 当前题分开表达） */
function sessionPayload(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: SESSION_ID,
    resumeText: '',
    totalQuestions: sessionCandidates.length,
    currentQuestionIndex: 2,
    currentQuestionId: 'q3',
    currentQuestion: sessionCandidates[2],
    candidates: sessionCandidates,
    turns: sessionTurns,
    status: 'IN_PROGRESS',
    adaptive: true,
    turnVersion: 1,
    plannedDurationMinutes: 20,
    requiredTopics: ['Redis'],
    consumedSeconds: 45,
    remainingSeconds: 1155,
    ...overrides,
  };
}

const interviewSessionBlock = {
  type: 'interview_session',
  session_id: SESSION_ID,
  skill_id: 'java-backend',
  difficulty: 'mid',
  mode: 'TEXT',
  focus: [],
  planned_duration_minutes: 20,
  required_topics: ['Redis'],
  direction_name: 'Java 后端',
};

async function mockConversation(page: import('@playwright/test').Page) {
  await page.route(LIST_URL, (route) => route.fulfill(result(200, 'success', [okConversation])));
  await page.route(detailUrl(CONVERSATION_ID), (route) =>
    route.fulfill(
      result(200, 'success', {
        ...okConversation,
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
            content: '已为你创建面试。',
            // blocks 是 JSON 字符串：进入 Interview Mode 靠这个信号块
            blocks: JSON.stringify([interviewSessionBlock]),
            status: 'COMPLETED',
            createdAt: new Date().toISOString(),
          },
        ],
      }),
    ),
  );
  // 注意：不要再对 detailUrl 注册第二个桩——Playwright 后注册者优先，
  // 会把上面带 messages 的响应整个覆盖掉，表现成「进不去 Interview Mode」。
}

test.beforeEach(async ({ page }) => {
  // 右侧画像面板：与本用例无关，返回空列表避免真实请求
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(result(200, 'success', { tool: 'get_skill_profile', data: { skills: [] } })),
  );
  // 报告完成后会旁路读画像差分。默认桩住它，避免并发 E2E 因本地 Java 未启动
  // 产生无关的代理失败与渲染抖动；需验证差分的用例会在后面覆盖该桩。
  await page.route('**/api/interview/sessions/*/profile-impact', (route) =>
    route.fulfill(result(200, 'success', { sessionId: SESSION_ID, skills: [] })),
  );
});

test.describe('Interview Mode 刷新恢复', () => {
  test('只重放实际已提问的轮次，被跳过的候选追问不出现', async ({ page }) => {
    await mockConversation(page);
    await page.route(sessionUrl, (route) =>
      route.fulfill(
        result(200, 'success', sessionPayload()),
      ),
    );

    await page.goto('/copilot');

    // 进入 Interview Mode 并渲染面试官/答题两条轮次
    await expect(page.getByText('Q1: JVM 内存模型？')).toBeVisible();
    await expect(page.getByText('堆和栈')).toBeVisible();
    // 当前待答题也要渲染，否则刷新后看不到正在回答的问题
    await expect(page.getByText('Q2: Redis 持久化？')).toBeVisible();

    // 关键回归：被策略跳过、从未提问的候选追问不得出现
    await expect(page.getByText('F1: 堆区分代？')).toHaveCount(0);
    await expect(page.getByText('F2: AOF 重写？')).toHaveCount(0);

    // 动态主循环不再用预生成题数伪装进度：展示真实话题、轨迹、必要覆盖与答题用时
    await expect(page.getByText('话题：Redis')).toBeVisible();
    await expect(page.getByText('已答 1 轮')).toBeVisible();
    await expect(page.getByText('覆盖 0/1')).toBeVisible();
    await page.getByText('覆盖 0/1').click();
    const coverageDetail = page.getByTestId('interview-coverage-detail');
    await expect(coverageDetail.getByText('Redis')).toBeVisible();
    await expect(coverageDetail.getByText('待覆盖')).toBeVisible();
    await expect(page.getByText(/已用 \d+:\d{2} · 剩余 20 分钟/)).toBeVisible();
    await expect(page.getByText('第 3 / 4 题')).toHaveCount(0);
  });

  test('完成退出后不被历史里的 interview_session 信号块拉回面试模式', async ({ page }) => {
    await mockConversation(page);
    await page.route(
      /\/api\/agent\/conversations\/\d+\/messages$/,
      (route) => route.fulfill(result(200, 'success', null)),
    );

    let finished = false;
    await page.route(sessionUrl, (route) =>
      route.fulfill(
        result(200, 'success', sessionPayload(finished
          ? {
              status: 'EVALUATED',
              currentQuestion: null,
              currentQuestionId: null,
              endReason: 'USER_FINISHED',
            }
          : {})),
      ),
    );
    await page.route(completeUrl, (route) => {
      finished = true;
      return route.fulfill(result(200, 'success', null));
    });
    await page.route(reportUrl, (route) =>
      route.fulfill(
        result(200, 'success', reportPayload()),
      ),
    );

    await page.goto('/copilot');
    await expect(page.getByText('Q1: JVM 内存模型？')).toBeVisible();

    // 提前交卷 → 评估轮询（3s 一次）→ 结果卡
    await page.getByRole('button', { name: '结束面试' }).click();
    await expect(page.getByRole('button', { name: '完成并返回对话' })).toBeVisible({
      timeout: 15000,
    });

    // 退出：回到普通对话，并留下完成摘要 artifact
    await page.getByRole('button', { name: '完成并返回对话' }).click();
    await expect(page.getByText(/模拟面试完成（Java 后端）/)).toBeVisible();
    await expect(page.getByText('Q1: JVM 内存模型？')).toHaveCount(0);

    // 关键回归：追加摘要会让 messages 变化并重新扫描到那个信号块，
    // 若不登记「已退出」，用户会被立刻拽回面试模式
    await page.waitForTimeout(1200);
    await expect(page.getByText('Q1: JVM 内存模型？')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '结束面试' })).toHaveCount(0);
  });

  test('复盘入口：真实发出 REVIEW_INTERVIEW 且携带被点击的那一场 sessionId', async ({ page }) => {
    await mockConversation(page);
    await page.route(
      /\/api\/agent\/conversations\/\d+\/messages$/,
      (route) => route.fulfill(result(200, 'success', null)),
    );

    let finished = false;
    await page.route(sessionUrl, (route) =>
      route.fulfill(
        result(200, 'success', sessionPayload(finished
          ? {
              status: 'EVALUATED',
              currentQuestion: null,
              currentQuestionId: null,
              endReason: 'USER_FINISHED',
            }
          : {})),
      ),
    );
    await page.route(completeUrl, (route) => {
      finished = true;
      return route.fulfill(result(200, 'success', null));
    });
    await page.route(reportUrl, (route) =>
      route.fulfill(
        result(200, 'success', reportPayload()),
      ),
    );

    // 捕获复盘请求体：这是审计要求的「REVIEW_INTERVIEW 真实前端入口」的落点
    let reviewBody: Record<string, any> | null = null;
    await page.route('**/api/chat/stream', (route) => {
      reviewBody = JSON.parse(route.request().postData() ?? '{}');
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: [
          'data: {"type":"message_delta","payload":{"content":"本场复盘：Java 72 分。"}}',
          'data: {"type":"run_status","payload":{"status":"COMPLETED"}}',
          'data: {"type":"done","payload":{}}',
          '',
        ].join('\n\n'),
      });
    });

    await page.goto('/copilot');
    await expect(page.getByText('Q1: JVM 内存模型？')).toBeVisible();

    await page.getByRole('button', { name: '结束面试' }).click();
    await expect(page.getByRole('button', { name: '让 Copilot 复盘' })).toBeVisible({ timeout: 15000 });
    await page.getByRole('button', { name: '让 Copilot 复盘' }).click();

    await expect.poll(() => reviewBody?.action?.action ?? null).toBe('REVIEW_INTERVIEW');
    expect(reviewBody.action.type).toBe('ACTION_SELECTED');
    // 复盘的是「这一场」，不是笼统的最近一场
    expect(reviewBody.action.payload.sessionId).toBe(SESSION_ID);
    // 复盘面向已结束的会话：点击后即退出 Interview Mode
    await expect(page.getByRole('button', { name: '结束面试' })).toHaveCount(0);
    await expect(page.getByText('本场复盘：Java 72 分。')).toBeVisible();
  });

  test('结果卡展示本场画像变化，并提供可用的场次追溯入口', async ({ page }) => {
    await mockConversation(page);
    await page.route(
      /\/api\/agent\/conversations\/\d+\/messages$/,
      (route) => route.fulfill(result(200, 'success', null)),
    );

    let finished = false;
    await page.route(sessionUrl, (route) =>
      route.fulfill(
        result(200, 'success', sessionPayload(finished
          ? {
              status: 'EVALUATED',
              currentQuestion: null,
              currentQuestionId: null,
              endReason: 'USER_FINISHED',
            }
          : {})),
      ),
    );
    await page.route(completeUrl, (route) => {
      finished = true;
      return route.fulfill(result(200, 'success', null));
    });
    await page.route(reportUrl, (route) =>
      route.fulfill(
        result(200, 'success', reportPayload()),
      ),
    );
    // 差分来自 Java 的证据重算；这里给一条「涨」与一条「本场新增」
    await page.route(new RegExp(`/api/interview/sessions/${SESSION_ID}/profile-impact$`), (route) =>
      route.fulfill(
        result(200, 'success', {
          sessionId: SESSION_ID,
          skills: [
            {
              skill: 'Java',
              beforeScore: 60,
              afterScore: 72,
              delta: 12,
              sessionEvidences: [
                { sourceId: `${SESSION_ID}:q1`, questionKey: 'q1', questionOrdinal: 1, score: 80, occurredAt: '2026-09-15T10:05:00' },
                { sourceId: `${SESSION_ID}:q2`, questionKey: 'q2', questionOrdinal: 2, score: 64, occurredAt: '2026-09-15T10:12:00' },
              ],
            },
            {
              skill: 'Kafka',
              beforeScore: null,
              afterScore: 55,
              delta: 0,
              sessionEvidences: [
                { sourceId: `${SESSION_ID}:q4`, questionKey: 'q4', questionOrdinal: 3, score: 55, occurredAt: '2026-09-15T10:09:00' },
              ],
            },
          ],
        }),
      ),
    );

    await page.goto('/copilot');
    await expect(page.getByText('Q1: JVM 内存模型？')).toBeVisible();

    await page.getByRole('button', { name: '结束面试' }).click();
    await expect(page.getByText('本场画像变化')).toBeVisible({ timeout: 15000 });

    // 有变化的排前：Java（|12|）在 Kafka（新增）之前
    const card = page.locator('text=本场画像变化').locator('xpath=ancestor::div[1]');
    await expect(card.getByText('60 → 72')).toBeVisible();
    await expect(card.getByText('+12')).toBeVisible();
    await expect(card.getByText('本场新增')).toBeVisible();

    // 展开 → 证据落回具体题号与时间，并可跳转到面试记录页定位该场次
    await card.getByRole('button', { name: /Java/ }).first().click();
    await expect(card.getByText('面试 · 第 1 题')).toBeVisible();
    await expect(card.getByText('2026-09-15 10:05')).toBeVisible();

    await card.getByRole('button', { name: '查看该场面试 →' }).first().click();
    await expect(page).toHaveURL(/\/interviews/);
  });
});
