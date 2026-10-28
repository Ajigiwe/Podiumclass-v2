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
import logging
import os
import socket
import time
import urllib.error
import urllib.request

import jwt
from django.conf import settings

logger = logging.getLogger(__name__)


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

#: How long a stop waits for a pipeline that is still coming up.
STARTUP_GRACE_SECONDS = 8.0

#: What the worker says when StopEgress arrives before it is capturing. The
#: take is discarded, so this is the one error a user must be able to read.
_PIPELINE_ABORT = "stop called before pipeline could start"

#: What the worker says when the pipeline never received media at all.
_START_SIGNAL_MISSING = "start signal not received"

PIPELINE_ABORT_MESSAGE = (
    "The recording was stopped before the worker had started capturing, so "
    "nothing was saved. Start a new recording."
)

#: The common case behind both of the above: a room where nobody is sending
#: audio or video. Room composite recording begins on the first published
#: track, so an empty stage never starts.
NOTHING_TO_RECORD_MESSAGE = (
    "The recorder received no audio or video from the class, so nothing was "
    "saved. Turn on your microphone or camera, then record again."
)


def is_pipeline_abort(message) -> bool:
    """Did the worker refuse because the pipeline never came up?"""
    return _PIPELINE_ABORT in str(message or "").lower()


def friendly_error(raw) -> str:
    """
    Rewrite the worker's diagnostics as something a lecturer can act on.

    "stop called before pipeline could start" is an operator's sentence: it
    names an internal stage and says nothing about the missing video. The rest
    of the worker's errors are passed through untouched -- they are rare, and
    inventing wording for them would be worse than quoting them.
    """
    text = str(raw or "").strip()
    if is_pipeline_abort(text):
        return PIPELINE_ABORT_MESSAGE
    if _START_SIGNAL_MISSING in text.lower():
        return NOTHING_TO_RECORD_MESSAGE
    return text


def egress_url() -> str:
    """Where the worker's HTTP API listens. Empty means "not configured"."""
    return (getattr(settings, "LIVEKIT_EGRESS_URL", "") or "").rstrip("/")


def _signing_keys():
    """The LiveKit key pair, or a refusal naming what is missing.

    The ``dev-only-`` placeholders are refused for the same reason token
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
    return api_key, api_secret


def _recorder_token(ttl_seconds: int = 300) -> str:
    """A LiveKit JWT carrying the ``roomRecord`` grant egress checks."""
    api_key, api_secret = _signing_keys()
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


def _room_admin_token(room_name: str, ttl_seconds: int = 120) -> str:
    """A JWT allowed to inspect one room (its participants and their tracks)."""
    api_key, api_secret = _signing_keys()
    now = int(time.time())
    return jwt.encode(
        {
            "iss": api_key,
            "sub": "podium-rooms",
            "nbf": now - 30,
            "exp": now + ttl_seconds,
            "video": {"room": room_name, "roomAdmin": True},
        },
        api_secret,
        algorithm="HS256",
    )


def _rpc(
    method: str,
    payload: dict,
    timeout: float = 10.0,
    service: str = "Egress",
    base: str | None = None,
    token: str | None = None,
) -> dict:
    """
    POST one twirp request and decode its JSON answer.

    The egress worker is reached through the LiveKit server (``Egress``
    service); the same server answers ``RoomService``, which is how the app
    asks who is publishing before it starts a recording.
    """
    base = (base or egress_url()).rstrip("/")
    if not base:
        raise RecordingUnavailable(
            "Recording is not configured on this deployment (LIVEKIT_EGRESS_URL is empty)."
        )

    request = urllib.request.Request(
        f"{base}/twirp/livekit.{service}/{method}",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {token or _recorder_token()}",
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
        # Connection refused, timed out, Docker not running. Python's socket
        # errors read like log output ("<urlopen error timed out>"), which is
        # the right text for a console and the wrong text for a teacher; the
        # raw cause goes to the logs, the UI gets the diagnosis.
        logger.warning("LiveKit Egress unreachable at %s: %s", base, exc)
        raise RecordingUnavailable(
            "Recording is unavailable right now -- the recording worker could "
            "not be reached. Ask your administrator to start the Egress worker."
        )

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


def _server_url() -> str:
    """The LiveKit server's HTTP API, which also serves ``RoomService``."""
    base = (getattr(settings, "LIVEKIT_API_URL", "") or "").rstrip("/")
    if base:
        return base
    # No http URL configured: derive one from the ws URL clients use.
    ws = (getattr(settings, "LIVEKIT_URL", "") or "").rstrip("/")
    if ws.startswith("wss://"):
        return "https://" + ws[len("wss://"):]
    if ws.startswith("ws://"):
        return "http://" + ws[len("ws://"):]
    return ""


def room_media(room_name: str) -> dict | None:
    """
    How many people in this room are actually sending audio or video.

    Room composite recording starts on the first published track. A class where
    everyone has their microphone and camera off is a class with nothing to
    record, and the worker does not fail -- it waits in ``EGRESS_STARTING``
    until someone stops it, which reads to a host as "recording is stuck".
    Asking the server first turns that hang into an answer.

    Returns ``None`` when the question cannot be answered (old server, no
    credentials, transient outage). The caller must then proceed: a recording
    must never be blocked because the check itself was unavailable.
    """
    base = _server_url()
    if not base or not room_name:
        return None
    try:
        answer = _rpc(
            "ListParticipants",
            {"room": room_name},
            timeout=5.0,
            service="RoomService",
            base=base,
            token=_room_admin_token(room_name),
        )
    except (RecordingUnavailable, RecordingRejected):
        return None

    participants = [
        p for p in (answer or {}).get("participants") or [] if isinstance(p, dict)
    ]
    # Hidden participants are the recorder and agents, not people in the class.
    people = [p for p in participants if not (p.get("permission") or {}).get("hidden")]
    publishing = 0
    for person in people:
        tracks = person.get("tracks") or []
        # A muted track publishes a stream but no frames; it is not something a
        # recording can be started from.
        if any(isinstance(t, dict) and not t.get("muted") for t in tracks):
            publishing += 1
    return {"people": len(people), "publishing": publishing}


def wait_for_capture(egress_id: str, timeout: float = STARTUP_GRACE_SECONDS) -> dict:
    """
    Wait until the pipeline is actually capturing, and answer with its state.

    A composite pipeline takes a second or two to attach to the room. Stopping
    during that window is not "stop early", it is destructive: the worker
    aborts with "stop called before pipeline could start" and throws the take
    away, which is what hosts hit by pressing record and then stop to check it
    worked. Waiting here turns that into the stop they meant.

    Returning the last state seen (possibly empty) leaves the caller free to
    decide: it can stop from a terminal state, or stop anyway and report an
    abort it did not manage to avoid.
    """
    if not egress_id:
        return {}

    deadline = time.monotonic() + max(0.0, timeout)
    last: dict = {}
    while True:
        try:
            info = status(egress_id)
        except (RecordingUnavailable, RecordingRejected):
            # Unreachable, or the worker has no memory of this id. Either way
            # there is nothing to wait for; the caller's stop will say so.
            return last
        if not info:
            return last
        last = info
        if snapshot(info)["status"] not in ("starting", "unknown", ""):
            return info
        if time.monotonic() >= deadline:
            return last
        time.sleep(0.75)


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
    """
    A duration in whole seconds, whatever unit the worker reported.

    LiveKit's ``FileInfo.duration`` is an int64 of **nanoseconds**, so a 16
    second clip arrives as ``15922781899``. Stored as-is that is a number no
    client can read as seconds (and it overflows the integer column), which is
    why every recording used to show a nonsense length. Older Egress builds
    have posted milliseconds and microseconds instead, so anything that cannot
    plausibly be seconds is scaled by magnitude.
    """
    try:
        raw = float(value or 0)
    except (TypeError, ValueError):
        return None
    if raw <= 0:
        return None
    # Two days in seconds: a lecture longer than this was not measured in
    # seconds, so a finer unit is the only reading that makes sense.
    if raw <= 172800:
        return int(raw)
    seconds = raw / 1e9  # the documented unit
    if seconds < 1:
        # Older workers: milliseconds, then microseconds.
        if raw / 1e3 >= 1:
            seconds = raw / 1e3
        else:
            seconds = raw / 1e6
    return int(seconds)


# Public alias: a row written before the scaling above existed still holds
# nanoseconds, so the REST payload normalises it on the way out too.
as_seconds = _duration_seconds


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
        "error": friendly_error(info.get("error")),
        "started": started,
        "ended": ended,
        "duration": duration,
        "size": size,
    }
