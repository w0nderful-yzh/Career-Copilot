import { expect, test } from '@playwright/test';

const CONVERSATION_ID = 19;
const RESUME_ID = 1;
const SOURCE_VERSION_ID = 5;
const PROPOSAL_ID = 77;

function result<T>(code: number, message: string, data: T) {
  return { json: { code, message, data } };
}

const patches = [
  {
    id: 'patch_1',
    type: 'REPLACE',
    path: 'projects[0].bullets[0]',
    oldValue: '负责后端开发工作',
    newValue: '基于 Spring Boot 主导后端开发工作',
    reason: '突出与 JD 直接匹配的框架经验',
    evidence: ['项目 Demo 使用 Spring Boot'],
    impact: '项目经历首条职责描述',
    verificationRequired: [],
  },
  {
    id: 'patch_2',
    type: 'ADD',
    path: 'projects[0].bullets',
    oldValue: null,
    newValue: '补充数据库设计职责',
    reason: '回应 JD 的数据库要求',
    evidence: [],
    impact: '项目经历的职责覆盖',
    verificationRequired: ['是否真实负责过数据库设计'],
  },
];

const gapBlock = {
  type: 'resume_gap_analysis',
  resumeId: RESUME_ID,
  jobId: 42,
  jobTitle: 'Java 后端实习',
  matchLevel: 'MEDIUM',
  summary: 'Spring Boot 项目相关，MySQL 经验仍缺少简历证据。',
  items: [
    {
      requirement: '熟悉 Spring Boot',
      status: 'MATCHED',
      resumeEvidence: ['Demo（Spring Boot）'],
      impact: '支撑核心框架匹配',
      verificationRequired: [],
    },
    {
      requirement: '熟悉 MySQL',
      status: 'UNKNOWN',
      resumeEvidence: [],
      impact: '数据库要求暂无法证明',
      verificationRequired: ['是否在项目中实际使用 MySQL'],
    },
  ],
};

const optimizationBlock = {
  type: 'resume_optimization',
  proposalId: PROPOSAL_ID,
  resumeId: RESUME_ID,
  versionId: SOURCE_VERSION_ID,
  summary: '针对 Java 后端岗位突出可验证的项目证据',
  patches,
  optimizationType: 'JD_TARGETED',
};

test('简历 → JD Gap → Patch → 预览 → 确认 → 新版本 → PDF 闭环', async ({ page }) => {
  const conversation = {
    id: CONVERSATION_ID,
    title: 'JD 定向优化',
    messageCount: 2,
    isPinned: false,
    updatedAt: new Date().toISOString(),
  };
  await page.route(/\/api\/agent\/conversations(\?.*)?$/, (route) =>
    route.fulfill(result(200, 'success', [conversation])),
  );
  await page.route(`**/api/agent/conversations/${CONVERSATION_ID}`, (route) =>
    route.fulfill(result(200, 'success', {
      ...conversation,
      createdAt: new Date().toISOString(),
      activeResumeId: RESUME_ID,
      activeJobId: 42,
      messages: [
        {
          id: 1,
          role: 'USER',
          content: '按这份 JD 优化我的简历',
          blocks: null,
          status: 'COMPLETED',
          createdAt: new Date().toISOString(),
        },
        {
          id: 2,
          role: 'ASSISTANT',
          content: '先核对 Gap，再决定要应用的修改。',
          blocks: JSON.stringify([gapBlock, optimizationBlock]),
          status: 'COMPLETED',
          createdAt: new Date().toISOString(),
        },
      ],
    })),
  );
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(result(200, 'success', { tool: 'get_skill_profile', data: { skills: [] } })),
  );
  await page.route(`**/api/jobs/42`, (route) =>
    route.fulfill(result(200, 'success', { id: 42, title: 'Java 后端实习' })),
  );
  await page.route(`**/api/resume-optimization/proposals/${PROPOSAL_ID}`, (route) =>
    route.fulfill(result(200, 'success', { id: PROPOSAL_ID, status: 'PENDING' })),
  );
  await page.route(`**/api/resume-versions/${SOURCE_VERSION_ID}`, (route) =>
    route.fulfill(result(200, 'success', {
      id: SOURCE_VERSION_ID,
      resumeId: RESUME_ID,
      version: 1,
      source: 'IMPORT',
      confirmationStatus: 'ACTIVE',
      optimizationType: null,
      targetJobId: null,
      targetDirection: null,
      content: {
        basicInfo: { name: '张三' },
        projects: [{ name: 'Demo', techStack: 'Spring Boot', bullets: ['负责后端开发工作'] }],
      },
      missingFields: [],
      createdAt: new Date().toISOString(),
      sourceCreatedAt: new Date().toISOString(),
    })),
  );

  const previewPatchIds: string[][] = [];
  await page.route('**/internal/agent/resume/preview', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    previewPatchIds.push((body.patches ?? []).map((patch: { id: string }) => patch.id));
    await route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' });
  });

  let applyBody: Record<string, any> | null = null;
  await page.route('**/api/chat/stream', (route) => {
    applyBody = JSON.parse(route.request().postData() ?? '{}');
    return route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: [
        'data: {"type":"message_delta","payload":{"content":"已生成 V2。"}}',
        `data: {"type":"block","payload":{"type":"navigation","route":"RESUME_DETAIL","label":"查看简历版本","params":{"resumeId":${RESUME_ID}}}}`,
        'data: {"type":"run_status","payload":{"status":"COMPLETED"}}',
        'data: {"type":"done","payload":{}}',
        '',
      ].join('\n\n'),
    });
  });

  await page.goto('/copilot');

  await expect(page.getByText('JD Gap 分析')).toBeVisible();
  await expect(page.getByText('熟悉 Spring Boot')).toBeVisible();
  await expect(page.getByText('是否在项目中实际使用 MySQL')).toBeVisible();
  await expect(page.getByText('修改理由').first()).toBeVisible();
  await expect(page.getByText('项目 Demo 使用 Spring Boot')).toBeVisible();
  await expect(page.getByText('是否真实负责过数据库设计')).toBeVisible();

  await expect.poll(() => previewPatchIds.at(-1)).toEqual(['patch_1', 'patch_2']);
  await page.getByRole('checkbox').nth(1).click();
  await expect.poll(() => previewPatchIds.at(-1)).toEqual(['patch_1']);

  await page.getByRole('button', { name: '核对并应用（1/2）' }).click();
  await expect(page.getByText('确认生成新版本？')).toBeVisible();
  expect(applyBody).toBeNull();
  await page.getByRole('button', { name: '确认生成新版本' }).click();

  await expect.poll(() => applyBody?.action?.payload?.patchIds ?? null).toEqual(['patch_1']);
  expect(applyBody?.action?.payload?.proposalId).toBe(PROPOSAL_ID);
  await expect(page.getByRole('button', { name: '查看简历版本' })).toBeVisible();

  await page.route(`**/api/resumes/${RESUME_ID}/detail`, (route) =>
    route.fulfill(result(200, 'success', {
      id: RESUME_ID,
      filename: 'resume.pdf',
      uploadedAt: new Date().toISOString(),
      analyzeStatus: 'COMPLETED',
      analyses: [],
      interviews: [],
    })),
  );
  await page.route(`**/api/resumes/${RESUME_ID}/versions`, (route) =>
    route.fulfill(result(200, 'success', [
      {
        id: 6,
        resumeId: RESUME_ID,
        version: 2,
        source: 'AI_OPTIMIZE',
        confirmationStatus: 'ACTIVE',
        optimizationType: 'JD_TARGETED',
        targetJobId: 42,
        targetDirection: null,
        content: { basicInfo: { name: '张三' }, projects: [] },
        missingFields: [],
        createdAt: new Date().toISOString(),
        sourceCreatedAt: new Date().toISOString(),
      },
      {
        id: SOURCE_VERSION_ID,
        resumeId: RESUME_ID,
        version: 1,
        source: 'IMPORT',
        confirmationStatus: 'ACTIVE',
        optimizationType: null,
        targetJobId: null,
        targetDirection: null,
        content: { basicInfo: { name: '张三' }, projects: [] },
        missingFields: [],
        createdAt: new Date().toISOString(),
        sourceCreatedAt: new Date().toISOString(),
      },
    ])),
  );
  let exportedVersionId: string | null = null;
  await page.route('**/api/resume-versions/6/export-pdf', (route) => {
    exportedVersionId = '6';
    return route.fulfill(result(200, 'success', {
      fileKey: 'resume-exports/resume_v2.pdf',
      url: '/unused',
      filename: 'resume_v2.pdf',
      sizeBytes: 12,
    }));
  });
  await page.route('**/api/resume-exports/download?*', (route) =>
    route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' }),
  );

  await page.getByRole('button', { name: '查看简历版本' }).click();
  await expect(page.getByRole('heading', { name: 'resume.pdf' })).toBeVisible();
  await page.getByRole('button', { name: '简历版本', exact: true }).click();
  await expect(page.getByText('结构化版本（2）')).toBeVisible();
  await expect(page.getByText('V2')).toBeVisible();
  await expect(page.getByText('V1')).toBeVisible();
  await expect(page.getByText('JD 定向 · JD#42')).toBeVisible();
  await page.getByRole('button', { name: '导出 PDF' }).first().click();
  await expect.poll(() => exportedVersionId).toBe('6');
});

test('简历闭环支持取消、刷新恢复，以及 Preview 失败后的显式重试', async ({ page }) => {
  const conversation = {
    id: CONVERSATION_ID,
    title: '待确认的 JD 定向优化',
    messageCount: 2,
    isPinned: false,
    updatedAt: new Date().toISOString(),
  };
  let proposalStatus: 'PENDING' | 'REJECTED' = 'PENDING';
  let previewAttempts = 0;
  let applyCalls = 0;
  let rejectCalls = 0;

  await page.route(/\/api\/agent\/conversations(\?.*)?$/, (route) =>
    route.fulfill(result(200, 'success', [conversation])),
  );
  await page.route(`**/api/agent/conversations/${CONVERSATION_ID}`, (route) =>
    route.fulfill(result(200, 'success', {
      ...conversation,
      createdAt: new Date().toISOString(),
      activeResumeId: RESUME_ID,
      activeJobId: 42,
      messages: [
        {
          id: 1,
          role: 'USER',
          content: '按 JD 优化简历',
          blocks: null,
          status: 'COMPLETED',
          createdAt: new Date().toISOString(),
        },
        {
          id: 2,
          role: 'ASSISTANT',
          content: '请核对后决定是否应用。',
          blocks: JSON.stringify([gapBlock, optimizationBlock]),
          status: 'COMPLETED',
          createdAt: new Date().toISOString(),
        },
      ],
    })),
  );
  await page.route('**/api/agent/tools/get_skill_profile', (route) =>
    route.fulfill(result(200, 'success', { tool: 'get_skill_profile', data: { skills: [] } })),
  );
  await page.route('**/api/jobs/42', (route) =>
    route.fulfill(result(200, 'success', { id: 42, title: 'Java 后端实习' })),
  );
  await page.route(`**/api/resume-optimization/proposals/${PROPOSAL_ID}`, (route) =>
    route.fulfill(result(200, 'success', { id: PROPOSAL_ID, status: proposalStatus })),
  );
  await page.route(`**/api/resume-optimization/proposals/${PROPOSAL_ID}/reject`, (route) => {
    rejectCalls += 1;
    proposalStatus = 'REJECTED';
    return route.fulfill(result(200, 'success', { id: PROPOSAL_ID, status: proposalStatus }));
  });
  await page.route(`**/api/resume-versions/${SOURCE_VERSION_ID}`, (route) =>
    route.fulfill(result(200, 'success', {
      id: SOURCE_VERSION_ID,
      resumeId: RESUME_ID,
      version: 1,
      source: 'IMPORT',
      confirmationStatus: 'ACTIVE',
      content: {
        basicInfo: { name: '张三' },
        projects: [{ name: 'Demo', bullets: ['负责后端开发工作'] }],
      },
      missingFields: [],
      createdAt: new Date().toISOString(),
      sourceCreatedAt: new Date().toISOString(),
    })),
  );
  await page.route('**/internal/agent/resume/preview', (route) => {
    previewAttempts += 1;
    if (previewAttempts === 1) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, message: 'PDF 渲染服务暂不可用', data: null }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' });
  });
  await page.route('**/api/chat/stream', (route) => {
    applyCalls += 1;
    return route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: 'data: {"type":"done","payload":{}}\n\n',
    });
  });

  await page.goto('/copilot');

  await expect(page.getByText('预览失败：PDF 渲染服务暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重试预览' }).click();
  await expect(page.getByTitle('简历优化预览')).toBeVisible();
  expect(previewAttempts).toBe(2);

  // 取消发生在真正的 CONFIRM_WRITE 之前，不得发起应用请求。
  await page.getByRole('button', { name: '核对并应用（2/2）' }).click();
  await expect(page.getByText('确认生成新版本？')).toBeVisible();
  await page.getByRole('button', { name: '取消', exact: true }).click();
  expect(applyCalls).toBe(0);

  // 刷新后由 Java 的 PENDING 状态恢复同一提案，仍可继续决策。
  await page.reload();
  await expect(page.getByText('JD Gap 分析')).toBeVisible();
  await expect(page.getByRole('button', { name: '核对并应用（2/2）' })).toBeEnabled();

  await page.getByRole('button', { name: '全部忽略' }).click();
  await expect(page.getByText('已忽略本次优化建议，简历内容未改动')).toBeVisible();
  expect(rejectCalls).toBe(1);
  expect(applyCalls).toBe(0);

  // 再刷新仍以权威 REJECTED 状态锁定，不能重复写入。
  await page.reload();
  await expect(page.getByText('已忽略本次优化建议，简历内容未改动')).toBeVisible();
  await expect(page.getByRole('button', { name: '已忽略' })).toBeDisabled();
});
