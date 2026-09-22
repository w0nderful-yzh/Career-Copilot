-- P4-5：保存最终报告的完整、版本化快照。
--
-- 旧字段 overall_score / strengths_json / improvements_json 等继续保留，供历史列表和导出兼容；
-- report_json 是新报告接口的权威快照，包含实际轮次、覆盖状态、聚合规则版本及决策依据。
ALTER TABLE interview_sessions
  ADD COLUMN report_json TEXT;

COMMENT ON COLUMN interview_sessions.report_json IS
    'P4-5 最终报告完整快照：规则版本、实际轮次、覆盖状态、聚合输入与结果；NULL 表示旧报告未保存快照';
