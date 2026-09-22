import { expect, test } from '@playwright/test';

// Copilot 主链路的加载 / 错误 / 停止态验收。
//
// 全部通过 page.route 打桩，不依赖 Java 与数据库（与 voice-interview.spec.ts 同策略）。
// 覆盖 P1 待收口的两条：加载失败不得退化成「新会话首屏」；停止生成必须在历史回放中可见。
//
// 注意：失败窗口用「开关变量」控制，不能按调用序号打桩——应用自身会重新拉取列表，
// 序号式的桩会在第二次请求就被成功响应覆盖，测不出错误态（实测踩过）。

const CONVERSATION_ID = 7;

// 路由匹配必须与查询参数无关：列表接口会带 ?status=ACTIVE，写死路径 glob 会漏匹配，
// 请求落到真实后端（ECONNREFUSED）后表现为「列表加载失败」，用例会以错误的原因失败。
const LIST_URL = /\/api\/agent\/conversations(\?.*)?$/;
const detailUrl = (id: number) => new RegExp(`/api/agent/conversations/${id}$`);

const okConversation = {
  id: CONVERSATION_ID,
  title: '复盘最近面试',
  messageCount: 2,
  isPinned: false,
  updatedAt: new Date().toISOString(),
};

/** 统一 Result 信封：项目约定 HTTP 200 + code != 200 表示业务失败 */
function result<T>(code: number, message: string, data: T) {
  return { json: { code, message, data } };
}

test.beforeEach(async ({ page }) => {
  // 右侧画像面板：与本用例无关，返回空列表避免真实请求
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(result(200, 'success', { tool: 'get_skill_profile', data: { skills: [] } })),
  );
});

test.describe('Copilot 加载与错误态', () => {
  test('会话详情加载失败时给出错误与重试，而不是伪装成空会话', async ({ page }) => {
    let detailFails = true;

    await page.route(LIST_URL, (route) =>
      route.fulfill(result(200, 'success', [okConversation])),
    );
    await page.route(detailUrl(CONVERSATION_ID), (route) => {
      if (detailFails) {
        // 此前只 console.error，界面会渲染出新会话首屏
        return route.fulfill(result(500, '数据库连接失败', null));
      }
      return route.fulfill(
        result(200, 'success', {
          ...okConversation,
          createdAt: new Date().toISOString(),
          messages: [
            {
              id: 1,
              role: 'USER',
              content: '帮我复盘这次面试',
              blocks: null,
              status: 'COMPLETED',
              createdAt: new Date().toISOString(),
            },
            {
              id: 2,
              role: 'ASSISTANT',
              content: '先看整体分数：',
              blocks: null,
              // 用户当时点了「停止生成」，刷新后必须仍然看得出来
              status: 'STOPPED',
              createdAt: new Date().toISOString(),
            },
          ],
        }),
      );
    });

    await page.goto('/copilot');

    // 失败态可见，且不出现「新会话首屏」文案
    await expect(page.getByText('对话加载失败')).toBeVisible();
    await expect(page.getByText('数据库连接失败')).toBeVisible();
    await expect(page.getByText('今天想为求职推进哪一步？')).toHaveCount(0);

    // 重试 → 拿到真实历史，停止态按「已停止」渲染而非正常完成
    detailFails = false;
    await page.getByRole('button', { name: '重试' }).click();
    await expect(page.getByText('先看整体分数：')).toBeVisible();
    await expect(page.getByText('已停止生成，以上为已产出的部分')).toBeVisible();
    await expect(page.getByText('今天想为求职推进哪一步？')).toHaveCount(0);
  });

  test('会话列表加载失败时不显示「还没有对话」的空态', async ({ page }) => {
    let listFails = true;

    await page.route(LIST_URL, (route) => {
      if (listFails) {
        return route.fulfill(result(500, '后端服务不可达', null));
      }
      return route.fulfill(result(200, 'success', [okConversation]));
    });
    await page.route(detailUrl(CONVERSATION_ID), (route) =>
      route.fulfill(
        result(200, 'success', {
          ...okConversation,
          createdAt: new Date().toISOString(),
          messages: [],
        }),
      ),
    );

    await page.goto('/copilot');

    await expect(page.getByText('后端服务不可达')).toBeVisible();
    await expect(page.getByText('还没有对话，开始你的第一段对话吧')).toHaveCount(0);

    // 重试成功后列出会话
    listFails = false;
    await page.getByRole('button', { name: '重试' }).click();
    await expect(page.getByText('复盘最近面试')).toBeVisible();
    await expect(page.getByText('后端服务不可达')).toHaveCount(0);
  });

  test('画像依赖失败时不展示旧数据，用户重试后恢复 Evidence', async ({ page }) => {
    let profileFails = true;
    await page.route(LIST_URL, (route) =>
      route.fulfill(result(200, 'success', [])),
    );
    await page.route('**/api/agent/tools/get_skill_profile', (route) => {
      if (profileFails) {
        return route.fulfill(result(500, '画像服务暂不可用', null));
      }
      return route.fulfill(result(200, 'success', {
        tool: 'get_skill_profile',
        data: {
          skills: [{ skill: 'JVM', score: 68, evidenceCount: 3, evidences: [] }],
          declaredSkills: [],
        },
      }));
    });

    await page.goto('/copilot');

    await expect(page.getByText('画像加载失败，当前不展示旧数据')).toBeVisible();
    await expect(page.getByText('Java 后端求职')).toHaveCount(0);
    await expect(page.getByText('今日任务 · 示例')).toHaveCount(0);

    profileFails = false;
    await page.getByRole('button', { name: '重试画像' }).click();
    await expect(page.getByText('JVM')).toBeVisible();
    await expect(page.getByText('68', { exact: true })).toBeVisible();
    await expect(page.getByText('画像加载失败，当前不展示旧数据')).toHaveCount(0);
  });
});
