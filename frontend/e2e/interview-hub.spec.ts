import { expect, test } from '@playwright/test';

function result<T>(code: number, message: string, data: T) {
  return { json: { code, message, data } };
}

test('面试中心默认展示最近记录，用户主动操作后才展开自定义配置', async ({ page }) => {
  await page.route('**/api/interview/skills', (route) =>
    route.fulfill(result(200, 'success', [
      {
        id: 'java-backend',
        name: 'Java 后端',
        description: 'Java 后端面试',
        categories: [],
        isPreset: true,
        sourceJd: null,
      },
    ])),
  );
  await page.route('**/api/resumes', (route) =>
    route.fulfill(result(200, 'success', [])),
  );
  await page.route('**/api/interview/sessions', (route) =>
    route.fulfill(result(200, 'success', [
      {
        sessionId: 'recent-session',
        skillId: 'java-backend',
        difficulty: 'mid',
        resumeId: null,
        totalQuestions: 4,
        status: 'EVALUATED',
        evaluateStatus: 'COMPLETED',
        evaluateError: null,
        evaluateStatusUpdatedAt: null,
        overallScore: 78,
        sourceType: null,
        knowledgeBaseId: null,
        interviewCategory: null,
        createdAt: '2026-09-21T10:00:00',
        completedAt: '2026-09-21T10:20:00',
      },
    ])),
  );
  await page.route(/\/api\/voice-interview\/sessions\?.*$/, (route) =>
    route.fulfill(result(200, 'success', [])),
  );
  await page.route(/\/api\/agent\/conversations(\?.*)?$/, (route) =>
    route.fulfill(result(200, 'success', [])),
  );
  await page.route('**/api/interview/sessions/recent-session/details', (route) =>
    route.fulfill(result(200, 'success', {
      sessionId: 'recent-session',
      skillName: 'Java 后端',
      status: 'EVALUATED',
      answers: [],
    })),
  );

  await page.goto('/interview-hub');

  await expect(page.getByRole('heading', { name: '最近面试记录' })).toBeVisible();
  await expect(page.getByText('Java 后端').last()).toBeVisible();
  await expect(page.getByText('得分')).toBeVisible();
  await expect(page.getByLabel('自定义面试配置')).toHaveCount(0);

  await page.getByRole('button', { name: '发起自定义面试' }).click();
  await expect(page.getByLabel('自定义面试配置')).toBeVisible();
  await expect(page.getByRole('button', { name: '开始文字面试' })).toBeVisible();

  await page.getByRole('button', { name: '收起自定义配置' }).click();
  await expect(page.getByLabel('自定义面试配置')).toHaveCount(0);
  await expect(page.getByText('Java 后端').last()).toBeVisible();

  await page.getByText('Java 后端').last().click();
  await expect(page).toHaveURL(/\/interviews\/recent-session$/);
});
