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

    llm_base_url: str = "https://api.example.com/v1"
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = "gpt-4o-mini"
    llm_intent_model: str = "gpt-4o-mini"

    agent_service_host: str = "0.0.0.0"
    agent_service_port: int = 8000


settings = Settings()
