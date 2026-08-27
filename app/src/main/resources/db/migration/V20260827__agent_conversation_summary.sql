-- Copilot 对话：会话级滚动摘要（Python Agent 短期记忆写回，checkpoint 重启后仍可恢复）
ALTER TABLE agent_conversations ADD COLUMN summary TEXT;