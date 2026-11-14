# JARVIS V1.0 — EzeeLabs phone assistant

JARVIS places a **real phone call** (Exotel → your Android number) and, when you answer, bridges the call to
**OpenAI Realtime** for a two-way voice conversation. A FastAPI server owns everything that matters (scheduling,
dialing, limits, data, keys); the Android app is only a remote control.

```
Android app ──HTTPS──▶ FastAPI (scheduler, SQLite, auth) ──REST──▶ Exotel "Connect Voice AI" ──PSTN──▶ your phone
                              ▲  WSS /ws/exotel/{one-time-token}  (PCM16 24 kHz)
                              └────────── bridge ──────────▶ OpenAI Realtime (WebSocket, tools = your DB)
```

## What is verified, and what is not (read this first)

| Area | Status |
|---|---|
| Call lifecycle, 3-minute delayed call, duplicate-press protection (25 concurrent presses → 1 row), cancel, cooldown, daily cap, restart safety, IST schedules (daily / weekly / once), no-answer detection | **Unit-tested, passing** (29 tests, `python -m unittest discover -s tests -t .`) |
| Business analytics (IST day/week/month boundaries, growth), auth/JWT/scrypt/rate limiter, audio re-chunking, the whole Exotel⇄OpenAI bridge (greeting, tool calls from the DB, barge-in, hang-up) | **Unit-tested against fake sockets** |
| FastAPI route layer (`app/main.py`) | Compiles; **not run** (FastAPI could not be installed where this was built). First thing to do: run it. |
| Real Exotel call, real OpenAI session, real audio quality/latency | **Never run.** Needs your credentials. |
| Android app | **Never compiled** (no Android SDK here). Written against AGP 8.5.2 / Kotlin 1.9.24 / Compose BOM 2024.06. Expect to fix a few compile errors on the first CI build. |

Things I could not confirm from the docs and you must check on first real use:
1. Exotel's **status-callback field names** (`Status` / `CallStatus` are read tolerantly). Look at the `exotel callback` log lines after a call.
2. Whether your **trial** Exotel account allows the `Calls/connect` AgentStream endpoint, or only paid accounts do.
3. OpenAI model name (`gpt-realtime` default, configurable) and voice (`cedar`, configurable). OpenAI has no "British" voice setting; the accent comes from the instructions, so it is an approximation.
4. Exotel docs call the number being dialled `From` and your ExoPhone `CallerId` for this endpoint; implemented exactly as documented.

## Provider choice (India)

Chosen: **Exotel**, because it is an Indian provider with TRAI-compliant onboarding and a documented
bidirectional WebSocket "Connect Voice AI" API (`POST https://api.in.exotel.com/v1/Accounts/{sid}/Calls/connect`,
`StreamUrl`, `StreamType=bidirectional`, `TimeLimit`, `StatusCallback`). I did **not** verify Twilio's India outbound
rules and did not pick it. I did not evaluate other Indian providers.

Constraints from Exotel's docs:
- **KYC is mandatory** (business documents such as PAN/GST or equivalent) before production numbers and calls to non-whitelisted numbers.
- **Trial accounts** can only call **verified (whitelisted) numbers**, use a shared trial ExoPhone, and have limited credits (sources disagree on the amount; check your dashboard). Your own number must be whitelisted.
- Calls only ever go to `APPROVED_NUMBER`. DND/NDNC rules apply to Exotel's side; this is a call to yourself.

## Costs (check current pricing pages; I did not verify prices)
- **Exotel**: free trial credits, then prepaid credits per minute + ExoPhone rental after KYC.
- **OpenAI**: Realtime audio is billed per audio token (input and output). A 10-minute cap (`MAX_CALL_SECONDS`) and the daily cap (`MAX_CALLS_PER_DAY`) bound your spend.
- **Server**: must be always-on with a public HTTPS domain (scheduled calls need a running process). Free tiers that sleep when idle will miss calls. A small VPS or an always-free cloud VM works; verify current terms.
- **Android build**: GitHub Actions (free tier) builds the APK; no laptop needed.
- Domain: any, or a free DNS name pointing at the VPS (Caddy fetches the HTTPS certificate automatically).

## Setup (phone-only workflow)

1. **Get the code on GitHub.** In Termux: `pkg install git unzip`, unzip this archive, `git init`, add your repo, push (use a GitHub personal access token as the password). The workflow `.github/workflows/android.yml` then builds the APK on every push to `android/` and runs the backend tests.
2. **Server**: create a small Linux VPS, point a domain at it, install Docker (`curl -fsSL https://get.docker.com | sh`), `git clone` your repo there (from Termux use `ssh` for all server commands).
3. **Secrets**: `cd backend && cp .env.example .env && python3 -m app.make_secrets` (prints `ADMIN_PASSWORD_HASH`, `JWT_SECRET`, `WEBHOOK_SECRET`; paste into `.env`). Set `APPROVED_NUMBER`, `PUBLIC_BASE_URL`, Exotel and OpenAI values. `chmod 600 .env`. Leave `CALLS_ENABLED=false`.
4. **Run**: `cd deploy && DOMAIN=jarvis.example.com docker compose up -d --build`. Check `https://jarvis.example.com/health`.
5. **App**: download `app-debug.apk` from the GitHub Actions run (Artifacts), install it, enter `https://jarvis.example.com` and your password.
6. **Exotel**: sign up, whitelist your number (trial) or finish KYC, note Account SID, API key/token, and your ExoPhone → `.env`.

## Testing order (do not skip; nothing is dialled until step 4)
1. `cd backend && python3 -m unittest discover -s tests -t .` → all pass.
2. In the app press **Call me**: countdown runs, notifications fire, and at 0:00 the call fails with *"Calls are disabled on the server (CALLS_ENABLED=false)"*. That proves scheduling, countdown, failure display and notifications without spending money. Also test: press repeatedly (one call), **Cancel** (no call), close the app mid-countdown (countdown notification continues; server still fires).
3. Fill in Exotel + OpenAI keys, still disabled → the failure reason should now be the same disabled message (credentials aren't touched yet).
4. Set `CALLS_ENABLED=true`, `docker compose up -d`. Press **Call me**. Answer. Speak, interrupt it, ask "what's today's revenue?" (it must read the DB; add a test order first). Check `docker compose logs jarvis` for the `exotel callback` lines and compare with `apply_callback` in `app/calls.py`.
5. Test failure paths: decline the call (expect *busy/no answer* with reason), and temporarily blank `EXOTEL_API_TOKEN` (expect a clear failure notification).
6. Only then enable the seeded schedules (daily briefing / weekly report, created **disabled**) in the Calls tab.

## How the 3-minute call works
- **Call me** → `POST /api/calls/call-me` creates one row with `scheduled_for = now + 180 s` (a unique DB index plus a transaction make duplicates impossible; a repeat press returns the same call).
- The server's scheduler loop (1 s tick) atomically claims the row at the due time and calls Exotel **once**. Timing is "dial at T+180 s"; the phone rings a few seconds later.
- The app shows the countdown from the server's timestamp (clock-skew corrected) and starts a foreground service that keeps a system countdown notification and posts *about to call* (≈30 s before), *initiated*, and *failed + reason* notifications, even after you close the app. If the phone kills that service, the call still happens; you only lose the notifications.
- If the server was down at the due time and is >90 s late, the call is **not** placed late; it is marked failed with that reason.
- No-answer / busy / failed come from Exotel's callback, or "no answer" is inferred if the call never connects in 120 s.

## Safety summary
Password-protected API (scrypt hash, 30-day JWT), per-IP rate limits, login lockout window, only `APPROVED_NUMBER` is ever dialled, one-time stream token per call (WebSocket rejected otherwise), webhook secret, `CALLS_ENABLED` master switch, call length cap (plus Exotel `TimeLimit`), cooldown, daily cap, no audio recording (`Record=false`), transcripts off by default, DB file `chmod 600`, API keys server-side only, HTTPS-only app. Run exactly **one** uvicorn worker (the scheduler is in-process).

## Known limits / next steps
- Single owner, SQLite, in-memory rate limiter (fine for one user).
- Notifications rely on a foreground service rather than Firebase push; FCM would also cover a killed app.
- Android signing is debug-only; add a release keystore if you want Play distribution.
