import time
import json
import logging

import jwt
import requests
from django.conf import settings

logger = logging.getLogger(__name__)

def generate_livekit_token(
    room_name: str,
    participant_name: str,
    participant_id: str = None,
    role: str = "student",
    user_id: str = None,
    photo_url: str = None,
    ttl_seconds: int = 86400  # 24 hours
) -> str:
    """
    Generates a LiveKit access token with VideoGrant permissions according to user role.
    Lecturers get moderator/roomAdmin privileges.
    Students get interactive subscriber privileges.
    """
    # Signing material comes only from the environment (via settings). The
    # previous literals were real production credentials: anyone with the source
    # could mint a token for any room, on any deployment using the same keys.
    api_key = settings.LIVEKIT_API_KEY
    api_secret = settings.LIVEKIT_API_SECRET

    if (
        not api_key
        or not api_secret
        or str(api_key).startswith("dev-only-")
        or str(api_secret).startswith("dev-only-")
    ):
        raise RuntimeError(
            "LIVEKIT_API_KEY / LIVEKIT_API_SECRET are not configured; "
            "cannot issue room tokens."
        )
    
    identity = participant_id or f"user_{int(time.time() * 1000)}"
    now = int(time.time())

    # 'admin' belongs here with the hosts: the rest of the backend already
    # treats an admin as the session's authority (see classrooms.access), and
    # LiveKit decides on the grant, not on our opinion. Leaving admin out issued
    # a student-shaped token to an administrator hosting a class, so every
    # LiveKit-side room action they took -- moderation, clearing the room -- was
    # refused as unauthenticated.
    is_lecturer = role.lower() in ("lecturer", "host", "admin")

    video_grant = {
        "room": room_name,
        "roomJoin": True,
        "canSubscribe": True,
        "canPublishData": True,
        "canPublish": True,
        "canPublishSources": ["camera", "microphone", "screen_share", "screen_share_audio"],
        "roomAdmin": is_lecturer,
        "roomCreate": is_lecturer,
        "canUpdateOwnMetadata": is_lecturer,
    }

    metadata = json.dumps({
        "role": role,
        "userId": user_id or identity,
        "name": participant_name,
        "photoURL": photo_url or None,
    })

    payload = {
        "iss": api_key,
        "sub": identity,
        "name": participant_name,
        "metadata": metadata,
        "nbf": now - 5,
        "exp": now + ttl_seconds,
        "video": video_grant,
    }

    token = jwt.encode(payload, api_secret, algorithm="HS256")
    return token, identity


def livekit_api_url() -> str:
    """Base HTTP(S) URL of the LiveKit server, or '' when it is not configured."""
    return (getattr(settings, "LIVEKIT_API_URL", "") or "").rstrip("/")


def delete_livekit_room(room_name: str) -> bool:
    """
    Delete a LiveKit room, which disconnects everyone inside it.

    Ending a class has to clear the room, not just flip a flag: each participant
    holds their own WebRTC session, so a database-only "end" left the room live
    and a student could keep talking in a lecture the host had finished -- and
    simply walk back in.

    Best effort: a LiveKit server that is down or misconfigured must never stop
    a host from ending their own class. Returns whether the room was removed.
    """
    base = livekit_api_url()
    if not base or not room_name:
        return False
    try:
        # roomAdmin on this room is what DeleteRoom checks.
        token, _ = generate_livekit_token(
            room_name=room_name,
            participant_name="podium-server",
            participant_id="podium-server",
            role="lecturer",
            ttl_seconds=60,
        )
        response = requests.post(
            f"{base}/twirp/livekit.RoomService/DeleteRoom",
            json={"room": room_name},
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            timeout=5,
        )
        if response.status_code == 200:
            logger.info("LiveKit room %s deleted", room_name)
            return True
        logger.warning(
            "LiveKit refused to delete room %s: %s %s",
            room_name,
            response.status_code,
            response.text[:200],
        )
        return False
    except Exception as exc:  # noqa: BLE001 - never fail the caller's request
        logger.warning("LiveKit room %s could not be deleted: %s", room_name, exc)
        return False
