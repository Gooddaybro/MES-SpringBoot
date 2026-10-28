from pathlib import Path

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

AGENT_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = AGENT_ROOT.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=REPO_ROOT / ".local" / "agent.env",
        env_file_encoding="utf-8",
        env_prefix="AGENT_",
        extra="ignore",
        populate_by_name=True,
    )
    shared_secret: SecretStr = Field(
        default=SecretStr(""), validation_alias="MES_AGENT_SHARED_SECRET"
    )
    host: str = "127.0.0.1"
    port: int = Field(default=8001, ge=1024, le=65535)
    request_timeout_seconds: float = Field(default=30, ge=1, le=60)
    llm_base_url: str = "https://api.deepseek.com"
    llm_model: str = "deepseek-flash"
    llm_api_key: SecretStr = Field(default=SecretStr(""), validation_alias="DEEPSEEK_API_KEY")
    llm_timeout_seconds: float = Field(default=15, ge=1, le=30)
    jina_api_key: SecretStr = Field(default=SecretStr(""), validation_alias="JINA_API_KEY")
    embedding_base_url: str = "https://api.jina.ai/v1/embeddings"
    embedding_model: str = "jina-embeddings-v3"
    embedding_dimensions: int = Field(default=512, ge=32, le=2048)
    embedding_timeout_seconds: float = Field(default=20, ge=1, le=60)
    embedding_batch_size: int = Field(default=24, ge=1, le=64)
    data_dir: Path = AGENT_ROOT / "data"
    pdf_dir: Path = REPO_ROOT.parent / "pdf文档"
    manifest_path: Path = AGENT_ROOT / "documents" / "manifest.json"
    top_k: int = Field(default=5, ge=1, le=8)
    min_similarity: float = Field(default=0.38, ge=0.0, le=1.0)
    max_context_characters: int = Field(default=6000, ge=1000, le=12000)

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key.get_secret_value().strip())
