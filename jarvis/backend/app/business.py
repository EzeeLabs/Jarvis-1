"""EzeeLabs business data + analytics. Every figure comes from the database; nothing is invented."""
from .timeutil import PREVIOUS, iso, now_utc, period_bounds

PERIODS = ("all", "today", "yesterday", "week", "last_week", "month", "last_month")


def _rng(col, start, end):
    if start is None:
        return "", ()
    return f" AND {col} >= ? AND {col} < ?", (start, end)


def summary(db, period="all", now=None):
    if period not in PERIODS:
        raise ValueError(f"period must be one of {PERIODS}")
    start, end = period_bounds(period, now)
    w, a = _rng("created_at", start, end)
    row = db.one(
        "SELECT COALESCE(SUM(CASE WHEN status='completed' THEN total END),0) AS revenue,"
        " COUNT(*) AS orders_total,"
        " SUM(status='completed') AS completed, SUM(status='pending') AS pending, SUM(status='cancelled') AS cancelled,"
        " COALESCE(SUM(CASE WHEN status='pending' THEN total END),0) AS pending_value,"
        " COALESCE(SUM(CASE WHEN status='completed' THEN quantity END),0) AS units_sold"
        f" FROM orders WHERE 1=1{w}", a)
    exp = db.one(f"SELECT COALESCE(SUM(amount),0) AS expenses FROM expenses WHERE 1=1{w}", a)["expenses"]
    top = db.all(
        "SELECT product_name AS product, SUM(quantity) AS units, SUM(total) AS revenue FROM orders"
        f" WHERE status='completed'{w} GROUP BY product_name ORDER BY revenue DESC LIMIT 5", a)
    rev = round(row["revenue"], 2)
    return {
        "period": period, "currency": "INR", "revenue": rev, "expenses": round(exp, 2),
        "net_recorded_earnings": round(rev - exp, 2),
        "orders_total": row["orders_total"], "completed_orders": row["completed"] or 0,
        "pending_orders": row["pending"] or 0, "cancelled_orders": row["cancelled"] or 0,
        "pending_order_value": round(row["pending_value"], 2), "units_sold": row["units_sold"],
        "top_products": top, "has_data": bool(row["orders_total"] or exp),
    }


def growth(db, period="month", now=None):
    if period not in PREVIOUS:
        raise ValueError("period must be today, week or month")
    cur, prev = summary(db, period, now), summary(db, PREVIOUS[period], now)

    def pct(c, p):
        return None if p == 0 else round((c - p) / p * 100, 1)

    return {
        "period": period, "compared_with": PREVIOUS[period],
        "revenue": cur["revenue"], "previous_revenue": prev["revenue"], "revenue_change_pct": pct(cur["revenue"], prev["revenue"]),
        "net": cur["net_recorded_earnings"], "previous_net": prev["net_recorded_earnings"],
        "orders": cur["completed_orders"], "previous_orders": prev["completed_orders"],
        "orders_change_pct": pct(cur["completed_orders"], prev["completed_orders"]),
        "note": "change_pct is null when the previous period had zero.",
    }


def list_orders(db, status=None, limit=20):
    q, a = "SELECT * FROM orders", []
    if status:
        q += " WHERE status=?"
        a.append(status)
    return db.all(q + " ORDER BY created_at DESC, id DESC LIMIT ?", (*a, min(int(limit), 100)))


def list_expenses(db, period="all", limit=50, now=None):
    start, end = period_bounds(period, now)
    w, a = _rng("created_at", start, end)
    return db.all(f"SELECT * FROM expenses WHERE 1=1{w} ORDER BY created_at DESC, id DESC LIMIT ?", (*a, min(int(limit), 200)))


def list_products(db):
    return db.all("SELECT * FROM products ORDER BY name")


def add_order(db, product_name, quantity, unit_price, status="pending", customer=None, product_id=None, created_at=None):
    if status not in ("pending", "completed", "cancelled"):
        raise ValueError("bad status")
    if quantity < 1 or unit_price < 0:
        raise ValueError("bad quantity/price")
    if product_id:
        p = db.one("SELECT name FROM products WHERE id=?", (product_id,))
        if not p:
            raise ValueError("unknown product")
        product_name = p["name"]
    rid, _ = db.run(
        "INSERT INTO orders(product_id,product_name,quantity,unit_price,total,status,customer,created_at) VALUES(?,?,?,?,?,?,?,?)",
        (product_id, product_name, quantity, unit_price, round(quantity * unit_price, 2), status, customer, created_at or iso(now_utc())))
    return db.one("SELECT * FROM orders WHERE id=?", (rid,))
