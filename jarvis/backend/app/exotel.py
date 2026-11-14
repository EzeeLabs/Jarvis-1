"""Exotel 'Connect Voice AI' client.
Docs: https://developer.exotel.com/docs/agentstream/connect-voice-ai
POST https://{host}/v1/Accounts/{sid}/Calls/connect  (HTTP Basic: API key / API token)
"""

class ExotelError(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class ExotelClient:
    def __init__(self, settings):
        self.s = settings

    async def connect(self, *, to: str, stream_url: str, time_limit: int, custom_field: str,
                      status_callback: str) -> dict:
        import httpx  # imported lazily so the rest of the backend is importable/testable without it

        url = f"https://{self.s.exotel_host}/v1/Accounts/{self.s.exotel_sid}/Calls/connect"
        form = [
            ("From", to),  # per Exotel docs, "From" is the number to dial
            ("CallerId", self.s.exotel_caller_id),
            ("StreamUrl", stream_url),
            ("StreamType", "bidirectional"),
            ("Record", "false"),
            ("TimeLimit", str(time_limit)),
            ("CustomField", custom_field[:128]),
            ("StatusCallback", status_callback),
            ("StatusCallbackEvents[]", "terminal"),
            ("StatusCallbackEvents[]", "answered"),
        ]
        files = [(k, (None, v)) for k, v in form]  # multipart/form-data, as in Exotel's curl examples
        try:
            async with httpx.AsyncClient(timeout=20) as c:
                r = await c.post(url, files=files, auth=(self.s.exotel_key, self.s.exotel_token))
        except httpx.HTTPError as e:
            raise ExotelError(f"Could not reach Exotel: {e.__class__.__name__}") from e
        if r.status_code >= 300:
            raise ExotelError(f"Exotel rejected the call (HTTP {r.status_code}): {r.text[:300]}")
        try:
            call = r.json().get("Call", {})
        except ValueError:
            raise ExotelError("Exotel returned an unreadable response")
        if not call.get("Sid"):
            raise ExotelError(f"Exotel response had no call Sid: {r.text[:200]}")
        return {"sid": call["Sid"], "status": call.get("Status", "")}
