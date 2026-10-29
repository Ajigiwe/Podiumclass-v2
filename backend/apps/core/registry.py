"""
Declarative registry for platform settings and feature flags.

Division of responsibility:

  * ``SystemSetting`` (the database) stores RUNTIME STATE -- the current value
    of each setting and the on/off state of each feature flag.
  * This module is the source of truth for WHAT can be configured and WHAT can
    be toggled, together with the metadata a modular admin UI needs to render
    itself: labels, help text, grouping, and input types.

Keeping the schema in code (rather than adding columns to ``SystemSetting``)
means adding or changing a setting is a reviewable code change, and the UI
never has to guess how to render a field -- it is told the type explicitly.
"""

# --------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------

# Ordered list of configuration groups. A settings page renders one module
# (section/panel) per group, so grouping here directly drives the page layout.
SETTING_GROUPS = [
    {
        "key": "access",
        "label": "Access & Identity",
        "icon": "fas fa-user-shield",
        "description": "Sign-in, OAuth credentials, and the root administrator.",
    },
    {
        "key": "realtime",
        "label": "Realtime Classrooms",
        "icon": "fas fa-broadcast-tower",
        "description": "LiveKit WebRTC gateway used for live video sessions.",
    },
    {
        "key": "integrations",
        "label": "External Integrations",
        "icon": "fas fa-plug",
        "description": "Server-to-server keys for the attendance service and object storage.",
    },
    {
        "key": "commerce",
        "label": "Commerce & Billing",
        "icon": "fas fa-wallet",
        "description": "Wallet top-up limits and automatic refund policy.",
    },
]

# Input types the settings UI knows how to render.
#   text | email | url | secret | integer | boolean
#
# A spec may also carry ``readOnly``: the setting is displayed for reference but
# cannot be written through the API, and ``env`` names the environment variable
# an operator changes instead. Use it for anything the runtime reads only from
# the environment, so the UI never offers an edit that would do nothing.
SETTINGS = [
    # -- access -----------------------------------------------------------
    {
        "key": "admin_email",
        "group": "access",
        "label": "Superadmin Email",
        "type": "email",
        "secret": False,
        "help": "Primary root administrator address.",
    },
    {
        "key": "google_client_id",
        "group": "access",
        "label": "Google Client ID",
        "type": "text",
        "secret": False,
        "help": "OAuth client ID from the Google Cloud console.",
    },
    {
        "key": "google_client_secret",
        "group": "access",
        "label": "Google Client Secret",
        "type": "secret",
        "secret": True,
        "help": "OAuth client secret. Never returned to the browser in clear text.",
    },
    # -- realtime ---------------------------------------------------------
    # Only livekit_url is honoured from the database: resolve_livekit_url()
    # reads it before falling back to LIVEKIT_URL. The API key and secret are
    # NOT -- generate_livekit_token() and livekit_credentials_ready() read the
    # environment directly, because they sign tokens that grant entry to any
    # room. They are listed here read-only so the page can show which key is in
    # use; editing them used to look like it worked and silently changed
    # nothing, which cost real debugging time on failing joins.
    {
        "key": "livekit_url",
        "group": "realtime",
        "label": "LiveKit Server URL",
        "type": "url",
        "secret": False,
        "help": "Secure websocket endpoint clients connect to.",
    },
    {
        "key": "livekit_api_key",
        "group": "realtime",
        "label": "LiveKit API Key",
        "type": "text",
        "secret": False,
        "readOnly": True,
        "env": "LIVEKIT_API_KEY",
        "help": "Read-only. Set the LIVEKIT_API_KEY environment variable on the server.",
    },
    {
        "key": "livekit_api_secret",
        "group": "realtime",
        "label": "LiveKit API Secret",
        "type": "secret",
        "secret": True,
        "readOnly": True,
        "env": "LIVEKIT_API_SECRET",
        "help": (
            "Read-only. Set the LIVEKIT_API_SECRET environment variable on the "
            "server; it is never returned to a browser."
        ),
    },
    # -- integrations -----------------------------------------------------
    {
        "key": "attendance_api_key",
        "group": "integrations",
        "label": "Attendance API Key",
        "type": "secret",
        "secret": True,
        "help": "Server-to-server key the external Laravel service uses. Never sent to browsers.",
    },
    {
        "key": "attendance_rate_limit",
        "group": "integrations",
        "label": "Attendance Rate Limit",
        "type": "integer",
        "secret": False,
        "help": "Maximum attendance API requests allowed per minute.",
    },
    {
        "key": "minio_endpoint",
        "group": "integrations",
        "label": "Storage Endpoint",
        "type": "text",
        "secret": False,
        "help": "MinIO / S3 compatible object storage host.",
    },
    {
        "key": "minio_access_key",
        "group": "integrations",
        "label": "Storage Access Key",
        "type": "text",
        "secret": False,
        "help": "Object storage access key.",
    },
    {
        "key": "minio_secret_key",
        "group": "integrations",
        "label": "Storage Secret Key",
        "type": "secret",
        "secret": True,
        "help": "Object storage secret key. Never returned to the browser in clear text.",
    },
    # -- commerce ---------------------------------------------------------
    {
        "key": "min_topup_amount",
        "group": "commerce",
        "label": "Minimum Top-Up",
        "type": "integer",
        "secret": False,
        "help": "Smallest permitted wallet top-up, in pesewas (100 = GHS 1.00).",
    },
    {
        "key": "refund_threshold_minutes",
        "group": "commerce",
        "label": "Refund Threshold",
        "type": "integer",
        "secret": False,
        "help": "Minimum lecture duration (minutes) before an automatic refund is blocked.",
    },
]

SETTINGS_BY_KEY = {s["key"]: s for s in SETTINGS}

# --------------------------------------------------------------------------
# Feature flags
# --------------------------------------------------------------------------

FEATURE_GROUPS = [
    {
        "key": "learning",
        "label": "Learning & Classrooms",
        "icon": "fas fa-chalkboard-teacher",
        "description": "Live teaching surface: video, sharing, and in-room interaction.",
    },
    {
        "key": "assessment",
        "label": "Assessment & Attendance",
        "icon": "fas fa-clipboard-check",
        "description": "Presence tracking and the external attendance integration.",
    },
    {
        "key": "commerce",
        "label": "Commerce & Billing",
        "icon": "fas fa-coins",
        "description": "Paid sessions and wallet payments.",
    },
    {
        "key": "access",
        "label": "Access",
        "icon": "fas fa-key",
        "description": "How users are allowed to sign in.",
    },
]

# Each feature maps to a real code path. ``critical`` flags disable a whole
# surface and are called out in the UI; the rest are enhancements.
FEATURES = [
    {
        "key": "classroom_live",
        "group": "learning",
        "label": "Live Classrooms",
        "description": "Real-time LiveKit video sessions. Disabling this blocks token issuance and live rooms.",
        "default": True,
        "critical": True,
        "icon": "fas fa-video",
    },
    {
        "key": "screen_share",
        "group": "learning",
        "label": "Screen Sharing",
        "description": "Allow participants to present their screen to the room.",
        "default": True,
        "critical": False,
        "icon": "fas fa-desktop",
    },
    {
        "key": "chat_reactions",
        "group": "learning",
        "label": "Chat & Reactions",
        "description": "In-room text chat and emoji reactions.",
        "default": True,
        "critical": False,
        "icon": "fas fa-comments",
    },
    {
        "key": "recordings",
        "group": "learning",
        "label": "Session Recordings",
        "description": "Record and replay classroom sessions.",
        "default": False,
        "critical": False,
        "icon": "fas fa-circle",
    },
    {
        "key": "attendance_verification",
        "group": "assessment",
        "label": "Attendance Verification",
        "description": "Hosts can push timed presence checks to students during a session.",
        "default": True,
        "critical": False,
        "icon": "fas fa-tasks",
    },
    {
        "key": "external_attendance_api",
        "group": "assessment",
        "label": "External Attendance API",
        "description": "Expose the v1 attendance contract to the external Laravel service.",
        "default": True,
        "critical": False,
        "icon": "fas fa-server",
    },
    {
        "key": "wallet_payments",
        "group": "commerce",
        "label": "Wallet & Payments",
        "description": "Wallet balance, paid sessions, and billing endpoints.",
        "default": True,
        "critical": True,
        "icon": "fas fa-wallet",
    },
    {
        "key": "google_signin",
        "group": "access",
        "label": "Google Sign-In",
        "description": "Allow users to authenticate with their Google account.",
        "default": True,
        "critical": True,
        "icon": "fab fa-google",
    },
]

FEATURES_BY_KEY = {f["key"]: f for f in FEATURES}

# Settings whose value is a feature flag, so key spaces never collide.
FEATURE_KEY_PREFIX = "feature_"


def feature_storage_key(key: str) -> str:
    """``SystemSetting`` row key for a feature flag."""
    return f"{FEATURE_KEY_PREFIX}{key}"
