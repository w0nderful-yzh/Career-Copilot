-- P4-1：题目稳定标识，以及「候选素材」与「实际轮次」的分离。
--
-- 背景：此前 questions_json 的一维数组既当候选素材又当实际轨迹（作答与状态被原地写回同一份数组），
-- 身份就是数组下标。后果有三个：合并两批题单必须整体重排索引；「候选择问」与「真实轮次」
-- 只能靠「有没有 answerState」区分；结束条件退化成「下标 >= 数组长度」，候选耗尽被当成考察完成。
--
-- 本次给实际轮次补三样东西（候选素材侧的标识在 JSON 里，不需要 DDL）：
--   question_id     题目稳定标识：候选池内唯一，排序与合并都不改变它
--   turn_ordinal    真实发生顺序（1 起）。报告补写的「未考察」行保持为空——
--                   未考察不属于面试轨迹，这也正是「未问候选不进入实际轨迹」的落点
--   decided_action  本轮最终决定（跟进追问 / 转下一主问题 / 候选耗尽结束 / 用户结束）
--
-- 旧数据兼容：历史行的 question_id 按 `legacy-<question_index>` 回填；读取端对没有标识的旧题目
-- 用同一规则派生标识，因此旧会话不必重写 questions_json 就能继续与回放
-- （代价：旧会话的顺序一旦变化标识不再稳定——只影响兼容期的历史场次，新会话用随机标识）。
ALTER TABLE interview_answers
  ADD COLUMN question_id VARCHAR(64),
  ADD COLUMN turn_ordinal INTEGER,
  ADD COLUMN decided_action VARCHAR(32);

UPDATE interview_answers
   SET question_id = 'legacy-' || question_index
 WHERE question_id IS NULL AND question_index IS NOT NULL;

-- 真实发生顺序：历史行都来自逐轮推进（都是真实轮次），按题号给出稳定序号
UPDATE interview_answers a
   SET turn_ordinal = ordered.rn
  FROM (
    SELECT id, row_number() OVER (PARTITION BY session_id ORDER BY question_index) AS rn
      FROM interview_answers
  ) ordered
 WHERE a.id = ordered.id;

-- 身份从「数组下标」迁到「题目标识」：唯一键随之切换
ALTER TABLE interview_answers
  DROP CONSTRAINT uk_interview_answer_session_question;
ALTER TABLE interview_answers
  ADD CONSTRAINT uk_interview_answer_session_question_id UNIQUE (session_id, question_id);

-- 轨迹按真实发生顺序读取
CREATE INDEX idx_interview_answer_session_ordinal
  ON interview_answers (session_id, turn_ordinal);

-- 结束原因（P4-1）：候选耗尽与用户主动结束必须能分开表达，也为覆盖 / 预算原因留出口
-- 当前题标识（P4-1）：会话推进的闸门与「当前题」定位都改用它，数组下标退化为展示顺序
ALTER TABLE interview_sessions
  ADD COLUMN end_reason VARCHAR(32),
  ADD COLUMN current_question_id VARCHAR(64);

-- 旧会话：当前题标识同样按 `legacy-<index>` 派生，与题目、轮次两侧的规则一致
UPDATE interview_sessions
   SET current_question_id = 'legacy-' || current_question_index
 WHERE current_question_id IS NULL AND current_question_index IS NOT NULL;

COMMENT ON COLUMN interview_sessions.end_reason IS
    '结束原因：CANDIDATES_EXHAUSTED / USER_FINISHED（覆盖与预算原因由 P4Q-2 扩展）';
COMMENT ON COLUMN interview_sessions.current_question_id IS
    '当前待答题的稳定标识（P4-1）：推进闸门与当前题定位都基于它，不再基于数组下标';
COMMENT ON COLUMN interview_answers.turn_ordinal IS
    '真实发生顺序（1 起）；为空表示报告补写的未考察项，不属于面试轨迹（P4-1）';
COMMENT ON COLUMN interview_answers.decided_action IS
    '本轮最终决定：FOLLOW_UP / NEXT_MAIN / FINISH_EXHAUSTED / FINISH_USER（P4-1）';
