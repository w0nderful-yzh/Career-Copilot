import { expect, test } from '@playwright/test';

/**
 * Copilot 输入与消息操作 E2E：键盘约定、编辑已发送消息、重新生成回答。
 *
 * 全部通过 page.route 打桩，不依赖 Java / Python（与 copilot-states.spec.ts 同策略）。
 * 守三件容易悄悄坏掉的事：
 * 1. 输入法合成中的 Enter 不能被当成发送（中文选词误发是用户最先撞到的问题）；
 * 2. Shift+Enter 换行且不发送；
 * 3. 编辑 / 重新生成必须按 Java messageId 精确截断，且不重复追加用户提问。
 */

const CONVERSATION_ID = 7;
const LIST_URL = /\/api\/agent\/conversations(\?.*)?$/;
const detailUrl = (id: number) => new RegExp(`/api/agent/conversations/${id}$`);
const truncateUrl = (id: number, messageId: number) =>
  new RegExp(`/api/agent/conversations/${id}/messages/${messageId}$`);

/** 统一 Result 信封：项目约定 HTTP 200 + code != 200 表示业务失败 */
function result<T>(code: number, message: string, data: T) {
  return { json: { code, message, data } };
}

const okConversation = {
  id: CONVERSATION_ID,
  title: '复盘最近面试',
  messageCount: 2,
  isPinned: false,
  updatedAt: new Date().toISOString(),
};

const userMessage = {
  id: 1,
  role: 'USER',
  content: '第一问',
  blocks: null,
  status: 'COMPLETED',
  createdAt: new Date().toISOString(),
};

const assistantMessage = {
  id: 2,
  role: 'ASSISTANT',
  content: '第一答',
  blocks: null,
  status: 'COMPLETED',
  createdAt: new Date().toISOString(),
};

function detailBody(messages: unknown[]) {
  return { ...okConversation, createdAt: new Date().toISOString(), messages };
}

/** SSE 打桩：只回一条完成事件，够驱动前端收尾 */
function sseBody() {
  return [
    'data: {"type":"run_status","payload":{"status":"RUNNING"}}',
    'data: {"type":"run_status","payload":{"status":"COMPLETED"}}',
    'data: {"type":"done","payload":{}}',
    '',
  ].join('\n\n');
}

test.beforeEach(async ({ page }) => {
  // 右侧画像面板与本用例无关，返回空列表避免真实请求
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(result(200, 'success', { tool: 'get_skill_profile', data: { skills: [] } })),
  );
});

test.describe('Copilot 输入键盘约定', () => {
  test('输入法选词的 Enter 不发送，Shift+Enter 换行，普通 Enter 才发送', async ({ page }) => {
    const streamBodies: Array<Record<string, unknown>> = [];

    // 空会话：直接落在首屏，输入框可用
    await page.route(LIST_URL, (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill(result(200, 'success', { ...okConversation, title: '新对话' }));
      }
      return route.fulfill(result(200, 'success', []));
    });
    await page.route('**/api/chat/stream', (route) => {
      streamBodies.push(JSON.parse(route.request().postData() ?? '{}'));
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody(),
      });
    });

    await page.goto('/copilot');

    const composer = page.getByPlaceholder('输入你的目标或问题…');
    await composer.click();
    await composer.type('拼音候选');

    // 合成态：部分输入法只给哨兵 keyCode，这里连 isComposing 一起覆盖
    await composer.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
    expect(streamBodies).toHaveLength(0);

    // Shift+Enter 换行且不发送
    await composer.press('Shift+Enter');
    await composer.type('第二行');
    await expect(composer).toHaveValue('拼音候选\n第二行');
    expect(streamBodies).toHaveLength(0);

    // 普通 Enter 正常发送
    await composer.press('Enter');
    await expect.poll(() => streamBodies.length).toBe(1);
    expect(streamBodies[0].message).toBe('拼音候选\n第二行');
  });
});

test.describe('Copilot 消息编辑与重新生成', () => {
  test('编辑中间消息：确认后按 messageId 截断并以新内容重发', async ({ page }) => {
    let truncatedUrl: string | null = null;
    const streamBodies: Array<Record<string, unknown>> = [];

    await page.route(LIST_URL, (route) => route.fulfill(result(200, 'success', [okConversation])));
    await page.route(detailUrl(CONVERSATION_ID), (route) =>
      route.fulfill(result(200, 'success', detailBody([userMessage, assistantMessage]))),
    );
    await page.route(truncateUrl(CONVERSATION_ID, 1), (route) => {
      truncatedUrl = new URL(route.request().url()).pathname;
      return route.fulfill(result(200, 'success', 2));
    });
    await page.route('**/api/chat/stream', (route) => {
      streamBodies.push(JSON.parse(route.request().postData() ?? '{}'));
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody(),
      });
    });

    await page.goto('/copilot');
    await expect(page.getByText('第一答')).toBeVisible();

    await page.getByRole('button', { name: '编辑' }).click();
    const editor = page.getByTestId('message-editor');
    await editor.fill('第一问改了');
    await page.getByRole('button', { name: '保存并重新发送' }).click();

    // 其后还有 1 条对话：必须先确认，不能直接删
    await expect(page.getByText('编辑这条消息？')).toBeVisible();
    await page.getByRole('button', { name: '删除并重新发送' }).click();

    await expect.poll(() => truncatedUrl).toBe(`/api/agent/conversations/${CONVERSATION_ID}/messages/1`);
    await expect.poll(() => streamBodies.length).toBe(1);
    expect(streamBodies[0].message).toBe('第一问改了');
    // 旧提问与其后的回答都从界面消失，只留下编辑后的新提问
    await expect(page.getByText('第一问改了')).toBeVisible();
    await expect(page.getByText('第一答')).toHaveCount(0);
  });

  test('重新生成：删除旧回答并以 regenerate 语义重跑，不重复提问', async ({ page }) => {
    let truncatedUrl: string | null = null;
    const streamBodies: Array<Record<string, unknown>> = [];

    await page.route(LIST_URL, (route) => route.fulfill(result(200, 'success', [okConversation])));
    await page.route(detailUrl(CONVERSATION_ID), (route) =>
      route.fulfill(result(200, 'success', detailBody([userMessage, assistantMessage]))),
    );
    await page.route(truncateUrl(CONVERSATION_ID, 2), (route) => {
      truncatedUrl = new URL(route.request().url()).pathname;
      return route.fulfill(result(200, 'success', 1));
    });
    await page.route('**/api/chat/stream', (route) => {
      streamBodies.push(JSON.parse(route.request().postData() ?? '{}'));
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody(),
      });
    });

    await page.goto('/copilot');
    await expect(page.getByText('第一答')).toBeVisible();

    await page.getByRole('button', { name: '重新生成' }).click();

    // 只删该轮回答；提问保留在历史里，本轮不重复落库
    await expect.poll(() => truncatedUrl).toBe(`/api/agent/conversations/${CONVERSATION_ID}/messages/2`);
    await expect.poll(() => streamBodies.length).toBe(1);
    expect(streamBodies[0].message).toBe('第一问');
    expect(streamBodies[0].regenerate).toBe(true);
    // 界面上不应出现第二条相同的提问
    await expect(page.getByText('第一问')).toHaveCount(1);
  });
});
