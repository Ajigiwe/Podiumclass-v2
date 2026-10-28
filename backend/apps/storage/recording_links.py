"""
Short-lived URLs that let a recording be watched or downloaded.

Why this exists: the recording file endpoint authenticates with an
``Authorization: Bearer`` header, and **no media element can send one**. A
``<video src="...">``, a download link and Android's DownloadManager all fetch
the URL themselves with no header at all, so the file endpoint answered 401 and
nothing ever played -- not in the classroom, not from a share link.

A signed URL carries its credential in the query string instead, so the plain
fetch works, and it streams with ``Range`` support rather than being buffered in
memory (a lecture is easily hundreds of megabytes).

Scope is deliberately narrow. A token names one recording, one session, and one
purpose:

``play``
    Inline. Whoever may watch the replay in the classroom may mint one, and it
    is what the player uses.
``download``
    An attachment, so a click saves the file. Only the people who run the class
    may mint one -- downloading and sharing a lecture recording is the host's
    call, not a participant's.

Tokens are signed with the same secret as the access tokens but with their own
issuer and ``typ``, so a recording link can never be presented as a session
credential, and they expire quickly if one leaks into a chat log.
"""

import time

import jwt
from django.conf import settings

from apps.accounts.auth import get_jwt_secret

#: Issuer claim. Distinct from the access tokens' issuer so the two families can
#: never be confused for one another.
LINK_ISSUER = "podium-recordings"

MODE_PLAY = "play"
MODE_DOWNLOAD = "download"

#: How long a link stays valid. Long enough for a class to watch a replay and for
#: a download to finish, short enough that pasting it around is not a permanent
#: grant.
LINK_TTL_SECONDS = 60 * 60 * 6


def mint(session_id: str, recording_id: str, mode: str, ttl_seconds: int = LINK_TTL_SECONDS) -> str:
    """Sign a URL credential for one recording."""
    now = int(time.time())
    return jwt.encode(
        {
            "iss": LINK_ISSUER,
            "typ": "recording_link",
            "sid": session_id,
            "rid": recording_id,
            "mode": mode,
            "iat": now,
            "exp": now + ttl_seconds,
        },
        get_jwt_secret(),
        algorithm="HS256",
    )


def verify(raw: str, session_id: str, recording_id: str):
    """
    Check a link credential, returning its payload or ``None``.

    Returns ``None`` for anything that is not a valid, unexpired link for
    exactly this recording: expired, tampered with, minted for a different
    recording, or an ordinary access token.
    """
    try:
        payload = jwt.decode(
            raw,
            get_jwt_secret(),
            algorithms=["HS256"],
            issuer=LINK_ISSUER,
        )
    except jwt.InvalidTokenError:
        return None

    if payload.get("typ") != "recording_link":
        return None
    if payload.get("sid") != session_id or payload.get("rid") != recording_id:
        return None
    if payload.get("mode") not in (MODE_PLAY, MODE_DOWNLOAD):
        return None
    return payload


def file_url(request, session_id: str, recording_id: str, mode: str):
    """Absolute, signed URL for one recording in *mode*."""
    token = mint(session_id, recording_id, mode)
    path = f"/api/v1/classrooms/sessions/{session_id}/recordings/{recording_id}/file/?t={token}"
    return request.build_absolute_uri(path) if request is not None else path


def content_disposition(filename: str, mode: str) -> str:
    """
    ``inline`` for playing, ``attachment`` for saving.

    The download disposition is the whole difference between \"watch this in the
    player\" and \"save this file\", and it is carried by the link that was minted
    for it -- so a play link cannot be turned into a download by editing it.
    """
    verb = "attachment" if mode == MODE_DOWNLOAD else "inline"
    safe = filename.replace('"', "")
    return f'{verb}; filename="{safe}"'


def is_configured() -> bool:
    """Whether a secret exists to sign with (never false in a real deployment)."""
    return bool(getattr(settings, "PODIUM_JWT_SECRET", None) or settings.SECRET_KEY)
