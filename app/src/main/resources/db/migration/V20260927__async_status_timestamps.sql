-- P6-1：异步任务状态时间必须由 Java 业务后端持久化。
-- 前端据此在刷新、跨会话和重试后判断任务是否卡住，不使用浏览器本地计时猜测。
ALTER TABLE resumes
  ADD COLUMN analyze_status_updated_at TIMESTAMP;

ALTER TABLE knowledge_bases
  ADD COLUMN vector_status_updated_at TIMESTAMP;

ALTER TABLE interview_sessions
  ADD COLUMN evaluate_status_updated_at TIMESTAMP;

UPDATE resumes
SET analyze_status_updated_at = uploaded_at
WHERE analyze_status_updated_at IS NULL;

UPDATE knowledge_bases
SET vector_status_updated_at = uploaded_at
WHERE vector_status_updated_at IS NULL;

UPDATE interview_sessions
SET evaluate_status_updated_at = COALESCE(completed_at, created_at)
WHERE evaluate_status IS NOT NULL
  AND evaluate_status_updated_at IS NULL;
