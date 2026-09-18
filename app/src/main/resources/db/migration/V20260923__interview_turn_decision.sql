-- P4Q-3b：持久化 Java 最终接纳的逐轮决定，而不是只保留模型建议。
--
-- decided_action 已记录 FOLLOW_UP / NEXT_MAIN / FINISH_*；本次补齐：
--   decided_next_question_id  实际选择的下一题稳定标识，收束时为空
--   decision_reason           Java 校验后的最终依据
--   transition_message        实际展示的简短承接语（建议被否决时不保存）
ALTER TABLE interview_answers
  ADD COLUMN decided_next_question_id VARCHAR(64),
  ADD COLUMN decision_reason VARCHAR(200),
  ADD COLUMN transition_message VARCHAR(120);

COMMENT ON COLUMN interview_answers.decided_next_question_id IS
    'P4Q-3b Java 最终选择的下一题稳定标识；收束时为空';
COMMENT ON COLUMN interview_answers.decision_reason IS
    'P4Q-3b Java 最终决定依据；不保存被硬边界否决的模型建议';
COMMENT ON COLUMN interview_answers.transition_message IS
    'P4Q-3b 实际展示的承接语；只在模型建议被原样接纳时保存';
