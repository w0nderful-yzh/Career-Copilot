import assert from 'node:assert/strict';
import test from 'node:test';

import { serializeAttachments } from '../utils/agentChatProtocol.ts';

test('JD 附件把 jobId 映射到 Agent 协议的 job_id', () => {
  assert.deepEqual(
    serializeAttachments([
      { kind: 'job_description', jobId: 42, filename: 'java-backend.pdf' },
    ]),
    [
      {
        kind: 'job_description',
        resume_id: undefined,
        job_id: 42,
        filename: 'java-backend.pdf',
        duplicate: false,
      },
    ],
  );
});

test('简历附件继续保留 resume_id 与重复标记', () => {
  assert.deepEqual(
    serializeAttachments([
      { kind: 'resume', resumeId: 11, filename: 'resume.pdf', duplicate: true },
    ]),
    [
      {
        kind: 'resume',
        resume_id: 11,
        job_id: undefined,
        filename: 'resume.pdf',
        duplicate: true,
      },
    ],
  );
});
