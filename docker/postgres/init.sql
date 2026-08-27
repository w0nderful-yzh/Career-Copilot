CREATE EXTENSION IF NOT EXISTS vector;

-- LangGraph Checkpoint 独立库（Python Agent 工作状态持久化，避免与业务表混用）
CREATE DATABASE agent_checkpoint;
