"""Run:  python -m app.make_secrets   -> prints values for your .env"""
import getpass
import secrets

from .security import hash_password

pw = getpass.getpass("Choose a dashboard password (min 12 chars): ")
if len(pw) < 12:
    raise SystemExit("Too short.")
print("\nADMIN_PASSWORD_HASH=" + hash_password(pw))
print("JWT_SECRET=" + secrets.token_urlsafe(48))
print("WEBHOOK_SECRET=" + secrets.token_urlsafe(32))
