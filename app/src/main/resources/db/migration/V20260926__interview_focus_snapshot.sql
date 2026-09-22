-- P5-2：把提案重点落库，让「画像重点 → 定向提案 → 面试 → 报告 → 证据 → 新画像」可审计。
--
-- 此前 focusCategories 只在**出题那一刻**被消费（裁剪分类），没有落库：
-- 一场面试结束后，没人说得清「为什么这场重点考了 JVM」——是画像低分、用户指定，
-- 还是模型随手挑的。必要覆盖（required_topics_json）只能部分回答，且它是「至少触及」，
-- 不等于「提案的重点」。
--
-- 本列存提案给出的重点分类（原始 key 列表），与 required_topics / planned_duration_minutes
-- 一起构成计划快照。推荐**依据文本**不落库：它是展示层措辞，由画像数据实时推导（P4-6b）。
ALTER TABLE interview_sessions
  ADD COLUMN focus_categories_json TEXT;

COMMENT ON COLUMN interview_sessions.focus_categories_json IS
    '提案重点分类 key 列表（P5-2）；NULL 表示未记录（旧会话或未走提案创建）';
