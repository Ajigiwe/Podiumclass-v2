"""
Token-based authentication for the Podium API.

Issues real signed JWTs (PyJWT) so a token is an actual credential rather than
an identifier that can be forged. Tokens are self-contained and carry the
subject, role, and issued-at/expiry timestamps.

Two authentication paths exist and both resolve to the same User model:

  * Browser clients (Vue SPA) send ``Authorization: Bearer <jwt>``.
  * The external Laravel attendance service sends the server-to-server
    ``ATTENDANCE_API_KEY`` via ``X-API-Key`` or ``Authorization: Bearer``.

The service key intentionally does NOT resolve to a User; it authenticates the
peer service, and views that accept it must handle ``request.user`` being
anonymous and read the target user from explicit, validated parameters.
"""

from datetime import timedelta

import jwt
from django.conf import settings
from django.contrib.auth.models import AnonymousUser
from django.utils import timezone
from rest_framework import authentication, exceptions

from .models import User

# Distinct token types so a service key can never be replayed as a user token
# and vice versa.
TOKEN_TYPE_ACCESS = "access"
TOKEN_TYPE_SERVICE = "service"


def get_jwt_secret() -> str:
    """The HMAC secret. Falls back to Django's SECRET_KEY in dev."""
    return getattr(settings, "PODIUM_JWT_SECRET", None) or settings.SECRET_KEY


def get_token_ttl() -> timedelta:
    return timedelta(days=getattr(settings, "PODIUM_JWT_TTL_DAYS", 7))


def issue_token(user: User) -> str:
    """Create a signed access token for ``user``."""
    now = timezone.now()
    payload = {
        "sub": user.id,
        "email": user.email,
        "role": user.role,
        "typ": TOKEN_TYPE_ACCESS,
        "iat": int(now.timestamp()),
        "exp": int((now + get_token_ttl()).timestamp()),
        "iss": getattr(settings, "PODIUM_JWT_ISSUER", "podium-lms"),
    }
    return jwt.encode(payload, get_jwt_secret(), algorithm="HS256")


def decode_token(raw: str) -> dict:
    """
    Decode and validate a token, raising DRF-friendly exceptions on failure.

    Returns the payload dict. Raises ``AuthenticationFailed`` for any
    malformed, expired, or wrong-typed token.
    """
    try:
        payload = jwt.decode(
            raw,
            get_jwt_secret(),
            algorithms=["HS256"],
            issuer=getattr(settings, "PODIUM_JWT_ISSUER", "podium-lms"),
        )
    except jwt.ExpiredSignatureError:
        raise exceptions.AuthenticationFailed(
            {"code": "TOKEN_EXPIRED", "message": "Your session has expired. Please sign in again."}
        )
    except jwt.InvalidTokenError as exc:
        raise exceptions.AuthenticationFailed(
            {"code": "INVALID_TOKEN", "message": f"Invalid authentication token: {exc}"}
        )

    if payload.get("typ") != TOKEN_TYPE_ACCESS:
        raise exceptions.AuthenticationFailed(
            {"code": "INVALID_TOKEN_TYPE", "message": "Token is not an access credential."}
        )
    if not payload.get("sub"):
        raise exceptions.AuthenticationFailed(
            {"code": "INVALID_TOKEN", "message": "Token is missing a subject."}
        )
    return payload


class PodiumAuthentication(authentication.BaseAuthentication):
    """
    Authenticates the browser SPA via ``Authorization: Bearer <jwt>``.

    Also accepts the Laravel service key so the external attendance system can
    call user-facing endpoints. When the service key authenticates, no User is
    attached (``request.user`` stays anonymous); views that support the service
    key must resolve the target user explicitly.
    """

    keyword = "Bearer"

    def authenticate_header(self, request):
        # Lets DRF return 401 (not 403) for missing/expired credentials, so
        # clients can tell "not signed in" apart from "signed in but forbidden".
        return f'{self.keyword} realm="api"'

    def authenticate(self, request):
        header = authentication.get_authorization_header(request).decode("utf-8")
        if not header:
            return None

        parts = header.split()
        if len(parts) == 0:
            return None
        if parts[0].lower() != self.keyword.lower():
            # Not a Bearer scheme; let other authenticators try.
            return None
        if len(parts) != 2:
            raise exceptions.AuthenticationFailed(
                {"code": "MALFORMED_AUTHORIZATION", "message": "Authorization header must be 'Bearer <token>'."}
            )

        raw = parts[1].strip()
        if not raw:
            return None

        # Service-to-service key takes precedence and resolves to no user.
        expected_key = getattr(settings, "ATTENDANCE_API_KEY", None)
        if expected_key and raw == expected_key:
            return (AnonymousUser(), None)

        payload = decode_token(raw)
        user = self._resolve_user(payload["sub"])

        # The role claim is advisory only; always re-read authority from the
        # database so a demoted user loses access on their next request.
        return (user, payload)

    @staticmethod
    def _resolve_user(user_id: str) -> User:
        try:
            user = User.objects.get(pk=user_id)
        except User.DoesNotExist:
            raise exceptions.AuthenticationFailed(
                {"code": "USER_NOT_FOUND", "message": "The account for this token no longer exists."}
            )
        if not user.is_active:
            raise exceptions.AuthenticationFailed(
                {"code": "ACCOUNT_DISABLED", "message": "This account has been disabled."}
            )
        if user.status == "disabled":
            raise exceptions.AuthenticationFailed(
                {"code": "ACCOUNT_DISABLED", "message": "This account has been disabled."}
            )
        return user


class PodiumServiceKeyAuthentication(PodiumAuthentication):
    """
    Restricted authenticator for the external attendance API.

    Accepts ONLY the server-to-server key (as Bearer or ``X-API-Key``). This
    keeps the internal Laravel contract working while refusing browser JWTs on
    endpoints that are meant for machine-to-machine use.

    Returns HTTP 403 (not 401) for bad credentials: this is a server-to-server
    contract and the caller will not present a ``WWW-Authenticate`` challenge.
    """

    # No challenge header -> DRF reports 403 rather than 401.
    def authenticate_header(self, request):
        return None

    def authenticate(self, request):
        header = authentication.get_authorization_header(request).decode("utf-8")
        api_key_header = request.headers.get("X-API-Key") or ""

        token = None
        if header:
            parts = header.split()
            if len(parts) == 2 and parts[0].lower() == "bearer":
                token = parts[1].strip()
            elif len(parts) == 1:
                token = parts[0].strip()
        if not token and api_key_header:
            token = api_key_header.strip()

        if not token:
            raise exceptions.AuthenticationFailed(
                {"code": "MISSING_API_KEY", "message": "API key required."}
            )

        expected_key = getattr(settings, "ATTENDANCE_API_KEY", None)
        if not expected_key or token != expected_key:
            # Fail CLOSED. The previous code returned an authenticated anonymous
            # user when no key was configured, so a deployment that forgot to set
            # ATTENDANCE_API_KEY accepted ANY caller as the trusted service.
            raise exceptions.AuthenticationFailed(
                {"code": "INVALID_API_KEY", "message": "Invalid API key provided."}
            )
        return (AnonymousUser(), {"typ": TOKEN_TYPE_SERVICE})
