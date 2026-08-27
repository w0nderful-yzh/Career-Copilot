package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import java.time.LocalDateTime;

/**
 * 简历内容 DTO（Agent Tool get_resume 专用）。
 *
 * <p>只返回 Agent 决策所需的最小字段：完整解析文本 + 元信息。
 * 不复用 {@link ResumeDetailDTO}（含分析历史与面试列表，对 Agent 是 Token 浪费）。
 */
public record ResumeContentDTO(
    Long id,
    String filename,
    String resumeText,
    AsyncTaskStatus analyzeStatus,
    LocalDateTime uploadedAt) {}