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

function question(overrides: Record<string, unknown>) {
  return {
    type: 'MAIN',
    category: 'Java',
    topicSummary: null,
    userAnswer: null,
    score: null,
    feedback: null,
    isFollowUp: false,
    parentQuestionIndex: null,
    ...overrides,
  };
}

/**
 * 自适应题库：2 个主问题 + 各 1 条候选追问。
 * 实际发生：Q1 已答 → F1 被策略跳过（没有作答记录）→ Q2 为当前题。
 * F1 / F2 仍留在题库数组里，这正是「按索引遍历」会出错的地方。
 */
const sessionQuestions = [
  question({ questionIndex: 0, question: 'Q1: JVM 内存模型？', userAnswer: '堆和栈' }),
  question({
    questionIndex: 1,
    question: 'F1: 堆区分代？',
    isFollowUp: true,
    parentQuestionIndex: 0,
  }),
  question({ questionIndex: 2, question: 'Q2: Redis 持久化？', category: 'Redis' }),
  question({
    questionIndex: 3,
    question: 'F2: AOF 重写？',
    category: 'Redis',
    isFollowUp: true,
    parentQuestionIndex: 2,
  }),
];

const interviewSessionBlock = {
  type: 'interview_session',
  session_id: SESSION_ID,
  skill_id: 'java-backend',
  difficulty: 'mid',
  mode: 'TEXT',
  focus: [],
  question_count: 2,
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
});

test.describe('Interview Mode 刷新恢复', () => {
  test('只重放实际已提问的轮次，被跳过的候选追问不出现', async ({ page }) => {
    await mockConversation(page);
    await page.route(sessionUrl, (route) =>
      route.fulfill(
        result(200, 'success', {
          sessionId: SESSION_ID,
          resumeText: '',
          totalQuestions: sessionQuestions.length,
          currentQuestionIndex: 2,
          questions: sessionQuestions,
          status: 'IN_PROGRESS',
          adaptive: true,
        }),
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

    // 进度分母用主问题数（2），不是题库总数（4）——否则会显示「第 3 / 4 题」这种虚高进度
    await expect(page.getByText('已答 1 题 · 主题 2/2')).toBeVisible();
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
        result(200, 'success', {
          sessionId: SESSION_ID,
          resumeText: '',
          totalQuestions: sessionQuestions.length,
          currentQuestionIndex: finished ? 4 : 2,
          questions: sessionQuestions,
          status: finished ? 'EVALUATED' : 'IN_PROGRESS',
          adaptive: true,
        }),
      ),
    );
    await page.route(completeUrl, (route) => {
      finished = true;
      return route.fulfill(result(200, 'success', null));
    });
    await page.route(reportUrl, (route) =>
      route.fulfill(
        result(200, 'success', {
          overallScore: 72,
          categoryScores: [{ category: 'Java', score: 72 }],
        }),
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
        result(200, 'success', {
          sessionId: SESSION_ID,
          resumeText: '',
          totalQuestions: sessionQuestions.length,
          currentQuestionIndex: finished ? 4 : 2,
          questions: sessionQuestions,
          status: finished ? 'EVALUATED' : 'IN_PROGRESS',
          adaptive: true,
        }),
      ),
    );
    await page.route(completeUrl, (route) => {
      finished = true;
      return route.fulfill(result(200, 'success', null));
    });
    await page.route(reportUrl, (route) =>
      route.fulfill(
        result(200, 'success', {
          overallScore: 72,
          categoryScores: [{ category: 'Java', score: 72 }],
        }),
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
});
