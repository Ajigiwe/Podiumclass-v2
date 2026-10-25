from django.conf import settings
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.permissions import IsAdmin
from . import features
from .models import SystemSetting
from .registry import (
    SETTING_GROUPS,
    SETTINGS,
    SETTINGS_BY_KEY,
    FEATURES_BY_KEY,
    feature_storage_key,
)
from .serializers import SettingWriteSerializer


def _setting_payload(key: str) -> dict:
    """Merge registry metadata with the live row for one setting key."""
    spec = SETTINGS_BY_KEY[key]
    row = SystemSetting.objects.filter(key=key).first()
    is_secret = bool(spec.get("secret"))
    raw = row.value if row else ""
    return {
        "key": key,
        "group": spec["group"],
        "label": spec["label"],
        "type": spec.get("type", "text"),
        "help": spec.get("help", ""),
        "secret": is_secret,
        # Secrets are never echoed to the client; isSet tells the UI whether a
        # value is configured so it can render a "configured" state instead.
        "value": None if is_secret else raw,
        "isSet": bool(str(raw).strip()),
        "updatedAt": row.updated_at.isoformat() if row else None,
    }


class FeatureFlagsView(APIView):
    """
    GET /api/v1/core/features

    Public, unauthenticated map of enabled feature flags so the SPA can hide
    surfaces that are turned off (e.g. hide the Wallet nav when
    ``wallet_payments`` is disabled) without waiting for a privileged call.

    Returns only the boolean state, never configuration or secrets.
    ``devLogin`` is also a plain boolean: it tells the login page whether the
    backend will accept a credential-less POST at all, so the DEV quick-access
    buttons never appear (and fail) on a server that has it switched off.
    """

    permission_classes = [AllowAny]

    def get(self, request):
        return Response({
            "features": features.public_flags(),
            # The same effective test the view uses, so the login page never
            # offers buttons this deployment would refuse.
            "devLogin": settings.DEV_LOGIN_ENABLED and settings.DEBUG,
        })


class AdminSettingsView(APIView):
    """
    GET /api/v1/core/settings

    Admin-only full configuration: setting groups, every setting (secrets
    masked), and every feature flag with live state. This is what the modular
    settings page renders from.
    """

    permission_classes = [IsAuthenticated, IsAdmin]

    def get(self, request):
        settings_payload = [_setting_payload(s["key"]) for s in SETTINGS]
        return Response({
            "groups": SETTING_GROUPS,
            "settings": settings_payload,
            "featureGroups": features.groups(),
            "features": features.get_all(),
        })


class SettingDetailView(APIView):
    """
    PATCH /api/v1/core/settings/<key>

    Admin-only update of a single setting. The key must be a registered
    setting; unknown keys are rejected so a typo cannot create an orphaned row.
    """

    permission_classes = [IsAuthenticated, IsAdmin]

    def patch(self, request, key):
        spec = SETTINGS_BY_KEY.get(key)
        if spec is None:
            return Response(
                {"code": "UNKNOWN_SETTING", "message": f"'{key}' is not a configurable setting."},
                status=status.HTTP_404_NOT_FOUND,
            )

        serializer = SettingWriteSerializer(data=request.data, context={"spec": spec})
        serializer.is_valid(raise_exception=True)
        new_value = serializer.validated_data["value"]

        # For secrets, a blank submission means "leave unchanged" so saving the
        # form does not wipe a stored secret that was masked from the UI.
        if spec.get("secret") and not str(new_value).strip():
            return Response(_setting_payload(key))

        row, _ = SystemSetting.objects.update_or_create(
            key=key,
            defaults={
                "value": str(new_value),
                "description": spec.get("label", key),
                "is_secret": bool(spec.get("secret")),
            },
        )
        return Response(_setting_payload(key))


class FeatureToggleView(APIView):
    """
    PATCH /api/v1/core/features/<key>

    Admin-only toggle of a single feature flag. Persists to ``SystemSetting``
    under the ``feature_`` prefix.
    """

    permission_classes = [IsAuthenticated, IsAdmin]

    def patch(self, request, key):
        if key not in FEATURES_BY_KEY:
            return Response(
                {"code": "UNKNOWN_FEATURE", "message": f"'{key}' is not a known feature flag."},
                status=status.HTTP_404_NOT_FOUND,
            )

        enabled_raw = request.data.get("enabled")
        if enabled_raw is None:
            return Response(
                {"code": "VALIDATION_ERROR", "message": "'enabled' (true/false) is required."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        enabled = str(enabled_raw).strip().lower() in ("1", "true", "yes", "on")

        features.set_enabled(key, enabled)
        return Response(features.serialize_one(key))
