-- Conversation Memory：会话绑定活动简历（Agent 跨轮恢复上下文）
-- active_job_id 待 JD 附件功能（P2-1）落地时一并加入
ALTER TABLE agent_conversations ADD COLUMN active_resume_id BIGINT;