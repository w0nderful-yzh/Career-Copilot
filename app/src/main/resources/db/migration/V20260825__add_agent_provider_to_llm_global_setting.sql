-- Agent 模型默认 Provider：Python Agent Service 使用的模型配置
ALTER TABLE llm_global_setting ADD COLUMN default_agent_provider_id VARCHAR(64);