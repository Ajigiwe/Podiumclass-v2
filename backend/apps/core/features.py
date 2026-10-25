"""
Runtime state for feature flags, backed by ``SystemSetting``.

The registry (:mod:`apps.core.registry`) says which flags EXIST; this module
tracks whether each flag is currently ON. State lives in ``SystemSetting`` rows
under a ``feature_`` prefix so flag keys can never collide with configuration
keys.

A flag that has never been persisted reads as its registry default. That keeps
a freshly-seeded database correct without forcing every flag to be materialised
up front, and it means deleting a row reverts the flag to its default rather
than silently enabling it.
"""

from typing import Dict, Iterable, List

from .models import SystemSetting
from .registry import (
    FEATURES,
    FEATURES_BY_KEY,
    FEATURE_GROUPS,
    feature_storage_key,
)


def _parse_bool(raw) -> bool:
    """Interpret a stored string as a boolean, defaulting to False."""
    return str(raw).strip().lower() in ("1", "true", "yes", "on")


def is_enabled(key: str) -> bool:
    """
    Return whether a feature flag is currently enabled.

    Falls back to the registry default when no row exists yet, so an unknown or
    unsaved flag resolves to its documented default instead of an error.
    """
    spec = FEATURES_BY_KEY.get(key)
    default = bool(spec["default"]) if spec else False
    raw = SystemSetting.get_setting(feature_storage_key(key))
    if raw is None:
        return default
    return _parse_bool(raw)


def is_enabled_map(keys: Iterable[str]) -> Dict[str, bool]:
    """Resolve several flags at once into a ``{key: bool}`` mapping."""
    return {key: is_enabled(key) for key in keys}


def get_all() -> List[dict]:
    """
    Full list of feature specs, each annotated with its live ``enabled`` state.

    Ordered by the registry, grouped for the admin UI.
    """
    return [
        {**spec, "enabled": is_enabled(spec["key"])}
        for spec in FEATURES
    ]


def serialize_one(key: str) -> dict:
    """Single feature spec annotated with its live ``enabled`` state."""
    return {**FEATURES_BY_KEY[key], "enabled": is_enabled(key)}


def set_enabled(key: str, enabled: bool) -> dict:
    """
    Persist a feature flag's state, creating the row if needed.

    Raises ``KeyError`` for an unregistered flag so a typo cannot silently
    create an orphaned setting.
    """
    if key not in FEATURES_BY_KEY:
        raise KeyError(key)
    SystemSetting.objects.update_or_create(
        key=feature_storage_key(key),
        defaults={
            "value": "true" if enabled else "false",
            "description": FEATURES_BY_KEY[key]["label"],
            "is_secret": False,
        },
    )
    return {**FEATURES_BY_KEY[key], "enabled": bool(enabled)}


def groups() -> List[dict]:
    """Feature groups in registry order, for a modular settings layout."""
    return list(FEATURE_GROUPS)


def public_flags() -> Dict[str, bool]:
    """
    Minimal ``{key: enabled}`` map safe to expose to unauthenticated clients.

    Deliberately omits labels/descriptions: the SPA only needs to know whether
    a surface is available, and this keeps the public contract small.
    """
    return {spec["key"]: is_enabled(spec["key"]) for spec in FEATURES}


def seed_all() -> int:
    """
    Materialise every registered flag in the database at its default value.

    Only creates rows that are missing -- an existing (possibly customised)
    flag is left untouched so re-running a seed command never clobbers an
    admin's choices. Returns the number of rows created.
    """
    created = 0
    for spec in FEATURES:
        _, was_created = SystemSetting.objects.get_or_create(
            key=feature_storage_key(spec["key"]),
            defaults={
                "value": "true" if spec["default"] else "false",
                "description": spec["label"],
                "is_secret": False,
            },
        )
        if was_created:
            created += 1
    return created
