-- 优化提案/版本记录优化坐标系（P2 待修正）：JD_TARGETED 的目标 JD 与 TARGET_DIRECTION 的方向。
-- 此前提案只存 optimization_type（且上游恒为 GENERAL），版本表的 target_job_id 从未被写入，
-- 导致「按这份 JD 优化」「按 Java 后端方向优化」在审计上都退化成通用优化、无法追溯。
-- 仅记录引用，不加外键：JD 删除不级联清理（与 active_job_id 悬挂由取数失败兜底一致）。

ALTER TABLE resume_optimization_proposals
  ADD COLUMN target_job_id BIGINT,
  ADD COLUMN target_direction VARCHAR(128);

ALTER TABLE resume_versions
  ADD COLUMN target_direction VARCHAR(128);
