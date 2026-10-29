"""
Resolution of the LiveKit endpoint clients are told to connect to.

Every join answers with this URL, and the client SDKs turn a bad value into the
same unhelpful message -- ``expected url scheme 'https' but no scheme was found
for pending`` -- which reads as a broken app rather than as a missing setting.
The value can go wrong in four ordinary ways, so all four are handled here
instead of in each client:

* a placeholder (``pending``, ``tbd``, ...) left in the settings form;
* a bare hostname with no scheme (``myproject.livekit.cloud``);
* the development default ``ws://localhost:7880`` reaching a deployed server;
* ``http://`` / ``ws://`` where the page is served over TLS, which the browser
  would block as mixed content.

Anything this module cannot turn into a usable endpoint is reported as "not
configured" so the caller can answer with one clear message.
"""

from urllib.parse import urlsplit, urlunsplit

from django.conf import settings

from .models import SystemSetting

#: Values an operator leaves in a field to mean "not filled in yet". They parse
#: as a hostname, so without this list a placeholder would be accepted and then
#: fail on every device.
PLACEHOLDERS = frozenset({
    "",
    "pending",
    "tbd",
    "todo",
    "changeme",
    "change-me",
    "placeholder",
    "none",
    "null",
    "n/a",
    "na",
    "unset",
    "example",
    "example.com",
    "your-project.livekit.cloud",
})

#: Hosts that mean "this machine". A client on another device resolves them to
#: itself, so they are only usable as-is when the request came from such a host.
LOOPBACK_HOSTS = frozenset({"localhost", "127.0.0.1", "0.0.0.0", "::1"})

VALID_SCHEMES = frozenset({"ws", "wss", "http", "https"})


def _request_host(request) -> str:
    """The host the client actually reached, or '' when it cannot be read."""
    if request is None:
        return ""
    try:
        host = (request.get_host() or "").split(":")[0].strip().lower()
    except Exception:  # noqa: BLE001 - DisallowedHost must not break a join
        return ""
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    return host


def normalize_livekit_url(raw, request=None) -> str:
    """
    Turn a stored setting into a URL clients can use, or '' if unusable.

    ``request`` is optional: the token endpoint passes it so a loopback host can
    be rewritten to the address the client already reached (which is what makes
    a LAN development server work from a phone), while a settings save passes
    nothing and only gets validation and normalisation.
    """
    value = str(raw or "").strip().strip("\"'").strip()
    if value.casefold() in PLACEHOLDERS:
        return ""

    # A bare hostname is the most common paste from the LiveKit dashboard,
    # which shows the project URL without a scheme.
    if "://" not in value:
        value = f"wss://{value}"

    parts = urlsplit(value)
    scheme = parts.scheme.lower()
    host = (parts.hostname or "").strip().lower()
    if scheme not in VALID_SCHEMES or not host:
        return ""

    port = parts.port or None
    # None means "no request to judge it by" (a settings save): treat that like a
    # TLS page, because that is what every deployment serves.
    tls_page = True if request is None else request.is_secure()

    if host in LOOPBACK_HOSTS:
        reached = _request_host(request)
        if reached and reached not in LOOPBACK_HOSTS:
            # A deployed server advertising localhost: point clients at the host
            # they already used to reach the API, keeping the LiveKit port.
            host = reached
            port = port or 7880
            if tls_page:
                scheme = "wss"
            elif scheme in ("https", "http"):
                scheme = "ws"

    # A TLS page cannot open a plaintext websocket, and LiveKit Cloud only
    # speaks TLS. Only upgrade when the page itself is TLS: a LAN development
    # server runs on http and its LiveKit speaks plain ws.
    if host not in LOOPBACK_HOSTS and tls_page:
        if scheme == "http":
            scheme = "https"
        elif scheme == "ws":
            scheme = "wss"

    netloc = host if port is None else f"{host}:{port}"
    return urlunsplit((scheme, netloc, parts.path.rstrip("/"), "", ""))


def livekit_credentials_ready() -> bool:
    """
    Whether the API key/secret that sign room tokens are real.

    A placeholder key signs a token that LiveKit rejects during connect, which
    the client reports as a generic "could not connect". Checking here lets the
    join answer say which setting is wrong instead.
    """
    for value in (settings.LIVEKIT_API_KEY, settings.LIVEKIT_API_SECRET):
        cleaned = str(value or "").strip()
        if not cleaned or cleaned.casefold() in PLACEHOLDERS:
            return False
        if cleaned.startswith("dev-only-"):
            return False
    return True


def resolve_livekit_url(request=None) -> str:
    """
    The effective LiveKit URL: the stored setting, else the environment default.

    A stored placeholder must not shadow a real environment value -- that is
    exactly how a working ``LIVEKIT_URL`` ends up unused and every join fails --
    so each candidate is normalised and the first usable one wins.
    """
    candidates = [
        SystemSetting.get_setting("livekit_url", ""),
        getattr(settings, "LIVEKIT_URL", ""),
    ]
    for candidate in candidates:
        url = normalize_livekit_url(candidate, request=request)
        if url:
            return url
    return ""
