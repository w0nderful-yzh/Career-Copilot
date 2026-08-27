"""Application configuration.

Configuration is centralized here instead of scattering os.getenv calls
across services / tools.
"""

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    backend_base_url: str = "http://localhost:8080"
    backend_timeout: float = 30.0

    llm_base_url: str = "https://api.example.com/v1"
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = "gpt-4o-mini"
    llm_intent_model: str = "gpt-4o-mini"

    agent_service_host: str = "0.0.0.0"
    agent_service_port: int = 8000

    # LangGraph Checkpoint：Agent 工作状态持久化（跨轮次恢复/HITL）
    # 使用独立数据库（agent_checkpoint），避免与 Java 业务库混用；
    # 默认匹配 docker-compose.dev.yml 的本地开发凭据
    checkpoint_database_url: str = "postgresql://postgres:123456@localhost:5432/agent_checkpoint"
    checkpoint_schema: str = "agent_checkpoint"

    # 短期记忆（会话历史注入）参数
    history_max_messages: int = 8  # 注入的最近消息条数
    history_max_message_chars: int = 500  # 单条消息注入上限
    summary_trigger_messages: int = 12  # 历史超过该条数时触发滚动摘要

    # 简历内容注入上限（Agent 内容级分析 / 简历优化，Token 纪律）
    resume_context_max_chars: int = 8000


settings = Settings()
