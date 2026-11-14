import asyncio
import hmac
import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request, WebSocket
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import business, config
from .calls import CallError, CallManager
from .db import DB
from .exotel import ExotelClient
from .realtime import Bridge
from .security import E164, RateLimiter, check_token, make_token, mask, verify_password
from .timeutil import IST, epoch_ms, iso, now_utc

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
log = logging.getLogger("jarvis")

S = config.load()
if len(S.jwt_secret) < 32 or not S.admin_password_hash:
    raise RuntimeError("Set JWT_SECRET (>=32 chars) and ADMIN_PASSWORD_HASH (python -m app.make_secrets). Refusing to start.")
db = DB(S.db_path)
mgr = CallManager(db, S, ExotelClient(S))
limiter = RateLimiter()


def seed():
    if not db.one("SELECT id FROM schedules LIMIT 1"):  # seeded DISABLED: enable in the app after testing
        db.run("INSERT INTO schedules(name,frequency,time_hhmm,weekday,purpose,enabled) VALUES('Daily business briefing','daily','20:00',NULL,'briefing',0)")
        db.run("INSERT INTO schedules(name,frequency,time_hhmm,weekday,purpose,enabled) VALUES('Weekly earnings report','weekly','20:00',6,'weekly',0)")


async def scheduler_loop():
    while True:
        try:
            await mgr.tick()
        except Exception:
            log.exception("scheduler tick failed")
        await asyncio.sleep(1)


@asynccontextmanager
async def lifespan(app):
    seed()
    mgr.recover_on_startup()
    task = asyncio.create_task(scheduler_loop())
    yield
    task.cancel()


app = FastAPI(title="JARVIS", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(CallError)
async def call_error(_, e: CallError):
    return JSONResponse({"error": e.code, "message": e.message, **e.extra}, status_code=e.http)


def ip(req: Request) -> str:
    return req.client.host if req.client else "?"


def auth(req: Request):
    if not limiter.allow("api:" + ip(req), 120, 60):
        raise HTTPException(429, "Too many requests")
    h = req.headers.get("authorization", "")
    if not (h.startswith("Bearer ") and check_token(S.jwt_secret, h[7:])):
        raise HTTPException(401, "Unauthorized")


def call_out(c):
    if not c:
        return None
    d = {k: c[k] for k in ("id", "kind", "purpose", "status", "scheduled_for", "created_at", "initiated_at", "answered_at",
                           "ended_at", "duration_seconds", "failure_reason")}
    d["scheduled_for_ms"], d["server_now_ms"] = epoch_ms(c["scheduled_for"]), int(now_utc().timestamp() * 1000)
    return d


@app.get("/health")
def health():
    return {"ok": True}


class Login(BaseModel):
    password: str = Field(max_length=200)


@app.post("/api/auth/login")
async def login(body: Login, req: Request):
    if not limiter.allow("login:" + ip(req), 5, 60):
        raise HTTPException(429, "Too many attempts. Wait a minute.")
    if not verify_password(body.password, S.admin_password_hash):
        await asyncio.sleep(0.7)
        raise HTTPException(401, "Wrong password")
    return {"token": make_token(S.jwt_secret)}


@app.get("/api/status", dependencies=[Depends(auth)])
def status():
    return {"server_now_ms": int(now_utc().timestamp() * 1000), "ist": now_utc().astimezone(IST).strftime("%d %b %Y %H:%M IST"),
            "calls_enabled": S.calls_enabled, "exotel_configured": S.exotel_configured, "openai_configured": S.openai_configured,
            "public_url_set": bool(S.public_base_url), "approved_number": mask(S.approved_number),
            "number_valid": bool(E164.match(S.approved_number or "")), "max_call_seconds": S.max_call_seconds,
            "pending_call": call_out(mgr.pending()), "last_call": call_out(mgr.latest())}


# ---------- calls ----------
@app.post("/api/calls/call-me", dependencies=[Depends(auth)])
def call_me(req: Request):
    if not limiter.allow("callme:" + ip(req), 6, 60):
        raise HTTPException(429, "Too many requests")
    c, dup = mgr.create_delayed()
    return {"call": call_out(c), "duplicate": dup}


@app.post("/api/calls/{call_id}/cancel", dependencies=[Depends(auth)])
def cancel(call_id: str):
    return {"call": call_out(mgr.cancel(call_id))}


@app.get("/api/calls/pending", dependencies=[Depends(auth)])
def pending():
    return {"call": call_out(mgr.pending())}


@app.get("/api/calls/{call_id}", dependencies=[Depends(auth)])
def get_call(call_id: str):
    c = mgr.get(call_id)
    if not c:
        raise HTTPException(404, "No such call")
    return {"call": call_out(c)}


@app.get("/api/calls", dependencies=[Depends(auth)])
def history():
    return {"calls": [call_out(c) for c in mgr.history()]}


@app.post("/webhooks/exotel/{call_id}")
async def exotel_webhook(call_id: str, req: Request):
    if not (S.webhook_secret and hmac.compare_digest(req.query_params.get("secret", ""), S.webhook_secret)):
        raise HTTPException(403, "forbidden")
    try:
        fields = dict(await req.form())
    except Exception:
        try:
            fields = await req.json()
        except Exception:
            fields = {}
    log.info("exotel callback %s: %s", call_id, {k: v for k, v in fields.items() if k.lower() not in ("from", "to")})
    mgr.apply_callback(call_id, fields)
    return {"ok": True}


@app.websocket("/ws/exotel/{token}")
async def exotel_ws(ws: WebSocket, token: str):
    call = mgr.by_token(token)
    if not call:
        await ws.close(code=1008)
        return
    await ws.accept()
    await Bridge(ws, call, db, S, mgr).run()
    try:
        await ws.close()
    except Exception:
        pass


# ---------- schedules ----------
class ScheduleIn(BaseModel):
    name: str = Field(max_length=80)
    frequency: str
    time: str
    weekday: int | None = Field(default=None, ge=0, le=6)
    purpose: str = "briefing"
    note: str | None = Field(default=None, max_length=500)
    enabled: bool = True


def _valid_schedule(b: ScheduleIn):
    from datetime import datetime
    if b.frequency not in ("daily", "weekly", "once") or b.purpose not in ("briefing", "weekly", "reminder", "chat"):
        raise HTTPException(422, "bad frequency or purpose")
    try:
        datetime.strptime(b.time, "%Y-%m-%d %H:%M" if b.frequency == "once" else "%H:%M")
    except ValueError:
        raise HTTPException(422, "time must be HH:MM (or 'YYYY-MM-DD HH:MM' for once), in IST")
    if b.frequency == "weekly" and b.weekday is None:
        raise HTTPException(422, "weekday required for weekly (0=Mon)")
    if b.purpose == "reminder" and not b.note:
        raise HTTPException(422, "reminder needs a note")


@app.get("/api/schedules", dependencies=[Depends(auth)])
def schedules():
    return {"schedules": db.all("SELECT * FROM schedules ORDER BY id")}


@app.post("/api/schedules", dependencies=[Depends(auth)])
def add_schedule(b: ScheduleIn):
    _valid_schedule(b)
    i, _ = db.run("INSERT INTO schedules(name,frequency,time_hhmm,weekday,purpose,note,enabled) VALUES(?,?,?,?,?,?,?)",
                  (b.name, b.frequency, b.time, b.weekday, b.purpose, b.note, int(b.enabled)))
    return db.one("SELECT * FROM schedules WHERE id=?", (i,))


@app.put("/api/schedules/{sid}", dependencies=[Depends(auth)])
def put_schedule(sid: int, b: ScheduleIn):
    _valid_schedule(b)
    _, n = db.run("UPDATE schedules SET name=?,frequency=?,time_hhmm=?,weekday=?,purpose=?,note=?,enabled=? WHERE id=?",
                  (b.name, b.frequency, b.time, b.weekday, b.purpose, b.note, int(b.enabled), sid))
    if not n:
        raise HTTPException(404, "not found")
    return db.one("SELECT * FROM schedules WHERE id=?", (sid,))


@app.delete("/api/schedules/{sid}", dependencies=[Depends(auth)])
def del_schedule(sid: int):
    db.run("DELETE FROM schedules WHERE id=?", (sid,))
    return {"ok": True}


# ---------- business data ----------
class ProductIn(BaseModel):
    name: str = Field(max_length=120)
    sku: str | None = Field(default=None, max_length=60)
    price: float = Field(ge=0)
    stock: int = Field(default=0, ge=0)
    description: str | None = Field(default=None, max_length=1000)


class OrderIn(BaseModel):
    product_id: int | None = None
    product_name: str = Field(default="", max_length=120)
    quantity: int = Field(default=1, ge=1, le=100000)
    unit_price: float = Field(ge=0)
    status: str = "pending"
    customer: str | None = Field(default=None, max_length=120)


class StatusIn(BaseModel):
    status: str


class ExpenseIn(BaseModel):
    category: str = Field(max_length=60)
    amount: float = Field(gt=0)
    note: str | None = Field(default=None, max_length=300)


@app.get("/api/analytics", dependencies=[Depends(auth)])
def analytics(period: str = "month"):
    try:
        out = {"summary": business.summary(db, period)}
        if period in ("today", "week", "month"):
            out["growth"] = business.growth(db, period)
        return out
    except ValueError as e:
        raise HTTPException(422, str(e))


@app.get("/api/products", dependencies=[Depends(auth)])
def products():
    return {"products": business.list_products(db)}


@app.post("/api/products", dependencies=[Depends(auth)])
def add_product(b: ProductIn):
    i, _ = db.run("INSERT INTO products(name,sku,price,stock,description,created_at) VALUES(?,?,?,?,?,?)",
                  (b.name, b.sku, b.price, b.stock, b.description, iso(now_utc())))
    return db.one("SELECT * FROM products WHERE id=?", (i,))


@app.put("/api/products/{pid}", dependencies=[Depends(auth)])
def put_product(pid: int, b: ProductIn):
    _, n = db.run("UPDATE products SET name=?,sku=?,price=?,stock=?,description=? WHERE id=?", (b.name, b.sku, b.price, b.stock, b.description, pid))
    if not n:
        raise HTTPException(404, "not found")
    return db.one("SELECT * FROM products WHERE id=?", (pid,))


@app.delete("/api/products/{pid}", dependencies=[Depends(auth)])
def del_product(pid: int):
    db.run("DELETE FROM products WHERE id=?", (pid,))
    return {"ok": True}


@app.get("/api/orders", dependencies=[Depends(auth)])
def orders(status: str | None = None):
    return {"orders": business.list_orders(db, status, 100)}


@app.post("/api/orders", dependencies=[Depends(auth)])
def add_order(b: OrderIn):
    try:
        return business.add_order(db, b.product_name, b.quantity, b.unit_price, b.status, b.customer, b.product_id)
    except ValueError as e:
        raise HTTPException(422, str(e))


@app.patch("/api/orders/{oid}", dependencies=[Depends(auth)])
def set_order_status(oid: int, b: StatusIn):
    if b.status not in ("pending", "completed", "cancelled"):
        raise HTTPException(422, "bad status")
    _, n = db.run("UPDATE orders SET status=? WHERE id=?", (b.status, oid))
    if not n:
        raise HTTPException(404, "not found")
    return db.one("SELECT * FROM orders WHERE id=?", (oid,))


@app.delete("/api/orders/{oid}", dependencies=[Depends(auth)])
def del_order(oid: int):
    db.run("DELETE FROM orders WHERE id=?", (oid,))
    return {"ok": True}


@app.get("/api/expenses", dependencies=[Depends(auth)])
def expenses(period: str = "all"):
    try:
        return {"expenses": business.list_expenses(db, period, 200)}
    except ValueError as e:
        raise HTTPException(422, str(e))


@app.post("/api/expenses", dependencies=[Depends(auth)])
def add_expense(b: ExpenseIn):
    i, _ = db.run("INSERT INTO expenses(category,amount,note,created_at) VALUES(?,?,?,?)", (b.category, b.amount, b.note, iso(now_utc())))
    return db.one("SELECT * FROM expenses WHERE id=?", (i,))


@app.delete("/api/expenses/{eid}", dependencies=[Depends(auth)])
def del_expense(eid: int):
    db.run("DELETE FROM expenses WHERE id=?", (eid,))
    return {"ok": True}


# ---------- memory (you control it) ----------
class MemoryIn(BaseModel):
    kind: str = "note"
    title: str = Field(max_length=120)
    content: str = Field(max_length=1000)
    enabled: bool = True


@app.get("/api/memory", dependencies=[Depends(auth)])
def memory():
    return {"memory": db.all("SELECT * FROM memory ORDER BY id DESC")}


@app.post("/api/memory", dependencies=[Depends(auth)])
def add_memory(b: MemoryIn):
    i, _ = db.run("INSERT INTO memory(kind,title,content,enabled,source,created_at) VALUES(?,?,?,?,'user',?)",
                  (b.kind, b.title, b.content, int(b.enabled), iso(now_utc())))
    return db.one("SELECT * FROM memory WHERE id=?", (i,))


@app.put("/api/memory/{mid}", dependencies=[Depends(auth)])
def put_memory(mid: int, b: MemoryIn):
    _, n = db.run("UPDATE memory SET kind=?,title=?,content=?,enabled=? WHERE id=?", (b.kind, b.title, b.content, int(b.enabled), mid))
    if not n:
        raise HTTPException(404, "not found")
    return db.one("SELECT * FROM memory WHERE id=?", (mid,))


@app.delete("/api/memory/{mid}", dependencies=[Depends(auth)])
def del_memory(mid: int):
    db.run("DELETE FROM memory WHERE id=?", (mid,))
    return {"ok": True}


@app.delete("/api/memory", dependencies=[Depends(auth)])
def wipe_memory():
    db.run("DELETE FROM memory")
    return {"ok": True}


# ---------- settings ----------
class SettingsIn(BaseModel):
    store_transcripts: bool


@app.get("/api/settings", dependencies=[Depends(auth)])
def get_settings():
    return {"store_transcripts": db.get_setting("store_transcripts", "0") == "1"}


@app.put("/api/settings", dependencies=[Depends(auth)])
def put_settings(b: SettingsIn):
    db.set_setting("store_transcripts", "1" if b.store_transcripts else "0")
    if not b.store_transcripts:
        db.run("DELETE FROM transcripts")
    return {"store_transcripts": b.store_transcripts}
