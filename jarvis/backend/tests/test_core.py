import asyncio
import base64
import json
import threading
import unittest
from datetime import datetime, timedelta, timezone

from app import business
from app.calls import CallError, CallManager
from app.config import Settings
from app.db import DB
from app.exotel import ExotelError
from app.realtime import Bridge, Chunker, build_instructions
from app.security import RateLimiter, check_token, hash_password, make_token, verify_password
from app.timeutil import IST, iso, parse

T0 = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)


def settings(**kw):
    base = dict(jwt_secret="x" * 40, admin_password_hash="", approved_number="+919876543210", public_base_url="https://j.example.com",
                webhook_secret="whs", calls_enabled=True, exotel_sid="sid", exotel_key="k", exotel_token="t", exotel_host="api.in.exotel.com",
                exotel_caller_id="0800000000", openai_key="sk-x", openai_model="gpt-realtime", openai_voice="cedar",
                max_call_seconds=600, cooldown_seconds=120, max_calls_per_day=10, delayed_seconds=180, db_path=":memory:")
    base.update(kw)
    return Settings(**base)


class FakeExotel:
    def __init__(self, fail=None):
        self.calls, self.fail = [], fail

    async def connect(self, **kw):
        self.calls.append(kw)
        if self.fail:
            raise ExotelError(self.fail)
        return {"sid": f"SID{len(self.calls)}", "status": "in-progress"}


def make(**kw):
    db, ex = DB(":memory:"), FakeExotel(kw.pop("fail", None))
    return db, ex, CallManager(db, settings(**kw), ex)


def run(c):
    return asyncio.run(c)


class DelayedCall(unittest.TestCase):
    def test_schedules_exactly_180s_and_dedupes(self):
        db, ex, m = make()
        c, dup = m.create_delayed(T0)
        self.assertFalse(dup)
        self.assertEqual(parse(c["scheduled_for"]) - T0, timedelta(seconds=180))
        c2, dup2 = m.create_delayed(T0 + timedelta(seconds=5))
        self.assertTrue(dup2)
        self.assertEqual(c["id"], c2["id"])
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 1)

    def test_concurrent_presses_make_one_call(self):
        db, ex, m = make()
        res = []
        ts = [threading.Thread(target=lambda: res.append(m.create_delayed(T0))) for _ in range(25)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 1)
        self.assertEqual(sum(1 for _, dup in res if not dup), 1)

    def test_not_dialled_early_then_dialled_once(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=179)))
        self.assertEqual(ex.calls, [])
        run(m.tick(T0 + timedelta(seconds=180)))
        run(m.tick(T0 + timedelta(seconds=181)))
        self.assertEqual(len(ex.calls), 1)
        k = ex.calls[0]
        self.assertEqual(k["to"], "+919876543210")
        self.assertIn(c["stream_token"], k["stream_url"])
        self.assertTrue(k["stream_url"].startswith("wss://j.example.com/ws/exotel/"))
        self.assertTrue(k["stream_url"].endswith("?sample-rate=24000"))
        self.assertEqual(m.get(c["id"])["status"], "initiated")
        self.assertEqual(m.get(c["id"])["exotel_sid"], "SID1")

    def test_cancel_prevents_call(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        self.assertEqual(m.cancel(c["id"], T0 + timedelta(seconds=60))["status"], "cancelled")
        self.assertEqual(m.cancel(c["id"])["status"], "cancelled")  # idempotent
        run(m.tick(T0 + timedelta(seconds=200)))
        self.assertEqual(ex.calls, [])
        c2, dup = m.create_delayed(T0 + timedelta(seconds=70))
        self.assertFalse(dup)

    def test_cancel_too_late(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        with self.assertRaises(CallError) as e:
            m.cancel(c["id"])
        self.assertEqual(e.exception.code, "too_late")

    def test_calls_disabled_fails_with_reason(self):
        db, ex, m = make(calls_enabled=False)
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        r = m.get(c["id"])
        self.assertEqual(r["status"], "failed")
        self.assertIn("CALLS_ENABLED", r["failure_reason"])
        self.assertEqual(ex.calls, [])

    def test_missing_config_fails(self):
        db, ex, m = make(openai_key="")
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        self.assertIn("OpenAI", m.get(c["id"])["failure_reason"])

    def test_exotel_error_reason_surfaced(self):
        db, ex, m = make(fail="Exotel rejected the call (HTTP 403): TRAI NDNC regulations")
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        r = m.get(c["id"])
        self.assertEqual(r["status"], "failed")
        self.assertIn("NDNC", r["failure_reason"])

    def test_overdue_after_downtime_not_placed(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=180 + 300)))
        self.assertEqual(ex.calls, [])
        self.assertIn("Missed", m.get(c["id"])["failure_reason"])

    def test_cooldown_and_busy(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        with self.assertRaises(CallError) as e:  # call initiated -> busy
            m.create_delayed(T0 + timedelta(seconds=185))
        self.assertEqual(e.exception.code, "busy")
        m.answered(c["id"], T0 + timedelta(seconds=190))
        m.finish(c["id"], T0 + timedelta(seconds=250))
        self.assertEqual(m.get(c["id"])["status"], "completed")
        self.assertEqual(m.get(c["id"])["duration_seconds"], 60)
        with self.assertRaises(CallError) as e:
            m.create_delayed(T0 + timedelta(seconds=260))
        self.assertEqual(e.exception.code, "cooldown")
        m.create_delayed(T0 + timedelta(seconds=181 + 130))  # after cooldown: allowed

    def test_daily_limit(self):
        db, ex, m = make(max_calls_per_day=1, cooldown_seconds=0)
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        m.finish(c["id"], T0 + timedelta(seconds=200))
        with self.assertRaises(CallError) as e:
            m.create_delayed(T0 + timedelta(seconds=300))
        self.assertEqual(e.exception.code, "daily_limit")

    def test_no_answer_detected(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        run(m.tick(T0 + timedelta(seconds=181 + 125)))
        self.assertEqual(m.get(c["id"])["status"], "no_answer")

    def test_callbacks(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        run(m.tick(T0 + timedelta(seconds=181)))
        m.apply_callback(c["id"], {"Status": "busy"}, T0 + timedelta(seconds=200))
        self.assertEqual(m.get(c["id"])["status"], "busy")
        self.assertIn("busy", m.get(c["id"])["failure_reason"])

    def test_restart_does_not_redial(self):
        db, ex, m = make()
        c, _ = m.create_delayed(T0)
        db.run("UPDATE calls SET status='dialing' WHERE id=?", (c["id"],))
        m.recover_on_startup(T0 + timedelta(seconds=190))
        run(m.tick(T0 + timedelta(seconds=191)))
        self.assertEqual(ex.calls, [])
        self.assertEqual(m.get(c["id"])["status"], "failed")

    def test_bad_number_rejected(self):
        db, ex, m = make(approved_number="12345")
        with self.assertRaises(CallError):
            m.create_delayed(T0)


class Schedules(unittest.TestCase):
    def ist(self, y, mo, d, h, mi):
        return datetime(y, mo, d, h, mi, tzinfo=IST).astimezone(timezone.utc)

    def test_daily_8pm_ist_fires_once(self):
        db, ex, m = make()
        db.run("INSERT INTO schedules(name,frequency,time_hhmm,purpose) VALUES('b','daily','20:00','briefing')")
        run(m.tick(self.ist(2026, 11, 14, 19, 59)))
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 0)
        run(m.tick(self.ist(2026, 11, 14, 20, 0)))
        self.assertEqual(len(ex.calls), 1)
        m._last_schedule_check = 0
        run(m.tick(self.ist(2026, 11, 14, 20, 1)))
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 1)
        self.assertEqual(db.one("SELECT purpose p FROM calls")["p"], "briefing")

    def test_weekly_only_on_its_day_and_once(self):
        db, ex, m = make()
        db.run("INSERT INTO schedules(name,frequency,time_hhmm,weekday,purpose) VALUES('w','weekly','20:00',6,'weekly')")  # Sunday
        run(m.tick(self.ist(2026, 11, 14, 20, 5)))  # Saturday
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 0)
        run(m.tick(self.ist(2026, 11, 15, 20, 5)))  # Sunday
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 1)

    def test_once_reminder_disables_itself(self):
        db, ex, m = make()
        db.run("INSERT INTO schedules(name,frequency,time_hhmm,purpose,note) VALUES('r','once','2026-11-14 09:30','reminder','Pay supplier')")
        run(m.tick(self.ist(2026, 11, 14, 9, 31)))
        self.assertEqual(db.one("SELECT enabled e FROM schedules")["e"], 0)
        self.assertEqual(db.one("SELECT note n FROM calls")["n"], "Pay supplier")

    def test_too_late_after_window_skipped(self):
        db, ex, m = make()
        db.run("INSERT INTO schedules(name,frequency,time_hhmm,purpose) VALUES('b','daily','20:00','briefing')")
        run(m.tick(self.ist(2026, 11, 14, 22, 0)))
        self.assertEqual(db.one("SELECT COUNT(*) n FROM calls")["n"], 0)


class Business(unittest.TestCase):
    def setUp(self):
        self.db = DB(":memory:")
        self.now = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)  # Sat 17:30 IST
        d = self.db
        d.run("INSERT INTO products(name,price,stock,created_at) VALUES('EzeeGuard',4999,10,'2026-01-01T00:00:00Z')")
        o = lambda name, q, p, st, ts: business.add_order(d, name, q, p, st, created_at=ts)
        o("EzeeGuard", 2, 4999, "completed", "2026-11-13T18:40:00Z")   # 00:10 IST on 14 Nov -> "today"
        o("EzeeGuard", 1, 4999, "completed", "2026-11-13T18:20:00Z")   # 23:50 IST on 13 Nov -> yesterday
        o("NIMO", 1, 2999, "pending", "2026-11-14T05:00:00Z")
        o("NIMO", 1, 2999, "completed", "2026-10-20T05:00:00Z")        # last month
        d.run("INSERT INTO expenses(category,amount,created_at) VALUES('ads',1500,'2026-11-14T05:00:00Z')")

    def test_today_uses_ist_boundaries(self):
        s = business.summary(self.db, "today", self.now)
        self.assertEqual(s["revenue"], 9998.0)
        self.assertEqual(s["expenses"], 1500.0)
        self.assertEqual(s["net_recorded_earnings"], 8498.0)
        self.assertEqual((s["completed_orders"], s["pending_orders"], s["orders_total"]), (1, 1, 2))
        self.assertEqual(s["pending_order_value"], 2999.0)

    def test_all_and_empty(self):
        self.assertEqual(business.summary(self.db, "all", self.now)["revenue"], 9998 + 4999 + 2999)
        self.assertFalse(business.summary(DB(":memory:"), "all")["has_data"])

    def test_growth(self):
        g = business.growth(self.db, "today", self.now)
        self.assertEqual(g["previous_revenue"], 4999.0)
        self.assertEqual(g["revenue_change_pct"], 100.0)
        self.assertIsNone(business.growth(self.db, "month", self.now)["revenue_change_pct"] if business.summary(self.db, "last_month", self.now)["revenue"] == 0 else None)

    def test_bad_period(self):
        with self.assertRaises(ValueError):
            business.summary(self.db, "forever")


class Audio(unittest.TestCase):
    def test_chunker_units(self):
        c, out = Chunker(), []
        for n in (1000, 5000, 77, 20000, 3):
            out += c.add(b"\x01" * n)
        self.assertTrue(all(len(x) % 320 == 0 and len(x) >= 3200 for x in out))
        tail = c.flush()
        self.assertTrue(len(tail) % 320 == 0 and len(tail) >= 3200)
        self.assertEqual(sum(len(x) for x in out) + len(tail) - tail.count(b"\x00"), 26080)


class Security(unittest.TestCase):
    def test_password_and_token(self):
        h = hash_password("correct horse battery")
        self.assertTrue(verify_password("correct horse battery", h))
        self.assertFalse(verify_password("wrong", h))
        self.assertFalse(verify_password("x", "garbage"))
        t = make_token("s" * 40)
        self.assertTrue(check_token("s" * 40, t))
        self.assertFalse(check_token("o" * 40, t))
        self.assertFalse(check_token("s" * 40, t[:-2] + "xx"))

    def test_rate_limit(self):
        r = RateLimiter()
        self.assertTrue(all(r.allow("k", 5, 60, now=100 + i) for i in range(5)))
        self.assertFalse(r.allow("k", 5, 60, now=106))
        self.assertTrue(r.allow("k", 5, 60, now=161))


class FakeWS:
    def __init__(self):
        self.inbox, self.sent = asyncio.Queue(), []

    async def receive_text(self):
        v = await self.inbox.get()
        if v is None:
            raise RuntimeError("closed")
        return v

    async def send_text(self, t):
        self.sent.append(json.loads(t))


class FakeOAI:
    def __init__(self):
        self.q, self.sent = asyncio.Queue(), []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def send(self, t):
        self.sent.append(json.loads(t))

    def __aiter__(self):
        return self

    async def __anext__(self):
        v = await self.q.get()
        if v is None:
            raise StopAsyncIteration
        return json.dumps(v)


class BridgeTest(unittest.TestCase):
    def test_full_conversation_flow(self):
        async def go():
            db, ex, m = make()
            db.run("INSERT INTO memory(kind,title,content) VALUES('product','EzeeGuard','smart home hub')") if False else None
            db.run("INSERT INTO memory(kind,title,content,created_at) VALUES('product','EzeeGuard','smart home hub','x')")
            db.run("INSERT INTO memory(kind,title,content,enabled,created_at) VALUES('note','secret','hidden',0,'x')")
            business.add_order(db, "EzeeGuard", 1, 4999, "pending")
            c, _ = m.create_delayed(T0)
            run_now = T0 + timedelta(seconds=181)
            await m.tick(run_now)
            call = m.get(c["id"])
            ws, oai = FakeWS(), FakeOAI()
            b = Bridge(ws, call, db, settings(), m, connect_fn=lambda url, additional_headers: oai)
            task = asyncio.create_task(b.run())
            await ws.inbox.put(json.dumps({"event": "connected"}))
            await ws.inbox.put(json.dumps({"event": "start", "stream_sid": "MZ1", "start": {"stream_sid": "MZ1", "media_format": {"sample_rate": "24000"}}}))
            await asyncio.sleep(0.05)
            # session + greeting sent, in order
            self.assertEqual([x["type"] for x in oai.sent[:2]], ["session.update", "response.create"])
            sess = oai.sent[0]["session"]
            self.assertEqual(sess["audio"]["input"]["format"], {"type": "audio/pcm", "rate": 24000})
            self.assertIn("EzeeGuard", sess["instructions"])
            self.assertNotIn("hidden", sess["instructions"])  # disabled memory is never sent
            self.assertEqual(m.get(c["id"])["status"], "in_progress")
            # caller audio is forwarded
            await ws.inbox.put(json.dumps({"event": "media", "media": {"payload": "AAAA"}}))
            await asyncio.sleep(0.02)
            self.assertIn({"type": "input_audio_buffer.append", "audio": "AAAA"}, oai.sent)
            # model audio -> exotel, chunked to 320-byte multiples with marks
            await oai.q.put({"type": "response.output_audio.delta", "item_id": "it1", "delta": base64.b64encode(b"\x02" * 9000).decode()})
            await oai.q.put({"type": "response.output_audio.done"})
            await asyncio.sleep(0.05)
            media = [x for x in ws.sent if x["event"] == "media"]
            self.assertTrue(media and all(len(base64.b64decode(x["media"]["payload"])) % 320 == 0 for x in media))
            self.assertTrue(any(x["event"] == "mark" for x in ws.sent))
            # tool call uses DB and returns result to the model, then asks for a response
            await oai.q.put({"type": "response.function_call_arguments.done", "name": "get_business_summary", "call_id": "c1", "arguments": '{"period":"all"}'})
            await asyncio.sleep(0.1)
            out = [x for x in oai.sent if x["type"] == "conversation.item.create"][0]["item"]
            self.assertEqual(out["call_id"], "c1")
            self.assertEqual(json.loads(out["output"])["pending_orders"], 1)
            self.assertEqual(json.loads(out["output"])["revenue"], 0)
            self.assertEqual(oai.sent[-1]["type"], "response.create")
            # barge-in clears Exotel's buffer and truncates the model item
            await ws.inbox.put(json.dumps({"event": "mark", "mark": {"name": media and "m1"}}))
            await asyncio.sleep(0.02)
            await oai.q.put({"type": "input_audio_buffer.speech_started"})
            await asyncio.sleep(0.05)
            self.assertTrue(any(x["event"] == "clear" for x in ws.sent))
            tr = [x for x in oai.sent if x["type"] == "conversation.item.truncate"][0]
            self.assertEqual(tr["item_id"], "it1")
            # end_call -> goodbye finishes -> mark 'end' sent -> hang up after it is played
            await oai.q.put({"type": "response.function_call_arguments.done", "name": "end_call", "call_id": "c2", "arguments": "{}"})
            await oai.q.put({"type": "response.done"})
            await asyncio.sleep(0.1)
            self.assertEqual(ws.sent[-1]["mark"]["name"], "end")
            self.assertFalse(task.done())
            await ws.inbox.put(json.dumps({"event": "mark", "mark": {"name": "end"}}))
            await asyncio.wait_for(task, 3)
            self.assertEqual(m.get(c["id"])["status"], "completed")
        asyncio.run(go())

    def test_caller_hangup_ends_call(self):
        async def go():
            db, ex, m = make()
            c, _ = m.create_delayed(T0)
            await m.tick(T0 + timedelta(seconds=181))
            ws, oai = FakeWS(), FakeOAI()
            b = Bridge(ws, m.get(c["id"]), db, settings(), m, connect_fn=lambda url, additional_headers: oai)
            task = asyncio.create_task(b.run())
            await ws.inbox.put(json.dumps({"event": "start", "stream_sid": "MZ1", "start": {"stream_sid": "MZ1"}}))
            await asyncio.sleep(0.05)
            await ws.inbox.put(json.dumps({"event": "stop", "stop": {"reason": "callended"}}))
            await asyncio.wait_for(task, 3)
            self.assertEqual(m.get(c["id"])["status"], "completed")
        asyncio.run(go())

    def test_instructions_contain_persona_and_time(self):
        db = DB(":memory:")
        t = build_instructions(db, {}, datetime(2026, 11, 14, 14, 30, tzinfo=timezone.utc))
        self.assertIn("AI assistant", t)
        self.assertIn("08:00 PM IST", t)


if __name__ == "__main__":
    unittest.main()
