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
    # LLM 单次调用超时（秒）：防止模型侧卡住时 SSE 请求无限挂起（前端表现为"无响应"）
    llm_timeout_seconds: float = 120.0

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

    # JD 内容注入上限（P2-5 JD_TARGETED 定向优化，Token 纪律）
    jd_context_max_chars: int = 4000

    # 面试发起（P1-4）：Agent 推荐的默认题目数量（与前端创建面试默认一致）
    interview_default_question_count: int = 8

    # 新上传简历的异步分析就绪窗口：分析未完成时有限次轮询
    # 总等待 ≈ attempts × delay（默认约 15s），期间通过 tool_progress 事件向前端反馈；
    # 超时后返回「稍后获取分析结果」ChoiceBlock，用户可点击重试（避免请求内长时间干等）
    analysis_wait_attempts: int = 5
    analysis_wait_delay_seconds: float = 3.0


settings = Settings()
