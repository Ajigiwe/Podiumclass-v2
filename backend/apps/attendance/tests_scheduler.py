"""
Tests for the server-side automated attendance scheduler and the active
verification endpoint that classroom clients poll.

The scheduler is the backend process that fires checks when no moderator's
browser tab is awake; these tests exercise the same single scan the command
performs (via the ``--once`` style call through ``Command().scan()``).
"""

from datetime import timedelta

from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone

from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.attendance import engine
from apps.attendance.models import Attendance, Verification
from apps.core.management.commands.run_attendance_checker import Command

TEST_API_KEY = "test-service-key"
AUTH = {"HTTP_AUTHORIZATION": f"Bearer {TEST_API_KEY}"}


@override_settings(ATTENDANCE_API_KEY=TEST_API_KEY)
class SchedulerScanTest(TestCase):
    def setUp(self):
        self.lecturer = User.objects.create_user(
            email="lecturer@university.edu",
            full_name="Prof. Mensah",
            role="lecturer",
        )
        self.student = User.objects.create_user(
            email="student@university.edu",
            full_name="Kwame Asante",
            role="student",
        )
        self.session = Session.objects.create(
            id="sched-session-1",
            title="Automation Lab",
            host=self.lecturer,
            meeting_code="CS101-SCHED",
            course="CS101",
            status="active",
            is_active=True,
            started_at=timezone.now() - timedelta(hours=1),
            verification_count=3,
            auto_attendance_settings={
                "enabled": True,
                "checkCount": 3,
                "timeLimitSeconds": 30,
                "intervalMinutes": 10,
            },
        )
        Attendance.objects.create(
            id="att-sched-1",
            session=self.session,
            student=self.student,
            student_name="Kwame Asante",
        )

    def test_due_check_is_fired_by_scan(self):
        # First check due one interval after start: an hour ago it fired.
        fired = Command().scan()
        self.assertEqual(fired, 1)
        verif = Verification.objects.get(session=self.session)
        self.assertEqual(verif.verification_number, 1)
        self.assertEqual(verif.triggered_by, "auto-scheduler")
        self.assertAlmostEqual(
            verif.expires_at - verif.triggered_at, timedelta(seconds=30)
        )

        # The counter bump must reach attendees (same write path as manual triggers).
        att = Attendance.objects.get(id="att-sched-1")
        att.refresh_from_db()
        self.assertEqual(att.total_verifications_sent, 1)

    def test_next_check_waits_for_interval(self):
        Command().scan()
        # Scan again immediately: the interval has not elapsed, nothing fires.
        self.assertEqual(Command().scan(), 0)
        self.assertEqual(Verification.objects.filter(session=self.session).count(), 1)

    def test_scan_stops_at_target_count(self):
        for _ in range(3):
            Command().scan()

        # Jump past the next interval several times: the cap holds.
        for _ in range(3):
            latest = Verification.objects.filter(
                session=self.session
            ).order_by("-verification_number").first()
            latest.triggered_at = timezone.now() - timedelta(minutes=20)
            latest.save()
            Command().scan()

        self.assertEqual(
            Verification.objects.filter(session=self.session).count(), 3
        )

    def test_disabled_checker_never_fires(self):
        self.session.auto_attendance_settings = {
            "enabled": False,
            "checkCount": 3,
            "intervalMinutes": 10,
        }
        self.session.save()
        self.assertEqual(Command().scan(), 0)
        self.assertEqual(Verification.objects.filter(session=self.session).count(), 0)

    def test_scheduler_and_manual_trigger_share_write_path(self):
        Command().scan()
        scheduled = Verification.objects.get(session=self.session)
        # Manual trigger via the engine must be indistinguishable in shape.
        manual = engine.trigger_verification(self.session, triggered_by="lect-x")
        self.assertEqual(manual.verification_number, scheduled.verification_number + 1)


@override_settings(ATTENDANCE_API_KEY=TEST_API_KEY)
class VerificationActiveEndpointTest(TestCase):
    def setUp(self):
        self.lecturer = User.objects.create_user(
            email="lecturer@university.edu", full_name="Prof. M", role="lecturer"
        )
        self.session = Session.objects.create(
            id="active-session-1",
            title="Active Class",
            host=self.lecturer,
            meeting_code="CS101-ACTIVE",
            course="CS101",
            status="active",
            is_active=True,
            verification_count=3,
            auto_attendance_settings={
                "enabled": True,
                "checkCount": 3,
                "timeLimitSeconds": 30,
                "intervalMinutes": 10,
            },
        )

    def _url(self, after=0):
        return f"{reverse('attendance-verification-active')}?sessionId={self.session.id}&after={after}"

    def test_returns_no_verification_when_none_open(self):
        response = self.client.get(self._url(), **AUTH)
        self.assertEqual(response.status_code, 200)
        doc = response.json()["document"]
        self.assertIsNone(doc["verification"])
        self.assertEqual(doc["checksCompleted"], 0)
        self.assertIsNotNone(doc["nextCheckAt"])

    def test_returns_open_verification_with_seconds_remaining(self):
        engine.trigger_verification(self.session, triggered_by="auto-scheduler")
        response = self.client.get(self._url(), **AUTH)
        doc = response.json()["document"]
        self.assertEqual(doc["checksCompleted"], 1)
        verif = doc["verification"]
        self.assertEqual(verif["verificationNumber"], 1)
        self.assertLessEqual(verif["secondsRemaining"], 30)
        self.assertGreater(verif["secondsRemaining"], 0)

    def test_after_watermark_hides_already_seen_check(self):
        verif = engine.trigger_verification(self.session, triggered_by="lecturer")
        seen = self.client.get(self._url(after=verif.verification_number), **AUTH)
        self.assertIsNone(seen.json()["document"]["verification"])

    def test_expired_verification_is_not_fetched(self):
        verif = engine.trigger_verification(self.session, triggered_by="auto-scheduler")
        verif.expires_at = timezone.now() - timedelta(seconds=1)
        verif.save()
        response = self.client.get(self._url(), **AUTH)
        self.assertIsNone(response.json()["document"]["verification"])

    def test_has_seen_watermark_skips_expired_check(self):
        verif = engine.trigger_verification(self.session, triggered_by="auto-scheduler")
        verif.expires_at = timezone.now() - timedelta(seconds=1)
        verif.save()
        response = self.client.get(self._url(after=verif.verification_number), **AUTH)
        self.assertIsNone(response.json()["document"]["verification"])

    def test_requires_auth_and_session_to_exist(self):
        self.assertEqual(self.client.get(f"{reverse('attendance-verification-active')}?sessionId=nonesuch", **AUTH).status_code, 404)
        response = self.client.get(f"{reverse('attendance-verification-active')}?sessionId={self.session.id}")
        self.assertEqual(response.status_code, 401)
