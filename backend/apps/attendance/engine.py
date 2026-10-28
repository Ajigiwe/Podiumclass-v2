"""
Automated attendance checker engine.

Shared by two consumers:

* ``VerificationTriggerView`` (manual / in-classroom trigger over HTTP), and
* the ``run_attendance_checker`` management command, which fires the checks
  server-side so a class keeps getting its checks even when no moderator's
  browser tab is awake.

The engine is deliberately framework-free: pure Django models, no request
object, so both callers go through the exact same creation and counter logic.
"""

import uuid
import logging

from django.db import models as db_models
from django.utils import timezone

from .models import Attendance, AttendanceLog, Verification

logger = logging.getLogger(__name__)

# Defaults mirrored from the classroom UI (ClassroomView.vue).
DEFAULT_CHECK_COUNT = 3
DEFAULT_TIME_LIMIT_SECONDS = 30
DEFAULT_INTERVAL_MINUTES = 15


def parse_auto_settings(session):
    """
    Normalise a session's persisted checker configuration.

    ``verification_count`` (flat column) and ``auto_attendance_settings``
    (JSON blob) may both carry config; the JSON blob wins field by field,
    matching how the classroom UI merges them on join.
    """
    check_count = session.verification_count or DEFAULT_CHECK_COUNT
    time_limit = DEFAULT_TIME_LIMIT_SECONDS
    interval = DEFAULT_INTERVAL_MINUTES
    enabled = False

    auto = getattr(session, "auto_attendance_settings", None) or {}
    if isinstance(auto, dict):
        if auto.get("checkCount"):
            check_count = auto["checkCount"]
        if auto.get("timeLimitSeconds"):
            time_limit = int(auto["timeLimitSeconds"])
        if auto.get("intervalMinutes"):
            interval = int(auto["intervalMinutes"])
        if isinstance(auto.get("enabled"), bool):
            enabled = auto["enabled"]

    return {
        "enabled": enabled,
        "check_count": int(check_count),
        "time_limit_seconds": max(5, int(time_limit)),
        "interval_minutes": max(1, int(interval)),
    }


def checks_completed(session):
    """How many verifications have been triggered for this session so far."""
    return Verification.objects.filter(session=session).count()


def last_triggered_at(session):
    """When the most recent verification for this session was triggered."""
    latest = Verification.objects.filter(session=session).order_by(
        "-triggered_at", "-id"
    ).first()
    return latest.triggered_at if latest else None


def next_check_due_at(session, settings=None):
    """
    When the scheduler should fire this session's next check, given its last
    one. First check is due one interval after the session started (falling
    back to creation time for sessions that never recorded started_at).
    """
    settings = settings or parse_auto_settings(session)
    anchor = last_triggered_at(session) or session.started_at or session.created_at
    if anchor is None:
        return None
    from datetime import timedelta
    return anchor + timedelta(minutes=settings["interval_minutes"])


def trigger_verification(session, triggered_by, time_limit_seconds=None):
    """
    Create a Verification row for *session* and bump the sent-counters for
    every attendee. Returns the created Verification.

    This is the single write path for checks: manual triggers and the
    scheduler cannot drift apart.
    """
    from datetime import timedelta

    now = timezone.now()
    time_limit = int(time_limit_seconds) if time_limit_seconds else None
    if not time_limit or time_limit < 5:
        settings = parse_auto_settings(session)
        time_limit = settings["time_limit_seconds"]

    verif_count = Verification.objects.filter(session=session).count() + 1
    verif = Verification.objects.create(
        id=f"verif_{uuid.uuid4().hex[:16]}",
        session=session,
        verification_number=verif_count,
        triggered_by=triggered_by,
        triggered_at=now,
        expires_at=now + timedelta(seconds=time_limit),
        time_limit_seconds=time_limit,
    )

    Attendance.objects.filter(session=session).update(
        total_verifications_sent=db_models.F("total_verifications_sent") + 1
    )
    AttendanceLog.objects.filter(session=session).update(
        total_verifications_sent=db_models.F("total_verifications_sent") + 1
    )

    logger.info(
        "Attendance verification #%s triggered for session %s by %s (expires %s)",
        verif.verification_number,
        session.id,
        triggered_by,
        verif.expires_at.isoformat(),
    )
    return verif
