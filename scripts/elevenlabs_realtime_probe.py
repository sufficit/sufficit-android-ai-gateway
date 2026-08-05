#!/usr/bin/env python3
"""Probe sanitizado: envia varios PCM16/16 kHz na mesma sessao Scribe Realtime."""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
from pathlib import Path
from urllib.parse import urlencode

import websockets


async def transcribe_commit(socket, pcm_path: Path) -> dict:
    audio = pcm_path.read_bytes()
    chunk_size = 6_400
    for offset in range(0, len(audio), chunk_size):
        end = min(offset + chunk_size, len(audio))
        await socket.send(
            json.dumps(
                {
                    "message_type": "input_audio_chunk",
                    "audio_base_64": base64.b64encode(audio[offset:end]).decode("ascii"),
                    "sample_rate": 16_000,
                    "commit": end == len(audio),
                }
            )
        )

    committed_text = ""
    while True:
        event = json.loads(await asyncio.wait_for(socket.recv(), timeout=45))
        event_type = event.get("message_type", "")
        if event_type in {"error", "input_error", "transcriber_error"}:
            raise RuntimeError(event.get("error") or event.get("message") or event_type)
        if event_type == "committed_transcript":
            committed_text = event.get("text", "").strip()
        if event_type == "committed_transcript_with_timestamps":
            return {
                "text": event.get("text", "").strip() or committed_text,
                "languageCode": event.get("language_code"),
                "wordCount": sum(
                    1 for word in event.get("words", []) if word.get("type") == "word"
                ),
            }


async def run(paths: list[Path]) -> None:
    token = os.environ.get("ELEVENLABS_API_KEY", "").strip()
    if not token:
        raise SystemExit("ELEVENLABS_API_KEY ausente")
    query = urlencode(
        {
            "model_id": "scribe_v2_realtime",
            "language_code": "por",
            "audio_format": "pcm_16000",
            "include_timestamps": "true",
            "include_language_detection": "true",
        }
    )
    url = f"wss://api.elevenlabs.io/v1/speech-to-text/realtime?{query}"
    async with websockets.connect(
        url,
        additional_headers={"xi-api-key": token},
        max_size=None,
        ping_interval=20,
    ) as socket:
        started = json.loads(await asyncio.wait_for(socket.recv(), timeout=20))
        if started.get("message_type") != "session_started":
            raise RuntimeError(f"evento inicial inesperado: {started.get('message_type')}")
        results = []
        for path in paths:
            results.append(await transcribe_commit(socket, path))
        print(
            json.dumps(
                {
                    "sameSession": True,
                    "commitCount": len(results),
                    "results": results,
                },
                ensure_ascii=False,
                indent=2,
            )
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pcm", nargs="+", type=Path)
    args = parser.parse_args()
    asyncio.run(run(args.pcm))


if __name__ == "__main__":
    main()
