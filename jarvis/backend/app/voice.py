"""Authenticated in-app audio bridge: Android PCM audio <-> OpenAI Realtime."""
import asyncio
import json
import logging

from .realtime import build_instructions
from .tools import TOOL_SPECS, run_tool

log = logging.getLogger("jarvis.voice")


class InAppVoiceBridge:
    def __init__(self, app_ws, db, settings, connect_fn=None):
        self.app_ws = app_ws
        self.db = db
        self.settings = settings
        self.connect_fn = connect_fn
        self.oai = None
        self.closed = False

    async def send_app(self, payload):
        if not self.closed:
            await self.app_ws.send_json(payload)

    async def send_oai(self, payload):
        await self.oai.send(json.dumps(payload))

    def session_config(self):
        return {
            "type": "realtime",
            "output_modalities": ["audio"],
            "instructions": build_instructions(self.db, {"purpose": "chat", "id": "in-app"}),
            "audio": {
                "input": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "transcription": {"model": "gpt-4o-mini-transcribe"},
                    "turn_detection": {
                        "type": "server_vad",
                        "threshold": 0.6,
                        "prefix_padding_ms": 300,
                        "silence_duration_ms": 600,
                    },
                },
                "output": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "voice": self.settings.openai_voice,
                },
            },
            "tools": TOOL_SPECS,
            "tool_choice": "auto",
        }

    async def run(self):
        if not self.settings.openai_key:
            await self.send_app({"type": "error", "message": "JARVIS AI is not configured on the server yet."})
            return
        if self.connect_fn is None:
            from websockets.asyncio.client import connect
            self.connect_fn = connect

        url = f"wss://api.openai.com/v1/realtime?model={self.settings.openai_model}"
        try:
            async with self.connect_fn(
                url,
                additional_headers={"Authorization": f"Bearer {self.settings.openai_key}"},
                ping_interval=20,
                ping_timeout=20,
                max_size=8 * 1024 * 1024,
            ) as oai:
                self.oai = oai
                # Follow the Realtime session lifecycle: wait for session.created,
                # apply configuration, then wait for session.updated before audio.
                first = json.loads(await asyncio.wait_for(oai.recv(), timeout=15))
                if first.get("type") != "session.created":
                    raise RuntimeError(f"Unexpected Realtime handshake event: {first.get('type')}")
                await self.send_oai({"type": "session.update", "session": self.session_config()})
                while True:
                    update = json.loads(await asyncio.wait_for(oai.recv(), timeout=15))
                    if update.get("type") == "error":
                        raise RuntimeError("Realtime rejected the JARVIS session configuration")
                    if update.get("type") == "session.updated":
                        break
                await self.send_oai({
                    "type": "response.create",
                    "response": {
                        "instructions": (
                            "Greet the user briefly as JARVIS, his friendly EzeeLabs AI assistant. "
                            "Speak naturally in a calm British English voice, then ask how you can help."
                        )
                    },
                })
                await self.send_app({"type": "state", "state": "connected"})
                tasks = [
                    asyncio.create_task(self.from_android()),
                    asyncio.create_task(self.from_openai()),
                    asyncio.create_task(self.session_limit()),
                ]
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                for task in done:
                    try:
                        task.result()
                    except Exception:
                        log.exception("voice bridge task failed")
        except Exception as exc:
            log.exception("in-app realtime connection failed")
            await self.send_app({"type": "error", "message": "Could not connect to the AI voice service. Check server configuration and API access."})
        finally:
            try:
                await self.app_ws.send_json({"type": "state", "state": "ended"})
            except Exception:
                pass
            self.closed = True

    async def from_android(self):
        while True:
            event = await self.app_ws.receive_json()
            kind = event.get("type")
            if kind == "audio":
                payload = event.get("audio")
                if isinstance(payload, str) and len(payload) <= 40000:
                    await self.send_oai({"type": "input_audio_buffer.append", "audio": payload})
            elif kind == "end":
                return
            elif kind == "ping":
                await self.send_app({"type": "pong"})

    async def from_openai(self):
        async for raw in self.oai:
            event = json.loads(raw)
            kind = event.get("type", "")
            if kind in ("response.output_audio.delta", "response.audio.delta"):
                await self.send_app({"type": "audio", "audio": event.get("delta", "")})
            elif kind in ("input_audio_buffer.speech_started",):
                await self.send_app({"type": "state", "state": "listening"})
            elif kind in ("response.created", "response.output_audio.started"):
                await self.send_app({"type": "state", "state": "speaking"})
            elif kind in ("response.done", "response.output_audio.done", "response.audio.done"):
                await self.send_app({"type": "state", "state": "listening"})
            elif kind == "conversation.item.input_audio_transcription.completed":
                await self.send_app({"type": "transcript", "role": "user", "text": event.get("transcript", "")})
            elif kind in ("response.output_audio_transcript.done", "response.audio_transcript.done"):
                await self.send_app({"type": "transcript", "role": "jarvis", "text": event.get("transcript", "")})
            elif kind == "response.function_call_arguments.done":
                asyncio.create_task(self.run_tool(event))
            elif kind == "error":
                log.error("OpenAI Realtime error: %s", event.get("error"))
                await self.send_app({"type": "error", "message": "The AI voice service returned an error."})

    async def run_tool(self, event):
        name = event.get("name")
        call_id = event.get("call_id")
        try:
            args = json.loads(event.get("arguments") or "{}")
        except (TypeError, ValueError):
            args = {}
        if name == "end_call":
            result = json.dumps({"ok": True})
            await self.send_app({"type": "state", "state": "ending"})
        else:
            result = await asyncio.to_thread(run_tool, self.db, name, args)
        await self.send_oai({
            "type": "conversation.item.create",
            "item": {"type": "function_call_output", "call_id": call_id, "output": result},
        })
        if name == "end_call":
            self.closed = True
            try:
                await self.app_ws.close(code=1000, reason="JARVIS ended the voice session")
            except Exception:
                pass
        else:
            await self.send_oai({"type": "response.create"})

    async def session_limit(self):
        await asyncio.sleep(max(60, min(self.settings.max_call_seconds, 600)))
        await self.send_app({"type": "state", "state": "ending"})
        await self.send_oai({
            "type": "response.create",
            "response": {
                "instructions": "Tell the user briefly that this session's time limit has been reached, then say goodbye."
            },
        })
        await asyncio.sleep(12)
