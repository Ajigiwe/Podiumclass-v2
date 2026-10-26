"""
Talk to LiveKit Egress -- the worker that records a room into an MP4 file.

Recording is server-side on purpose. The file has to survive the host closing
their laptop, and only the server can composite everyone into one video, so
there is no browser MediaRecorder path anywhere in this project.

Egress runs as its own container and speaks twirp-over-HTTP. Every call here
can therefore fail for reasons that have nothing to do with the request being
valid: Docker stopped, the worker restarting, Redis cycling. Those become
``RecordingUnavailable``, which the views answer with 503 -- the API must never
let a caller believe a recording started when nothing is recording. That is the
same rule payments follow when the provider is missing, and for the same
reason: a silent success here would be a lie about a lecture that no longer
exists.

Paths handed to egress are paths *inside the container* (``/out/...``); the
serving side of the app works from ``settings.RECORDINGS_ROOT`` on this
machine. Only a file's basename is stored, so the two can never drift apart.
"""
import json
import os
import socket
import time
import urllib.error
import urllib.request

import jwt
from django.conf import settings


class RecordingUnavailable(Exception):
    """Egress cannot be reached, or refused the call at the transport level."""

    def __init__(self, message="Recording service is unavailable."):
        super().__init__(message)
        self.message = message


class RecordingRejected(Exception):
    """Egress answered and says *this* recording cannot run (wrong room, ...)."""

    def __init__(self, message):
        super().__init__(message)
        self.message = message


#: Statuses in which a recording is still being written to disk.
LIVE_STATUSES = ("starting", "active", "ending")


def egress_url() -> str:
    """Where the worker's HTTP API listens. Empty means "not configured"."""
    return (getattr(settings, "LIVEKIT_EGRESS_URL", "") or "").rstrip("/")


def _recorder_token(ttl_seconds: int = 300) -> str:
    """
    A LiveKit JWT carrying the ``roomRecord`` grant egress checks.

    It is signed with the same environment material the classroom uses. The
    ``dev-only-`` placeholders are refused here for the same reason token
    minting refuses them: the worker would reject them anyway, and a 503 that
    names the real cause is kinder than a mystery timeout.
    """
    api_key = settings.LIVEKIT_API_KEY
    api_secret = settings.LIVEKIT_API_SECRET
    if (
        not api_key
        or not api_secret
        or str(api_key).startswith("dev-only-")
        or str(api_secret).startswith("dev-only-")
    ):
        raise RecordingUnavailable(
            "Recording requires LIVEKIT_API_KEY and LIVEKIT_API_SECRET to be set."
        )
    now = int(time.time())
    return jwt.encode(
        {
            "iss": api_key,
            "sub": "podium-recorder",
            "nbf": now - 30,
            "exp": now + ttl_seconds,
            "video": {"room": "*", "roomRecord": True},
        },
        api_secret,
        algorithm="HS256",
    )


def _rpc(method: str, payload: dict, timeout: float = 10.0) -> dict:
    """POST one twirp request to the worker and decode its JSON answer."""
    base = egress_url()
    if not base:
        raise RecordingUnavailable(
            "Recording is not configured on this deployment (LIVEKIT_EGRESS_URL is empty)."
        )

    request = urllib.request.Request(
        f"{base}/twirp/livekit.Egress/{method}",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {_recorder_token()}",
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = (json.loads(exc.read().decode("utf-8")) or {}).get("msg", "")
        except Exception:
            pass
        if 400 <= exc.code < 500:
            # Twirp's application errors ("room not found", "already exists").
            raise RecordingRejected(
                detail or f"The recording worker refused this request (HTTP {exc.code})."
            )
        raise RecordingUnavailable(
            detail or f"The recording worker is not answering properly (HTTP {exc.code})."
        )
    except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as exc:
        # Connection refused, timed out, Docker not running.
        raise RecordingUnavailable(f"The recording worker is unreachable: {exc}")

    try:
        return json.loads(raw.decode("utf-8")) if raw else {}
    except ValueError:
        raise RecordingUnavailable("The recording worker returned an unreadable response.")


def start_room_composite(room_name: str, filepath: str) -> dict:
    """
    Start recording the whole room into ``filepath`` (absolute, inside the
    container). The answer carries ``egress_id`` and a starting status.
    """
    return _rpc(
        "StartRoomCompositeEgress",
        {
            "room_name": room_name,
            "layout": "grid",
            "file_outputs": [{"file_type": "MP4", "filepath": filepath}],
        },
    )


def stop(egress_id: str) -> dict:
    """Finish a recording cleanly so the MP4 is finalized rather than truncated."""
    return _rpc("StopEgress", {"egress_id": egress_id})


def status(egress_id: str) -> dict:
    """
    Poll one recording's state.

    An empty ``items`` list means the worker has no memory of this id -- with a
    fresh Redis or a rebuilt container that happens. The caller decides what
    that means (the file on disk, if any, is the tie-breaker).
    """
    answer = _rpc("ListEgress", {"egress_id": egress_id})
    items = (answer or {}).get("items") or []
    return items[0] if items else {}


def _seconds_from_epoch(value):
    """Egress reports unix time; the unit has shifted between versions."""
    try:
        raw = float(value or 0)
    except (TypeError, ValueError):
        return None
    if raw <= 0:
        return None
    if raw > 1e14:          # microseconds
        raw /= 1e6
    elif raw > 1e11:        # milliseconds
        raw /= 1e3
    return int(raw)


def _duration_seconds(value):
    """Scale a duration that is obviously not expressed in seconds."""
    try:
        raw = float(value or 0)
    except (TypeError, ValueError):
        return None
    if raw <= 0:
        return None
    if raw > 172800:        # more than two days in seconds = finer unit
        if raw > 1e14:
            raw /= 1e6
        elif raw > 1e11:
            raw /= 1e3
    return int(raw)


def snapshot(info: dict) -> dict:
    """
    Reduce an ``EgressInfo`` to the handful of facts worth storing.

    Egress has its own status vocabulary (``EGRESS_ACTIVE`` and friends); the
    prefix is dropped so the database row, the REST payload and the UI all
    speak one language.
    """
    info = info or {}
    status = str(info.get("status") or "").lower().replace("egress_", "") or "unknown"

    file_results = info.get("file_results") or []
    first = file_results[0] if isinstance(file_results, list) and file_results else {}

    started = _seconds_from_epoch(first.get("started_at") or info.get("started_at"))
    ended = _seconds_from_epoch(first.get("ended_at") or info.get("ended_at"))
    duration = _duration_seconds(first.get("duration"))
    if duration is None and started and ended and ended >= started:
        duration = ended - started

    size = first.get("size")
    try:
        size = int(size) if size not in (None, "") else None
    except (TypeError, ValueError):
        size = None

    return {
        "status": status,
        "error": str(info.get("error") or "").strip(),
        "started": started,
        "ended": ended,
        "duration": duration,
        "size": size,
    }
