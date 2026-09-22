-- 面试会话增加「是否自适应」标记（P4-3）
-- 自适应会话在答题时做逐题轻量评估并由决策引擎选择下一题（含已问主问题的追问）；
-- 普通/知识库面试保持原固定题单顺序行为。历史数据回填 false（非自适应）。
ALTER TABLE interview_sessions
    ADD COLUMN adaptive BOOLEAN NOT NULL DEFAULT FALSE;
