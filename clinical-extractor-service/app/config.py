import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-4o")
    embedding_model: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    qdrant_url: str = os.getenv("QDRANT_URL", "http://localhost:6333")
    qdrant_collection: str = os.getenv("QDRANT_COLLECTION", "clinical_reference")
    mysql_host: str = os.getenv("MYSQL_HOST", "localhost")
    mysql_port: int = int(os.getenv("MYSQL_PORT", "3306"))
    mysql_database: str = os.getenv("MYSQL_DATABASE", "chatappdb")
    mysql_user: str = os.getenv("MYSQL_USER", "root")
    mysql_password: str = os.getenv("MYSQL_PASSWORD", "root")
    resources_dir: Path = Path(os.getenv("RESOURCES_DIR", "/app/resources"))


settings = Settings()
