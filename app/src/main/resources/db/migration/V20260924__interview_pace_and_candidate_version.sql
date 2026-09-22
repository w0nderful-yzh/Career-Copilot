-- P4-4b / P4Q-3c：针对回答的追问与完整节奏控制所需的会话级字段。
--
-- 落两样东西：
--   difficulty_preference  本场难度偏好（junior/mid/senior）：用户「太简单了/能浅一点吗」
--                          等显式节奏指令当轮生效，只影响本场后续选题与生成的难度基线，
--                          **不写长期画像偏好**（3.3：不把本场调整写成长期偏好）。NULL = 未调整。
--   candidate_version      候选代次（P4-4b）：受限生成与后台预备候选都带上当时的代次；
--                          后台异步预备结果回写前比对代次，晚到/过期的结果不驱动状态。
--
-- 生成题与候选来源/版本本身存于 questions_json（TEXT）：候选池就是素材权威，
-- 运行期追加的题与之同处一份 JSON，恢复 / 报告 / 证据链路无需额外表。
ALTER TABLE interview_sessions
  ADD COLUMN difficulty_preference VARCHAR(16),
  ADD COLUMN candidate_version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN interview_sessions.difficulty_preference IS
    '本场难度偏好（P4Q-3c）：显式节奏指令当轮生效，仅本场，NULL 表示未调整';
COMMENT ON COLUMN interview_sessions.candidate_version IS
    '候选代次（P4-4b）：受限生成与后台预备据此判过期，后台结果不覆盖已推进会话';
