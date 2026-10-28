"""
Background scheduler for the automated attendance checker.

Runs as a standalone process -- ``python manage.py run_attendance_checker`` --
next to the Django backend. Every few seconds it scans active classroom
sessions and fires each session's due verification via the shared engine, so
checks keep happening even when every moderator's browser tab is asleep,
backgrounded, or closed entirely.

The engine is the single write path: manual triggers from the classroom UI
and scheduler firings produce identical rows and counter updates.

One scheduler process per deployment is assumed; the loop is deliberately
light (two small queries per active session) and safe to re-run because a
check's due time is derived from the last persisted Verification, never from
in-memory state. On a restart, an already-open interval may fire once late
without double-firing the same check.

Wire it up for the stack:
    docker compose / Procfile entry:
        python manage.py run_attendance_checker --interval 5
"""

import logging
import time
from datetime import timedelta

from django.core.management.base import BaseCommand
from django.utils import timezone

from apps.attendance import engine
from apps.classrooms.models import Session

logger = logging.getLogger(__name__)


class Command(BaseCommand):
    help = "Fire automated attendance checks for active sessions on a server-side schedule."

    def add_arguments(self, parser):
        parser.add_argument(
            "--interval",
            type=int,
            default=5,
            help="Seconds between scans (default 5).",
        )
        parser.add_argument(
            "--once",
            action="store_true",
            help="Run a single scan and exit (used by tests and health checks).",
        )

    def handle(self, *args, **options):
        interval = max(1, options["interval"])
        self.stdout.write(
            f"Attendance checker scheduler running: scanning every {interval}s."
        )
        while True:
            fired = self.scan()
            if fired:
                self.stdout.write(f"Fired {fired} verification check(s).")
            if options["once"]:
                return
            time.sleep(interval)

    def scan(self):
        """
        One pass over every live session with the checker enabled.

        Returns the number of verifications triggered, so the loop can log
        activity and unit tests can assert on it without sleeping.
        """
        now = timezone.now()
        live_sessions = (
            Session.objects
            .filter(is_active=True, is_deleted=False, ended_at__isnull=True)
            .exclude(status__in=("ended", "deleted", "scheduled"))
        )

        fired = 0
        for session in live_sessions:
            settings = engine.parse_auto_settings(session)
            if not settings["enabled"]:
                continue
            if engine.checks_completed(session) >= settings["check_count"]:
                continue

            due_at = engine.next_check_due_at(session, settings)
            if due_at is None or now < due_at:
                continue
            if now > due_at + timedelta(
                seconds=settings["time_limit_seconds"] * 4 + 3600
            ):
                # Stale beyond reason (e.g. the scheduler was down for hours
                # after the class emptied out) — skip rather than fire a
                # check that no one in the room is around to answer.
                continue

            try:
                verif = engine.trigger_verification(
                    session,
                    triggered_by="auto-scheduler",
                    time_limit_seconds=settings["time_limit_seconds"],
                )
                fired += 1
                self.stdout.write(
                    f"Session {session.id}: fired verification "
                    f"#{verif.verification_number} (expires {verif.expires_at.isoformat()})"
                )
            except Exception:  # noqa: BLE001 - one bad session must not stop the loop
                logger.exception(
                    "Failed to trigger verification for session %s", session.id
                )
        return fired
