import type { AttachmentRef } from '../types/copilot';

/** 前端 camelCase 附件引用转换为 Python Agent 的 snake_case 协议。 */
export function serializeAttachments(attachments: AttachmentRef[]) {
  return attachments.map((attachment) => ({
    kind: attachment.kind,
    resume_id: attachment.resumeId,
    job_id: attachment.jobId,
    filename: attachment.filename ?? null,
    duplicate: attachment.duplicate ?? false,
  }));
}
