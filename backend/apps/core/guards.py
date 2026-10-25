"""
Feature-flag guards for enforcing toggles at the endpoint level.

A flag is only meaningful if turning it off actually stops the behaviour. These
helpers are called at the top of the views a flag governs and raise a standard
``FEATURE_DISABLED`` error (HTTP 503) when the flag is off, so the API fails
closed rather than silently ignoring the toggle.
"""

from rest_framework.exceptions import APIException

from . import features


class FeatureDisabled(APIException):
    """503: the requested capability is switched off by an administrator."""

    status_code = 503
    default_detail = {"code": "FEATURE_DISABLED", "message": "This feature is currently disabled."}
    default_code = "feature_disabled"


def require_feature(key: str) -> None:
    """
    Raise ``FeatureDisabled`` (503) unless flag ``key`` is enabled.

    Usage::

        require_feature("classroom_live")
    """
    if not features.is_enabled(key):
        spec = features.FEATURES_BY_KEY.get(key, {})
        label = spec.get("label", key)
        raise FeatureDisabled({
            "code": "FEATURE_DISABLED",
            "message": f"{label} is currently disabled by an administrator.",
        })
