import base64
import hashlib
import hmac
import os
import re
import time
from collections import defaultdict, deque

import jwt

E164 = re.compile(r"^\+[1-9]\d{7,14}$")


def hash_password(password: str, n=2**14, r=8, p=1) -> str:
    salt = os.urandom(16)
    dk = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, dklen=32)
    return "scrypt${}${}${}${}${}".format(n, r, p, base64.b64encode(salt).decode(), base64.b64encode(dk).decode())


def verify_password(password: str, stored: str) -> bool:
    try:
        _, n, r, p, salt, dk = stored.split("$")
        calc = hashlib.scrypt(password.encode(), salt=base64.b64decode(salt), n=int(n), r=int(r), p=int(p), dklen=32)
        return hmac.compare_digest(calc, base64.b64decode(dk))
    except Exception:
        return False


TOKEN_TTL = 30 * 24 * 3600


def make_token(secret: str, now: float | None = None) -> str:
    now = now or time.time()
    return jwt.encode({"sub": "owner", "iat": int(now), "exp": int(now + TOKEN_TTL)}, secret, algorithm="HS256")


def check_token(secret: str, token: str) -> bool:
    try:
        jwt.decode(token, secret, algorithms=["HS256"], options={"require": ["exp", "sub"]})
        return True
    except jwt.PyJWTError:
        return False


class RateLimiter:
    """In-memory sliding window. Fine for a single-process personal server."""

    def __init__(self):
        self.hits: dict[str, deque] = defaultdict(deque)

    def allow(self, key: str, limit: int, window: float, now: float | None = None) -> bool:
        now = now or time.monotonic()
        q = self.hits[key]
        while q and q[0] <= now - window:
            q.popleft()
        if len(q) >= limit:
            return False
        q.append(now)
        return True


def mask(number: str) -> str:
    return number[:3] + "•" * max(0, len(number) - 5) + number[-2:] if number else ""
