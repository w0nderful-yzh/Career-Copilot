-- P4-1 历史回填修正。
--
-- V20260920 首次引入实际轮次时，把所有历史答案行都编入 turn_ordinal。但旧报告链路曾为
-- 未考察候选补写空答案行（answer_state = UNANSWERED），这些行不能被当成「真的问过」。
-- 历史数据没有足够事实区分「真实空提交」与「报告补写」，因此按保守口径排除旧标识下的
-- UNANSWERED，避免把未问候选伪造成轨迹。新链路的真实空提交使用稳定 q... 标识，不受影响。
UPDATE interview_answers
   SET turn_ordinal = NULL
 WHERE answer_state = 'UNANSWERED'
   AND question_id LIKE 'legacy-%';

-- P4-1 后每条答案事实都必须有题目标识；旧行已经由 V20260920 完成回填。
ALTER TABLE interview_answers
  ALTER COLUMN question_id SET NOT NULL;
