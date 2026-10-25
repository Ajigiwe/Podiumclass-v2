import time
import json
import jwt
from django.conf import settings

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

    is_lecturer = (role.lower() == "lecturer" or role.lower() == "host")

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
