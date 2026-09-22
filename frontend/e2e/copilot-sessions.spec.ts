import { expect, test, type Page } from '@playwright/test';

// 会话管理（重命名 / 归档 / 恢复）验收。
//
// 归档在数据层早已生效（列表只查 ACTIVE），但此前既没有归档入口、也没有查看与恢复入口，
// 已归档会话只进不出。本用例覆盖补上入口之后的完整来回。
//
// 全部 page.route 打桩，用一份内存 store 模拟后端状态变化，不依赖 Java 与数据库。

const LIST_URL = /\/api\/agent\/conversations(\?.*)?$/;
const MUTATION_URL = /\/api\/agent\/conversations\/\d+\/(title|pin|archive|restore)$/;
const detailUrl = (id: number) => new RegExp(`/api/agent/conversations/${id}$`);

interface Conversation {
  id: number;
  title: string;
  messageCount: number;
  isPinned: boolean;
  updatedAt: string;
  status: 'ACTIVE' | 'ARCHIVED';
}

/** 统一 Result 信封：项目约定 HTTP 200 + code != 200 表示业务失败 */
function result<T>(code: number, message: string, data: T) {
  return { json: { code, message, data } };
}

function seedConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 7,
    title: '要归档的会话',
    messageCount: 2,
    isPinned: false,
    updatedAt: new Date().toISOString(),
    status: 'ACTIVE',
    ...overrides,
  };
}

/** 用一份内存 store 承接列表读与状态变更写，使 UI 操作能真实反映到后续列表 */
async function mockConversationBackend(page: Page, store: Conversation[]) {
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(result(200, 'success', { tool: 'get_skill_profile', data: { skills: [] } })),
  );

  await page.route(LIST_URL, (route) => {
    // 列表接口带 ?status=ACTIVE|ARCHIVED，按状态过滤同一份 store
    const archived = route.request().url().includes('status=ARCHIVED');
    const items = store
      .filter((item) => (archived ? item.status === 'ARCHIVED' : item.status === 'ACTIVE'))
      .map(({ status: _status, ...rest }) => rest);
    return route.fulfill(result(200, 'success', items));
  });

  await page.route(detailUrl(7), (route) =>
    route.fulfill(
      result(200, 'success', {
        id: 7,
        title: store.find((item) => item.id === 7)?.title ?? '',
        isPinned: false,
        messages: [],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }),
    ),
  );

  await page.route(MUTATION_URL, (route) => {
    const match = new URL(route.request().url()).pathname.match(
      /\/api\/agent\/conversations\/(\d+)\/(\w+)$/,
    );
    const id = Number(match?.[1]);
    const action = match?.[2];
    const item = store.find((conversation) => conversation.id === id);
    if (item) {
      if (action === 'title') {
        const body = JSON.parse(route.request().postData() ?? '{}') as { title?: string };
        item.title = body.title ?? item.title;
      } else if (action === 'archive') {
        item.status = 'ARCHIVED';
      } else if (action === 'restore') {
        item.status = 'ACTIVE';
      } else if (action === 'pin') {
        item.isPinned = !item.isPinned;
      }
    }
    return route.fulfill(result(200, 'success', null));
  });
}

test.describe('Copilot 会话管理', () => {
  // 断言一律限定在会话列表内：会话标题同时会出现在页面头部（当前打开会话），
  // 不限定作用域会触发 strict mode violation
  const items = (page: Page) => page.getByTestId('session-items');

  test('重命名会话：就地编辑并保存到列表', async ({ page }) => {
    const store = [seedConversation({ title: '旧标题' })];
    await mockConversationBackend(page, store);

    await page.goto('/copilot');
    await expect(items(page).getByText('旧标题')).toBeVisible();

    await items(page).getByText('旧标题').hover();
    await items(page).getByRole('button', { name: '重命名' }).click();

    const input = page.getByLabel('会话标题');
    await expect(input).toBeVisible();
    await input.fill('新标题');
    await input.press('Enter');

    await expect(items(page).getByText('新标题')).toBeVisible();
    await expect(items(page).getByText('旧标题')).toHaveCount(0);
  });

  test('归档后可查看并恢复，归档集合不再是黑洞', async ({ page }) => {
    const store = [seedConversation()];
    await mockConversationBackend(page, store);

    await page.goto('/copilot');
    await expect(items(page).getByText('要归档的会话')).toBeVisible();

    // 归档：从活跃列表收起
    await items(page).getByText('要归档的会话').hover();
    await items(page).getByRole('button', { name: '归档（保留记录，可恢复）' }).click();
    await expect(page.getByText('还没有对话，开始你的第一段对话吧')).toBeVisible();

    // 进入归档视图：会话仍在（软隐藏而非删除）
    await page.getByRole('button', { name: '已归档' }).click();
    await expect(items(page).getByText('要归档的会话')).toBeVisible();

    // 恢复：回到活跃列表，归档视图清空
    await items(page).getByText('要归档的会话').hover();
    await items(page).getByRole('button', { name: '恢复到最近对话' }).click();
    await expect(page.getByText('还没有归档的对话')).toBeVisible();

    await page.getByRole('button', { name: '返回最近对话' }).click();
    await expect(items(page).getByText('要归档的会话')).toBeVisible();
  });
});
