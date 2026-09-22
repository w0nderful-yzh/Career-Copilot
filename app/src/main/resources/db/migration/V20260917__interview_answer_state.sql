-- 答案状态（P4Q-5）：把「跳过 / 未作答 / 明确不会」与「真实作答」分开。
--
-- 背景：此前没有跳过动作也没有状态字段，用户用自然语言说「跳过」会被当成技术答案
-- 由报告打 0 分，再以 0 分进入画像证据（dev 库里真实发生过：Redis=0、项目经历=0）。
-- 报告无法表达「这题没答」，「未作答」与「答错」在数据上完全一样。
--
-- 四种状态的区别：
--   ANSWERED   真实作答（含「答案 + 换话题指令」的混合消息，有效答案部分保留）
--   SKIPPED    用户主动跳过（按钮或明确的跳过指令）
--   DECLINED   明确表示不会/不记得（作为诊断信息保留，不作为技术评分）
--   UNANSWERED 已提问但没有任何作答内容
-- 未考察不落库——没有对应答案行即为未考察。
--
-- 计分与画像口径：只有 ANSWERED 参与报告评分与画像证据；跳过/未作答不产生评分证据，
-- 「明确不会」只作为诊断保留。这正是「报告不把跳过显示成 0 分」的数据基础。
ALTER TABLE interview_answers
  ADD COLUMN answer_state VARCHAR(16) NOT NULL DEFAULT 'ANSWERED';

COMMENT ON COLUMN interview_answers.answer_state IS
  'ANSWERED/SKIPPED/DECLINED/UNANSWERED；仅 ANSWERED 参与评分与画像证据（P4Q-5）';

-- 历史数据回填：空答案即「已提问但没有作答内容」
UPDATE interview_answers
   SET answer_state = 'UNANSWERED'
 WHERE user_answer IS NULL OR btrim(user_answer) = '';

ALTER TABLE interview_answers
  ADD CONSTRAINT interview_answers_state_check
  CHECK (answer_state IN ('ANSWERED', 'SKIPPED', 'DECLINED', 'UNANSWERED'));

-- 报告与画像按状态筛选答案，加一个联合索引
CREATE INDEX idx_interview_answer_session_state
  ON interview_answers (session_id, answer_state);
