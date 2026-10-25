"""
Regression tests for attendance endpoint authentication.

The five dual-use attendance routes are called by BOTH the Vue SPA (user JWT)
and the Laravel service (server-to-server key). They previously declared the
service-key-only authenticator, which rejected every browser request with
``INVALID_API_KEY`` before the view ever ran -- the student join flow and the
admin attendance list were unusable.

These tests pin the intended contract so the restriction cannot creep back.
"""

from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.core import features

from apps.attendance.models import Attendance

SERVICE_KEY = "test-service-key"


def bearer(token):
    return {"HTTP_AUTHORIZATION": f"Bearer {token}"}


@override_settings(ATTENDANCE_API_KEY=SERVICE_KEY)
class DualUseAuthenticationTests(TestCase):
    def setUp(self):
        features.set_enabled("external_attendance_api", True)
        features.set_enabled("attendance_verification", True)

        self.lecturer = User.objects.create_user(
            email="lec@x.edu", full_name="Prof. Owusu", role="lecturer"
        )
        self.student = User.objects.create_user(
            email="ama@x.edu", full_name="Ama Boateng", role="student",
            index_number="CS/2026/001",
        )
        self.other = User.objects.create_user(
            email="kofi@x.edu", full_name="Kofi Mensah", role="student",
            index_number="CS/2026/002",
        )
        self.session = Session.objects.create(
            id="ses_live", title="Algorithms", host=self.lecturer,
            meeting_code="ALG-101", course="CS201", program="BSc CS",
            status="active", is_active=True, duration_minutes=60,
        )

        self.student_token = issue_token(self.student)
        self.lecturer_token = issue_token(self.lecturer)

    # -- sessions ----------------------------------------------------------
    def test_sessions_accepts_browser_jwt(self):
        res = self.client.get(
            reverse("attendance-sessions"), **bearer(self.lecturer_token)
        )
        self.assertEqual(res.status_code, 200)
        self.assertIn("documents", res.json())

    def test_sessions_accepts_service_key(self):
        res = self.client.get(
            reverse("attendance-sessions"), **bearer(SERVICE_KEY)
        )
        self.assertEqual(res.status_code, 200)

    def test_sessions_rejects_anonymous(self):
        res = self.client.get(reverse("attendance-sessions"))
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.json()["error"]["code"], "MISSING_API_KEY")

    def test_session_detail_accepts_browser_jwt(self):
        res = self.client.get(
            reverse("attendance-session-detail", kwargs={"session_id": "ses_live"}),
            **bearer(self.lecturer_token),
        )
        self.assertEqual(res.status_code, 200)
        self.assertIn("document", res.json())

    # -- join --------------------------------------------------------------
    def test_join_accepts_browser_jwt(self):
        res = self.client.post(
            reverse("attendance-join"),
            {"sessionId": "ses_live"},
            content_type="application/json",
            **bearer(self.student_token),
        )
        self.assertEqual(res.status_code, 200, res.content)
        self.assertTrue(
            Attendance.objects.filter(session=self.session, student=self.student).exists()
        )

    def test_jwt_caller_cannot_forge_another_students_attendance(self):
        res = self.client.post(
            reverse("attendance-join"),
            {"sessionId": "ses_live", "studentId": self.other.id,
             "studentName": "Impostor"},
            content_type="application/json",
            **bearer(self.student_token),
        )
        self.assertEqual(res.status_code, 200)
        # The record belongs to the token's subject, never the body's claim.
        self.assertTrue(
            Attendance.objects.filter(session=self.session, student=self.student).exists()
        )
        self.assertFalse(
            Attendance.objects.filter(session=self.session, student=self.other).exists()
        )

    def test_service_key_may_name_the_student_explicitly(self):
        res = self.client.post(
            reverse("attendance-join"),
            {"sessionId": "ses_live", "studentId": self.other.id,
             "studentName": "Kofi Mensah"},
            content_type="application/json",
            **bearer(SERVICE_KEY),
        )
        self.assertEqual(res.status_code, 200)
        self.assertTrue(
            Attendance.objects.filter(session=self.session, student=self.other).exists()
        )

    def test_join_rejects_anonymous(self):
        res = self.client.post(
            reverse("attendance-join"),
            {"sessionId": "ses_live", "studentId": self.student.id},
            content_type="application/json",
        )
        self.assertEqual(res.status_code, 401)

    # -- verification ------------------------------------------------------
    def test_verification_trigger_accepts_browser_jwt(self):
        res = self.client.post(
            reverse("attendance-verification-trigger"),
            {"sessionId": "ses_live"},
            content_type="application/json",
            **bearer(self.lecturer_token),
        )
        # Whatever the domain outcome, the credential must not be rejected.
        self.assertNotEqual(res.status_code, 403, res.content)

    # -- fail closed -------------------------------------------------------
    @override_settings(ATTENDANCE_API_KEY="")
    def test_service_key_auth_fails_closed_when_unconfigured(self):
        """
        An unset key must never authenticate a caller.

        The previous implementation returned an authenticated anonymous user in
        this case, so a deployment that forgot the variable accepted anyone as
        the trusted external service.
        """
        res = self.client.get(
            reverse("attendance-export"), **bearer("anything-at-all")
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(res.json()["error"]["code"], "INVALID_API_KEY")

    # -- co-hosts --------------------------------------------------------
    #
    # Session-scoped verification endpoints accept whoever is running *that*
    # room; the unscoped list of every session stays lecturer/admin only.

    def _make_cohost(self, session=None, user=None):
        from apps.classrooms.models import CoHost

        CoHost.objects.create(
            id="coh_auth_test",
            session=session or self.session,
            user=user or self.student,
            user_name="Kofi Mensah",
            assigned_by=self.lecturer.id,
        )

    def test_cohost_of_the_session_may_trigger_verification(self):
        self._make_cohost()
        res = self.client.post(
            reverse("attendance-verification-trigger"),
            {"sessionId": self.session.id},
            content_type="application/json",
            **bearer(self.student_token),
        )
        self.assertNotEqual(res.status_code, 403, res.content)

    def test_a_plain_student_may_not_trigger_verification(self):
        res = self.client.post(
            reverse("attendance-verification-trigger"),
            {"sessionId": self.session.id},
            content_type="application/json",
            **bearer(self.student_token),
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(res.json()["error"]["code"], "LECTURER_REQUIRED")

    def test_a_cohost_of_another_session_cannot_trigger_here(self):
        other = Session.objects.create(
            id="ses_other_room", title="Other", host=self.lecturer,
            meeting_code="OTH-201", course="CS101", program="BSc CS",
            status="active", is_active=True, duration_minutes=60,
        )
        self._make_cohost(session=other)
        res = self.client.post(
            reverse("attendance-verification-trigger"),
            {"sessionId": self.session.id},
            content_type="application/json",
            **bearer(self.student_token),
        )
        self.assertEqual(res.status_code, 403)

    def test_cohost_may_read_the_roster_of_their_session(self):
        self._make_cohost()
        res = self.client.get(
            reverse("attendance-session-detail", kwargs={"session_id": self.session.id}),
            **bearer(self.student_token),
        )
        self.assertEqual(res.status_code, 200, res.content)

    def test_the_unscoped_session_list_stays_lecturer_only_for_a_cohost(self):
        self._make_cohost()
        res = self.client.get(reverse("attendance-sessions"), **bearer(self.student_token))
        self.assertEqual(res.status_code, 403)
