# JARVIS V1.0 — EzeeLabs in-app voice assistant

JARVIS is an Android-first personal assistant for EzeeLabs. The current voice direction is an **in-app voice conversation**: no cellular phone call and no Exotel provider is required.

## Current architecture

```
Android app
  ├─ 3-minute countdown + OnePlus-inspired call screen
  ├─ microphone capture / speaker playback (PCM16, mono, 24 kHz)
  └─ authenticated WebSocket: /ws/voice
            │
            ▼
FastAPI backend
  ├─ verifies the app's bearer token
  ├─ keeps the OpenAI API key server-side
  ├─ loads enabled JARVIS memory and business tools
  └─ proxies audio/events to OpenAI Realtime
```

The app does not contain the OpenAI API key. Android sends audio only to the authenticated JARVIS backend; the backend connects to OpenAI. The configured default is `gpt-realtime-2.1-mini` with the `cedar` voice.

## Current implementation status

| Feature | Status |
|---|---|
| Android dashboard and business/memory sections | Existing |
| OnePlus-inspired animated in-app call screen | Implemented |
| Three-minute countdown | Implemented in the open app |
| Microphone permission and PCM audio capture | Implemented |
| Speaker playback, mute and speaker/earpiece controls | Implemented |
| Authenticated backend WebSocket | Implemented |
| Backend-to-OpenAI Realtime bridge | Implemented |
| Business tools and enabled memory in voice prompt | Connected in bridge |
| Backend unit tests | Passing in CI |
| Android debug APK compile | CI compile passed on an intermediate commit; rebuild after latest changes |
| Real OpenAI session and physical-device audio test | Not yet tested; requires server configuration and an actual device |
| Background countdown/push notification for in-app voice | Not implemented yet; keep JARVIS open during the countdown and conversation |

**Important:** a successful APK build does not prove microphone permissions, device audio routing, server deployment, API access, or voice quality. Those still need a real-device end-to-end test.

## Backend setup

1. Deploy the backend using the existing Docker configuration and a public HTTPS domain.
2. In `jarvis/backend`, copy `.env.example` to `.env`.
3. Generate `JWT_SECRET` and `ADMIN_PASSWORD_HASH` with `python -m app.make_secrets`.
4. Set `OPENAI_API_KEY` on the backend host only. Do not put it in Android source, the APK, GitHub, screenshots, or messages.
5. Keep `OPENAI_REALTIME_MODEL=gpt-realtime-2.1-mini` and `OPENAI_VOICE=cedar` unless you intentionally change them.
6. Keep the server's `.env` private and restrict its file permissions. Restart/rebuild the backend after changing it.
7. Check `https://YOUR-DOMAIN/health`, then sign in to the Android app using the HTTPS server URL and the admin password.

No Exotel account, caller ID, approved phone number, or cellular calling credits are needed for this in-app voice path. The old Exotel modules remain in the repository as legacy code but are not used by the new dashboard voice button.

## Costs and account ownership

- The Realtime API is usage-billed; it is not automatically free. The mini Realtime model is the lower-cost Realtime option in the current model family, but audio input and output are still billed by token usage.
- Server hosting/domain costs depend on the provider.
- Since API billing and account setup require an adult account holder, a parent or guardian must handle any account, payment method, or spending limit. Do not add payment details yourself.
- Keep the session duration limit configured and monitor usage before repeated testing.

## Privacy and security

- OpenAI credentials stay on the backend.
- The voice WebSocket requires the app's existing bearer token.
- Microphone access is requested by Android at runtime.
- Audio is streamed live for the session; the app does not intentionally save microphone audio.
- Transcript storage remains off by default. If enabled in settings, text transcripts may be stored in the backend database.
- Use HTTPS/WSS in deployment. Do not expose the backend database or `.env` file.
- The repository is currently public. Make it private if you do not intend the source code to be public, and never commit credentials or personal details.

## Testing checklist

1. Run backend tests: `python -m unittest discover -s tests -t .`.
2. Build the Android app: `gradle --no-daemon -p jarvis/android assembleDebug`.
3. Install the debug APK on an Android device.
4. Sign in to the deployed HTTPS backend.
5. Confirm the backend reports voice as configured.
6. Tap the voice-session button and grant microphone permission.
7. Keep the app open through the three-minute countdown.
8. Confirm JARVIS speaks first, then test two-way conversation, mute, speaker/earpiece, and end session.
9. Check backend logs for connection errors. Do not share API keys or full logs containing secrets.

## Known limits / next steps

- The three-minute timer currently runs in the open Android screen. Background scheduling and a notification-to-resume flow are future work.
- Audio routing, echo cancellation, and sample-rate support vary by Android device and need physical-device testing.
- The app currently has a simple single-session audio client. Reconnection and robust network recovery need more work.
- The server currently uses a single-process SQLite design; keep one Uvicorn worker.
- Android builds are debug-signed. A release signing setup is needed before public distribution.
