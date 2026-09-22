-- P4Q-2：面试计划从「题数配额」改为「时长预算 + 必要覆盖」。
--
-- 背景：此前的规模来源是 questionCount（3-20 题），于是「约 20 分钟、实习和项目优先」
-- 这类真实意图被翻译成一个题数，回答质量好与差都问同样多的题；
-- 时间也只存在于前端的一个墙钟计时里——把模型等待都算成了用户答题时间。
--
-- 本次落四样东西：
--   planned_duration_minutes  预计时长（分钟）：规模与收束判据都由它推导
--   required_topics_json      必要覆盖（话题 key/label 列表）：哪些话题必须问到才算完成
--   consumed_seconds          用户答题耗时（秒）：只记「题目展示 → 本轮提交」的墙钟，
--                             扣除本轮模型评估耗时；模型/网络等待不扣用户预算
--   question_presented_at     当前题展示时刻：时间记账的起点
--
-- 覆盖状态本身**不落库**：候选池与实际轨迹（P4-1）已经足以还原「每个话题问没问、
-- 怎么答的」，再存一份快照只会和轨迹漂移。结束原因沿用 end_reason
-- （新增 COVERAGE_SATISFIED / BUDGET_EXHAUSTED 两种取值）。
ALTER TABLE interview_sessions
  ADD COLUMN planned_duration_minutes INTEGER,
  ADD COLUMN required_topics_json TEXT,
  ADD COLUMN consumed_seconds INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN question_presented_at TIMESTAMP(6);

-- 旧会话没有计划：保持 NULL，前端按「未记录」展示，不强行折算成新计划
COMMENT ON COLUMN interview_sessions.planned_duration_minutes IS
    '预计时长（分钟，P4Q-2）；NULL 表示旧会话未记录计划';
COMMENT ON COLUMN interview_sessions.required_topics_json IS
    '必要覆盖的话题列表（P4Q-2）；NULL 表示未记录（覆盖状态由候选池与实际轨迹推导）';
COMMENT ON COLUMN interview_sessions.consumed_seconds IS
    '用户答题累计耗时（秒，P4Q-2）：不含模型/网络等待与暂停';
COMMENT ON COLUMN interview_sessions.question_presented_at IS
    '当前题展示时刻（P4Q-2）：时间记账起点，提交时结算本轮耗时';
