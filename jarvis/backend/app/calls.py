"""Call lifecycle: delayed 'Call Me', schedules, dialing, status tracking, safety limits."""
import secrets
import uuid
from datetime import timedelta

from .exotel import ExotelError
from .security import E164
from .timeutil import IST, iso, now_utc, parse

ACTIVE = ("scheduled", "dialing", "initiated", "in_progress")
TERMINAL = ("completed", "failed", "cancelled", "busy", "no_answer")
DELAYED_GRACE = 90          # a delayed call that is >90s overdue (server was down) is not placed
NO_CONNECT_AFTER = 120      # initiated but never connected after this long -> no answer
SCHEDULE_WINDOW_MIN = 30    # a schedule may fire up to 30 min late (e.g. after a restart)


class CallError(Exception):
    def __init__(self, code: str, message: str, http: int = 400, extra: dict | None = None):
        super().__init__(message)
        self.code, self.message, self.http, self.extra = code, message, http, extra or {}


class CallManager:
    def __init__(self, db, settings, exotel):
        self.db, self.s, self.exotel = db, settings, exotel
        self._last_schedule_check = 0.0

    # ---------- creating / cancelling ----------
    def create_delayed(self, now=None):
        now = now or now_utc()
        if not E164.match(self.s.approved_number or ""):
            raise CallError("not_configured", "APPROVED_NUMBER is not set to a valid E.164 number on the server.", 503)
        with self.db.tx():
            existing = self.db.one("SELECT * FROM calls WHERE kind='delayed' AND status IN ('scheduled','dialing')")
            if existing:
                return existing, True
            self._check_limits(now)
            cid = uuid.uuid4().hex
            when = now + timedelta(seconds=self.s.delayed_seconds)
            self.db.run(
                "INSERT INTO calls(id,kind,purpose,status,scheduled_for,created_at,stream_token) VALUES(?,?,?,?,?,?,?)",
                (cid, "delayed", "chat", "scheduled", iso(when), iso(now), secrets.token_urlsafe(32)))
        return self.get(cid), False

    def _check_limits(self, now):
        if self.db.one("SELECT id FROM calls WHERE status IN ('initiated','in_progress')"):
            raise CallError("busy", "A call is already in progress.", 409)
        last = self.db.one("SELECT MAX(initiated_at) AS t FROM calls")["t"]
        if last:
            wait = self.s.cooldown_seconds - (now - parse(last)).total_seconds()
            if wait > 0:
                raise CallError("cooldown", f"Cooldown active. Try again in {int(wait) + 1}s.", 429, {"retry_after": int(wait) + 1})
        n = self.db.one("SELECT COUNT(*) AS n FROM calls WHERE initiated_at >= ?", (iso(now - timedelta(hours=24)),))["n"]
        if n >= self.s.max_calls_per_day:
            raise CallError("daily_limit", f"Daily call limit ({self.s.max_calls_per_day}) reached.", 429)

    def cancel(self, call_id, now=None):
        now = now or now_utc()
        _, n = self.db.run("UPDATE calls SET status='cancelled', ended_at=? WHERE id=? AND status='scheduled'", (iso(now), call_id))
        c = self.get(call_id)
        if not c:
            raise CallError("not_found", "No such call.", 404)
        if n == 0 and c["status"] != "cancelled":
            raise CallError("too_late", f"Cannot cancel: call is already {c['status']}.", 409)
        return c

    def get(self, call_id):
        return self.db.one("SELECT * FROM calls WHERE id=?", (call_id,))

    def pending(self):
        return self.db.one("SELECT * FROM calls WHERE status IN ('scheduled','dialing','initiated','in_progress') "
                           "ORDER BY created_at DESC LIMIT 1")

    def latest(self):
        return self.db.one("SELECT * FROM calls ORDER BY created_at DESC LIMIT 1")

    def history(self, limit=50):
        return self.db.all("SELECT * FROM calls ORDER BY created_at DESC LIMIT ?", (limit,))

    # ---------- scheduler ----------
    def recover_on_startup(self, now=None):
        now = now or now_utc()
        self.db.run("UPDATE calls SET status='failed', ended_at=?, failure_reason=? WHERE status='dialing'",
                    (iso(now), "Server restarted while placing the call; not retried to avoid a duplicate call."))

    async def tick(self, now=None):
        now = now or now_utc()
        if now.timestamp() - self._last_schedule_check >= 20:
            self._last_schedule_check = now.timestamp()
            self.fire_due_schedules(now)
        for c in self.db.all("SELECT id FROM calls WHERE status='scheduled' AND scheduled_for<=? ORDER BY scheduled_for", (iso(now),)):
            _, n = self.db.run("UPDATE calls SET status='dialing' WHERE id=? AND status='scheduled'", (c["id"],))
            if n == 1:  # atomically claimed; exactly one dial attempt per call
                await self._dial(self.get(c["id"]), now)
        self.reconcile(now)

    def fire_due_schedules(self, now):
        ist = now.astimezone(IST)
        today = ist.strftime("%Y-%m-%d")
        for sc in self.db.all("SELECT * FROM schedules WHERE enabled=1"):
            due = self._schedule_due_at(sc, ist)
            if due is None or not (due <= ist < due + timedelta(minutes=SCHEDULE_WINDOW_MIN)):
                continue
            if sc["last_run_date"] == today and sc["frequency"] != "once":
                continue
            _, n = self.db.run("UPDATE schedules SET last_run_date=?, enabled=CASE WHEN frequency='once' THEN 0 ELSE enabled END "
                               "WHERE id=? AND (last_run_date IS NOT ? OR frequency='once')", (today, sc["id"], today))
            if n:
                self.db.run("INSERT INTO calls(id,kind,purpose,status,scheduled_for,created_at,stream_token,schedule_id,note) VALUES(?,?,?,?,?,?,?,?,?)",
                            (uuid.uuid4().hex, "scheduled", sc["purpose"], "scheduled", iso(now), iso(now),
                             secrets.token_urlsafe(32), sc["id"], sc["note"]))

    @staticmethod
    def _schedule_due_at(sc, ist):
        try:
            if sc["frequency"] == "once":
                from datetime import datetime
                return datetime.strptime(sc["time_hhmm"], "%Y-%m-%d %H:%M").replace(tzinfo=IST)
            hh, mm = map(int, sc["time_hhmm"].split(":"))
            if sc["frequency"] == "weekly" and ist.weekday() != sc["weekday"]:
                return None
            return ist.replace(hour=hh, minute=mm, second=0, microsecond=0)
        except (ValueError, TypeError):
            return None

    async def _dial(self, call, now):
        s = self.s
        if call["kind"] == "delayed" and (now - parse(call["scheduled_for"])).total_seconds() > DELAYED_GRACE:
            return self._fail(call["id"], "Missed: the server was offline at the scheduled time, so the call was not placed late.", now)
        if not s.calls_enabled:
            return self._fail(call["id"], "Calls are disabled on the server (CALLS_ENABLED=false). Finish setup and testing, then enable them.", now)
        missing = [n for n, ok in (("Exotel credentials/ExoPhone", s.exotel_configured), ("OpenAI API key", s.openai_configured),
                                   ("PUBLIC_BASE_URL", bool(s.public_base_url)), ("WEBHOOK_SECRET", bool(s.webhook_secret))) if not ok]
        if missing:
            return self._fail(call["id"], "Server is missing configuration: " + ", ".join(missing) + ".", now)
        if call["kind"] != "delayed":
            try:
                with self.db.tx():
                    self._check_limits(now)
            except CallError as e:
                return self._fail(call["id"], e.message, now)
        try:
            res = await self.exotel.connect(
                to=s.approved_number,  # the ONLY number this system will ever dial
                stream_url=f"{s.ws_base()}/ws/exotel/{call['stream_token']}?sample-rate=24000",
                time_limit=s.max_call_seconds + 15, custom_field=call["id"],
                status_callback=f"{s.public_base_url}/webhooks/exotel/{call['id']}?secret={s.webhook_secret}")
        except ExotelError as e:
            return self._fail(call["id"], e.reason, now)
        except Exception as e:  # never leave a call stuck in 'dialing'
            return self._fail(call["id"], f"Unexpected error placing call: {e.__class__.__name__}", now)
        self.db.run("UPDATE calls SET status='initiated', initiated_at=?, exotel_sid=? WHERE id=?", (iso(now), res["sid"], call["id"]))

    def _fail(self, call_id, reason, now, status="failed"):
        self.db.run("UPDATE calls SET status=?, failure_reason=?, ended_at=? WHERE id=? AND status IN ('scheduled','dialing','initiated','in_progress')",
                    (status, reason, iso(now), call_id))

    def reconcile(self, now):
        cutoff = iso(now - timedelta(seconds=NO_CONNECT_AFTER))
        for c in self.db.all("SELECT id FROM calls WHERE status='initiated' AND initiated_at<?", (cutoff,)):
            self._fail(c["id"], "No answer: the call did not connect within 2 minutes.", now, "no_answer")
        cutoff = iso(now - timedelta(seconds=60))
        for c in self.db.all("SELECT id FROM calls WHERE status='dialing' AND scheduled_for<? AND initiated_at IS NULL", (cutoff,)):
            self._fail(c["id"], "Placing the call timed out.", now)
        cutoff = iso(now - timedelta(seconds=self.s.max_call_seconds + 120))
        for c in self.db.all("SELECT id FROM calls WHERE status='in_progress' AND answered_at<?", (cutoff,)):
            self.finish(c["id"], now)

    # ---------- events from the media socket / Exotel webhook ----------
    def by_token(self, token):
        return self.db.one("SELECT * FROM calls WHERE stream_token=? AND status='initiated'", (token,))

    def answered(self, call_id, now=None):
        self.db.run("UPDATE calls SET status='in_progress', answered_at=COALESCE(answered_at,?) WHERE id=? AND status='initiated'",
                    (iso(now or now_utc()), call_id))

    def finish(self, call_id, now=None):
        now = now or now_utc()
        c = self.get(call_id)
        if not c or c["status"] not in ("initiated", "in_progress"):
            return
        dur = int((now - parse(c["answered_at"])).total_seconds()) if c["answered_at"] else None
        status = "completed" if c["answered_at"] else "no_answer"
        reason = None if c["answered_at"] else "The call ended without being answered."
        self.db.run("UPDATE calls SET status=?, ended_at=?, duration_seconds=?, failure_reason=? WHERE id=?", (status, iso(now), dur, reason, call_id))

    def apply_callback(self, call_id, fields, now=None):
        """Exotel StatusCallback. Field names are read tolerantly; verify against a real callback (see README)."""
        now = now or now_utc()
        st = str(fields.get("Status") or fields.get("CallStatus") or "").lower().replace("_", "-")
        if st in ("busy",):
            self._fail(call_id, "The number was busy.", now, "busy")
        elif st in ("no-answer", "noanswer"):
            self._fail(call_id, "The call was not answered.", now, "no_answer")
        elif st in ("failed", "canceled", "cancelled"):
            self._fail(call_id, f"Exotel reported the call as {st}.", now, "failed")
        elif st == "completed":
            self.finish(call_id, now)
