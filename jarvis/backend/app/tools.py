"""Function-calling tools exposed to the realtime model. All answers come from the database."""
import json

from . import business
from .timeutil import iso, now_utc

PERIOD_ENUM = list(business.PERIODS)

TOOL_SPECS = [
    {"type": "function", "name": "get_business_summary",
     "description": "Revenue, expenses, net recorded earnings, order counts (completed/pending), units sold and top products "
                    "from the EzeeLabs database for a period. ALWAYS use this for any money or order figure.",
     "parameters": {"type": "object", "properties": {"period": {"type": "string", "enum": PERIOD_ENUM}}, "required": ["period"]}},
    {"type": "function", "name": "get_growth",
     "description": "Compare revenue/orders of the current day, week or month against the previous one.",
     "parameters": {"type": "object", "properties": {"period": {"type": "string", "enum": ["today", "week", "month"]}}, "required": ["period"]}},
    {"type": "function", "name": "list_orders",
     "description": "Recent orders, optionally filtered by status.",
     "parameters": {"type": "object", "properties": {"status": {"type": "string", "enum": ["pending", "completed", "cancelled"]},
                                                     "limit": {"type": "integer", "minimum": 1, "maximum": 20}}}},
    {"type": "function", "name": "list_products", "description": "EzeeLabs products with price and stock.",
     "parameters": {"type": "object", "properties": {}}},
    {"type": "function", "name": "list_expenses", "description": "Recorded business expenses for a period.",
     "parameters": {"type": "object", "properties": {"period": {"type": "string", "enum": PERIOD_ENUM},
                                                     "limit": {"type": "integer", "minimum": 1, "maximum": 20}}, "required": ["period"]}},
    {"type": "function", "name": "save_note",
     "description": "Save something the user explicitly asked you to remember. The user can review and delete it in the app.",
     "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "content": {"type": "string"}}, "required": ["title", "content"]}},
    {"type": "function", "name": "end_call",
     "description": "Hang up. Say a brief goodbye first, then call this once the user is finished or asks to end the call.",
     "parameters": {"type": "object", "properties": {}}},
]


def run_tool(db, name: str, args: dict) -> str:
    try:
        if name == "get_business_summary":
            out = business.summary(db, args.get("period", "all"))
        elif name == "get_growth":
            out = business.growth(db, args.get("period", "month"))
        elif name == "list_orders":
            out = business.list_orders(db, args.get("status"), args.get("limit", 10))
        elif name == "list_products":
            out = business.list_products(db)
        elif name == "list_expenses":
            out = business.list_expenses(db, args.get("period", "month"), args.get("limit", 10))
        elif name == "save_note":
            title, content = str(args.get("title", ""))[:120], str(args.get("content", ""))[:1000]
            if not title or not content:
                return json.dumps({"error": "title and content required"})
            db.run("INSERT INTO memory(kind,title,content,source,created_at) VALUES('note',?,?,'jarvis',?)", (title, content, iso(now_utc())))
            out = {"saved": True}
        else:
            out = {"error": f"unknown tool {name}"}
    except Exception as e:  # return errors to the model instead of crashing the call
        out = {"error": str(e)}
    return json.dumps(out, default=str)
