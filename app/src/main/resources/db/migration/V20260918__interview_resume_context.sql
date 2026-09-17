-- P4Q-1：面试的简历上下文快照。
--
-- 出题实际依据的来源 / 版本 / 文本，随会话持久化，用于报告与复盘追溯「这场面试基于哪份简历的哪个版本」。
-- 此前简历来源完全取决于调用方传了什么（Agent 侧恒传 null，导致简历题分支从未生效），
-- 落快照后历史场次也能解释「为什么问的是这些内容」。
ALTER TABLE interview_sessions
    ADD COLUMN resume_source VARCHAR(24),
    ADD COLUMN resume_version INTEGER,
    ADD COLUMN resume_context_text TEXT;
