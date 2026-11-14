"""All timestamps are stored as UTC 'YYYY-MM-DDTHH:MM:SSZ'. Business logic uses IST."""
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30), "IST")
FMT = "%Y-%m-%dT%H:%M:%SZ"


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime(FMT)


def parse(s: str) -> datetime:
    return datetime.strptime(s, FMT).replace(tzinfo=timezone.utc)


def epoch_ms(s: str | None) -> int | None:
    return int(parse(s).timestamp() * 1000) if s else None


def period_bounds(period: str, now: datetime | None = None):
    """Return (start_iso, end_iso) in UTC for an IST-based period, or (None, None) for all."""
    now = (now or now_utc()).astimezone(IST)
    day0 = now.replace(hour=0, minute=0, second=0, microsecond=0)
    if period == "all":
        return None, None
    if period == "today":
        return iso(day0), iso(day0 + timedelta(days=1))
    if period == "yesterday":
        return iso(day0 - timedelta(days=1)), iso(day0)
    if period == "week":
        start = day0 - timedelta(days=day0.weekday())
        return iso(start), iso(start + timedelta(days=7))
    if period == "last_week":
        start = day0 - timedelta(days=day0.weekday())
        return iso(start - timedelta(days=7)), iso(start)
    if period == "month":
        start = day0.replace(day=1)
        nxt = (start + timedelta(days=32)).replace(day=1)
        return iso(start), iso(nxt)
    if period == "last_month":
        this = day0.replace(day=1)
        prev = (this - timedelta(days=1)).replace(day=1)
        return iso(prev), iso(this)
    raise ValueError(f"unknown period {period}")


PREVIOUS = {"today": "yesterday", "week": "last_week", "month": "last_month"}
