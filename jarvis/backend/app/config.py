import os
from dataclasses import dataclass


def _b(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).strip().lower() in ("1", "true", "yes", "on")


@dataclass
class Settings:
    jwt_secret: str
    admin_password_hash: str
    approved_number: str
    public_base_url: str
    webhook_secret: str
    calls_enabled: bool
    exotel_sid: str
    exotel_key: str
    exotel_token: str
    exotel_host: str
    exotel_caller_id: str
    openai_key: str
    openai_model: str
    openai_voice: str
    max_call_seconds: int
    cooldown_seconds: int
    max_calls_per_day: int
    delayed_seconds: int
    db_path: str

    @property
    def exotel_configured(self) -> bool:
        return all([self.exotel_sid, self.exotel_key, self.exotel_token, self.exotel_caller_id])

    @property
    def openai_configured(self) -> bool:
        return bool(self.openai_key)

    def ws_base(self) -> str:
        return self.public_base_url.replace("https://", "wss://").replace("http://", "ws://").rstrip("/")


def load() -> Settings:
    return Settings(
        jwt_secret=os.getenv("JWT_SECRET", ""),
        admin_password_hash=os.getenv("ADMIN_PASSWORD_HASH", ""),
        approved_number=os.getenv("APPROVED_NUMBER", "").strip(),
        public_base_url=os.getenv("PUBLIC_BASE_URL", "").rstrip("/"),
        webhook_secret=os.getenv("WEBHOOK_SECRET", ""),
        calls_enabled=_b("CALLS_ENABLED", False),
        exotel_sid=os.getenv("EXOTEL_ACCOUNT_SID", ""),
        exotel_key=os.getenv("EXOTEL_API_KEY", ""),
        exotel_token=os.getenv("EXOTEL_API_TOKEN", ""),
        exotel_host=os.getenv("EXOTEL_HOST", "api.in.exotel.com"),
        exotel_caller_id=os.getenv("EXOTEL_CALLER_ID", ""),
        openai_key=os.getenv("OPENAI_API_KEY", ""),
        openai_model=os.getenv("OPENAI_REALTIME_MODEL", "gpt-realtime"),
        openai_voice=os.getenv("OPENAI_VOICE", "cedar"),
        max_call_seconds=int(os.getenv("MAX_CALL_SECONDS", "600")),
        cooldown_seconds=int(os.getenv("CALL_COOLDOWN_SECONDS", "120")),
        max_calls_per_day=int(os.getenv("MAX_CALLS_PER_DAY", "10")),
        delayed_seconds=int(os.getenv("DELAYED_CALL_SECONDS", "180")),
        db_path=os.getenv("DB_PATH", "./data/jarvis.db"),
    )
