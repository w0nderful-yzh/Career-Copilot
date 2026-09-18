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

    # LLM 调用预算（ARCH-2）：实时调用要人等（面试逐轮），后台调用可以慢（报告、优化提案、摘要）
    llm_timeout_realtime_seconds: float = 20.0
    llm_timeout_background_seconds: float = 120.0
    # 结构化输出的有限修复重试次数：只针对「解析失败」，网络/超时类错误不重试
    # （重试超时只会把用户的等待翻倍）
    llm_parse_retries: int = 1
    # 剩余预算低于该值就不再发起新尝试（ARCH-2b）：否则最后一次尝试会把等待
    # 拖成「预算 + 单次调用耗时」，等于没设预算
    llm_min_attempt_ms: int = 1500
    # 输入总长上限（字符）：业务侧已按各自上限裁剪上下文，这里是「调用方忘了裁」的兜底
    llm_max_input_chars: int = 16000
    # 结构化输出 token 上限（ARCH-2b）：结构化结果本来就短，封顶挡的是模型开始写小作文；
    # 自由文本与流式不设限，长度由各自提示词与上下文上限控制
    llm_max_output_tokens: int = 1024

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

    # 简历优化自评审轮次（P2 待修正，配置化，默认关闭）。
    # 评估结论：暂无质量证据支撑开启——每轮追加 1 次 LLM 调用与数秒等待，而真实性
    # 已由确定性校验器（数字/技术栈/公司/项目事实）兜底，不依赖 LLM review。
    # 0 = 关闭（一期默认最小）；>0 时在提案落库前做至多 N 轮「只淘汰不新增」的评审。
    resume_self_review_rounds: int = 0

    # 面试发起（P1-4）：Agent 推荐的默认题目数量（与前端创建面试默认一致）
    interview_default_question_count: int = 8

    # 新上传简历的异步分析就绪窗口：分析未完成时有限次轮询
    # 总等待 ≈ attempts × delay（默认约 15s），期间通过 tool_progress 事件向前端反馈；
    # 超时后返回「稍后获取分析结果」ChoiceBlock，用户可点击重试（避免请求内长时间干等）
    analysis_wait_attempts: int = 5
    analysis_wait_delay_seconds: float = 3.0


settings = Settings()
