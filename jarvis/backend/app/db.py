import os
import sqlite3
import threading
from contextlib import contextmanager

SCHEMA = """
CREATE TABLE IF NOT EXISTS calls (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, purpose TEXT NOT NULL DEFAULT 'chat',
  status TEXT NOT NULL, scheduled_for TEXT, created_at TEXT NOT NULL,
  initiated_at TEXT, answered_at TEXT, ended_at TEXT, duration_seconds INTEGER,
  exotel_sid TEXT, failure_reason TEXT, stream_token TEXT, schedule_id INTEGER, note TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_delayed ON calls(kind)
  WHERE kind='delayed' AND status IN ('scheduled','dialing');
CREATE INDEX IF NOT EXISTS calls_status ON calls(status, scheduled_for);
CREATE TABLE IF NOT EXISTS schedules (
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, frequency TEXT NOT NULL,
  time_hhmm TEXT NOT NULL, weekday INTEGER, purpose TEXT NOT NULL DEFAULT 'briefing',
  note TEXT, enabled INTEGER NOT NULL DEFAULT 1, last_run_date TEXT
);
CREATE TABLE IF NOT EXISTS products (
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sku TEXT, price REAL NOT NULL DEFAULT 0,
  stock INTEGER NOT NULL DEFAULT 0, description TEXT, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER, product_name TEXT NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 1, unit_price REAL NOT NULL, total REAL NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending', customer TEXT, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS expenses (
  id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, amount REAL NOT NULL,
  note TEXT, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS memory (
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL DEFAULT 'note', title TEXT NOT NULL,
  content TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, source TEXT NOT NULL DEFAULT 'user',
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS transcripts (
  id INTEGER PRIMARY KEY AUTOINCREMENT, call_id TEXT NOT NULL, role TEXT NOT NULL,
  text TEXT NOT NULL, created_at TEXT NOT NULL
);
"""

DEFAULT_SETTINGS = {"store_transcripts": "0"}


class DB:
    """Thin thread-safe sqlite3 wrapper (single connection, WAL, serialized by a lock)."""

    def __init__(self, path: str):
        if path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self.conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.conn.execute("PRAGMA journal_mode=WAL")
            self.conn.execute("PRAGMA foreign_keys=ON")
            self.conn.executescript(SCHEMA)
            for k, v in DEFAULT_SETTINGS.items():
                self.conn.execute("INSERT OR IGNORE INTO settings(key,value) VALUES(?,?)", (k, v))
        if path != ":memory:":
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass

    def all(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.conn.execute(sql, args).fetchall()]

    def one(self, sql, args=()):
        with self.lock:
            r = self.conn.execute(sql, args).fetchone()
            return dict(r) if r else None

    def run(self, sql, args=()):
        """Execute a write; returns (lastrowid, rowcount)."""
        with self.lock:
            cur = self.conn.execute(sql, args)
            return cur.lastrowid, cur.rowcount

    @contextmanager
    def tx(self):
        """Serialized immediate transaction."""
        with self.lock:
            self.conn.execute("BEGIN IMMEDIATE")
            try:
                yield self
                self.conn.execute("COMMIT")
            except BaseException:
                self.conn.execute("ROLLBACK")
                raise

    def get_setting(self, key, default=None):
        r = self.one("SELECT value FROM settings WHERE key=?", (key,))
        return r["value"] if r else default

    def set_setting(self, key, value):
        self.run("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                 (key, str(value)))
