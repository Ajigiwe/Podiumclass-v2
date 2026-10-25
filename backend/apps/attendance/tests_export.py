"""
Tests for the external attendance contract:

    GET /api/v1/attendance/students/{studentId}
    GET /api/v1/attendance/export

These endpoints are consumed by the Laravel service, which reads a FLAT
``$response->json('data')`` payload -- not the ``document`` wrapper used
elsewhere. Several tests below exist specifically to pin that difference, since
it is the kind of thing a well-meaning refactor would "fix" back into breakage.
"""

from datetime import timedelta
from unittest import mock

from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone

from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.core import features

from apps.attendance.models import Attendance, AttendanceLog

SERVICE_KEY = "test-service-key"
AUTH = {"HTTP_AUTHORIZATION": f"Bearer {SERVICE_KEY}"}


@override_settings(ATTENDANCE_API_KEY=SERVICE_KEY)
class ExternalAttendanceTestBase(TestCase):
    def setUp(self):
        features.set_enabled("external_attendance_api", True)

        self.lecturer = User.objects.create_user(
            email="lecturer@x.edu", full_name="Prof. Owusu", role="lecturer"
        )
        self.student = User.objects.create_user(
            email="ama@x.edu", full_name="Ama Boateng", role="student",
            index_number="CS/2026/001",
        )
        self.other = User.objects.create_user(
            email="kofi@x.edu", full_name="Kofi Mensah", role="student",
            index_number="CS/2026/002",
        )

        now = timezone.now()
        self.session = Session.objects.create(
            id="ses_alg", title="Algorithms", host=self.lecturer,
            meeting_code="ALG-101", course="CS201", program="BSc CS",
            status="ended", duration_minutes=60, participant_count=2,
            created_at=now - timedelta(days=2),
            started_at=now - timedelta(days=2),
            ended_at=now - timedelta(days=2) + timedelta(minutes=50),
        )

        self.attendance = Attendance.objects.create(
            id="att_ama", session=self.session, student=self.student,
            student_name="Ama Boateng", student_index_number="CS/2026/001",
            total_verifications_sent=4, total_verifications_completed=3,
            verification_percentage=75, is_present=True,
            joined_at=now - timedelta(days=2),
        )
        Attendance.objects.create(
            id="att_kofi", session=self.session, student=self.other,
            student_name="Kofi Mensah", student_index_number="CS/2026/002",
            total_verifications_sent=4, total_verifications_completed=4,
            verification_percentage=100, is_present=True,
            joined_at=now - timedelta(days=2),
        )


class StudentHistoryTests(ExternalAttendanceTestBase):
    def url(self, student_id=None):
        return reverse("attendance-student-history", kwargs={
            "student_id": student_id or self.student.id
        })

    def test_requires_service_key(self):
        res = self.client.get(self.url())
        self.assertEqual(res.status_code, 403)
        body = res.json()
        self.assertIn("error", body)
        self.assertEqual(body["error"]["code"], "MISSING_API_KEY")

    def test_rejects_browser_jwt(self):
        """History exposes every student's record: service key only."""
        res = self.client.get(self.url(), HTTP_AUTHORIZATION="Bearer not-a-service-key")
        self.assertEqual(res.status_code, 403)

    def test_payload_is_flat_data_not_document(self):
        res = self.client.get(self.url(), **AUTH)
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertIn("data", body)
        self.assertNotIn("document", body)
        self.assertNotIn("documents", body)
        self.assertIn("pagination", body)
        self.assertIn("filters", body)
        self.assertEqual(body["filters"]["studentId"], self.student.id)

    def test_returns_only_that_students_records(self):
        body = self.client.get(self.url(), **AUTH).json()
        self.assertEqual(len(body["data"]), 1)
        entry = body["data"][0]
        self.assertEqual(entry["record"]["studentId"], self.student.id)
        self.assertEqual(entry["record"]["studentName"], "Ama Boateng")
        self.assertEqual(entry["session"]["sessionId"], "ses_alg")

    def test_record_derives_present_from_verification_threshold(self):
        record = self.client.get(self.url(), **AUTH).json()["data"][0]["record"]
        # 75% >= 50% threshold.
        self.assertTrue(record["present"])
        self.assertEqual(record["verificationPercentage"], 75)
        self.assertTrue(record["joined"])

    def test_stay_minutes_uses_session_end_when_student_never_left(self):
        record = self.client.get(self.url(), **AUTH).json()["data"][0]["record"]
        # joined_at is 2 days ago; session ended 50 minutes after it started,
        # which is the same moment here, so the fallback end is used.
        self.assertIsNotNone(record["stayMinutes"])
        self.assertGreaterEqual(record["stayMinutes"], 0)

    def test_unknown_student_returns_404(self):
        res = self.client.get(self.url("usr_does_not_exist"), **AUTH)
        self.assertEqual(res.status_code, 404)
        self.assertEqual(res.json()["error"]["code"], "STUDENT_NOT_FOUND")

    def test_invalid_date_returns_documented_error(self):
        res = self.client.get(self.url(), {"from": "not-a-date"}, **AUTH)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_DATE")

    def test_from_after_to_returns_invalid_range(self):
        res = self.client.get(
            self.url(), {"from": "2026-03-10", "to": "2026-03-01"}, **AUTH
        )
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_RANGE")

    def test_invalid_cursor_returns_400(self):
        res = self.client.get(self.url(), {"cursor": "!!!not-base64!!!"}, **AUTH)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_CURSOR")

    def test_pagination_cursor_round_trip(self):
        # A second, older session for the same student.
        older = Session.objects.create(
            id="ses_old", title="Older", host=self.lecturer, meeting_code="OLD-1",
            course="CS100", program="BSc CS", status="ended",
            created_at=timezone.now() - timedelta(days=30),
            ended_at=timezone.now() - timedelta(days=30) + timedelta(minutes=30),
        )
        Attendance.objects.create(
            id="att_ama_old", session=older, student=self.student,
            student_name="Ama Boateng", student_index_number="CS/2026/001",
            is_present=True, joined_at=timezone.now() - timedelta(days=30),
        )

        first = self.client.get(self.url(), {"limit": 1}, **AUTH).json()
        self.assertEqual(len(first["data"]), 1)
        self.assertTrue(first["pagination"]["hasMore"])
        self.assertIsNotNone(first["pagination"]["nextCursor"])

        second = self.client.get(
            self.url(),
            {"limit": 1, "cursor": first["pagination"]["nextCursor"]},
            **AUTH,
        ).json()
        self.assertEqual(len(second["data"]), 1)
        self.assertNotEqual(
            second["data"][0]["session"]["sessionId"],
            first["data"][0]["session"]["sessionId"],
        )

    def test_falls_back_to_legacy_join_log(self):
        """Sessions with only an attendance_logs row must still appear."""
        legacy_session = Session.objects.create(
            id="ses_legacy", title="Legacy", host=self.lecturer, meeting_code="LEG-1",
            course="CS050", program="BSc CS", status="ended",
            created_at=timezone.now() - timedelta(days=5),
            ended_at=timezone.now() - timedelta(days=5) + timedelta(minutes=20),
        )
        AttendanceLog.objects.create(
            id="log_legacy", session=legacy_session, user=self.student,
            user_name="Ama Boateng", user_index_number="CS/2026/001",
            is_present=True, joined_at=timezone.now() - timedelta(days=5),
        )

        body = self.client.get(self.url(), **AUTH).json()
        ids = {row["session"]["sessionId"] for row in body["data"]}
        self.assertIn("ses_legacy", ids)

    def test_feature_disabled_returns_503(self):
        features.set_enabled("external_attendance_api", False)
        res = self.client.get(self.url(), **AUTH)
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "FEATURE_DISABLED")


class ExportTests(ExternalAttendanceTestBase):
    def url(self):
        return reverse("attendance-export")

    def test_json_is_flat_data_and_meta(self):
        res = self.client.get(self.url(), {"format": "json"}, **AUTH)
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertIn("data", body)
        self.assertIn("meta", body)
        self.assertNotIn("document", body)
        self.assertEqual(body["meta"]["rowCount"], 2)
        self.assertEqual(body["meta"]["sessionCount"], 1)
        self.assertFalse(body["meta"]["truncated"])

    def test_json_rows_carry_all_documented_columns(self):
        row = self.client.get(self.url(), {"format": "json"}, **AUTH).json()["data"][0]
        for column in (
            "session_id", "session_title", "course", "program", "session_status",
            "student_id", "student_name", "index_number", "joined_at",
            "stay_minutes", "joined", "present", "verification_percentage",
            "verifications_sent", "verifications_completed", "source",
        ):
            self.assertIn(column, row)

    def test_csv_is_default_format(self):
        res = self.client.get(self.url(), **AUTH)
        self.assertEqual(res.status_code, 200)
        self.assertIn("text/csv", res["Content-Type"])
        self.assertIn("attachment", res["Content-Disposition"])
        text = res.content.decode()
        header = text.splitlines()[0]
        self.assertTrue(header.startswith("session_id,session_title,course"))
        self.assertEqual(len(text.splitlines()), 3)  # header + 2 rows

    def test_invalid_format_rejected(self):
        res = self.client.get(self.url(), {"format": "xml"}, **AUTH)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_FORMAT")

    def test_session_filter(self):
        body = self.client.get(
            self.url(), {"format": "json", "sessionId": "ses_alg"}, **AUTH
        ).json()
        self.assertEqual(body["meta"]["sessionCount"], 1)
        self.assertEqual(body["meta"]["rowCount"], 2)

    def test_unknown_session_returns_404(self):
        res = self.client.get(
            self.url(), {"format": "json", "sessionId": "nope"}, **AUTH
        )
        self.assertEqual(res.status_code, 404)
        self.assertEqual(res.json()["error"]["code"], "SESSION_NOT_FOUND")

    def test_course_filter_excludes_other_courses(self):
        other = Session.objects.create(
            id="ses_bio", title="Biology", host=self.lecturer, meeting_code="BIO-1",
            course="BIO101", program="BSc Bio", status="ended",
            created_at=timezone.now() - timedelta(days=3),
            ended_at=timezone.now() - timedelta(days=3) + timedelta(minutes=30),
        )
        Attendance.objects.create(
            id="att_bio", session=other, student=self.student,
            student_name="Ama Boateng", is_present=True,
            joined_at=timezone.now() - timedelta(days=3),
        )

        body = self.client.get(
            self.url(), {"format": "json", "course": "CS201", "from": "2026-01-01"},
            **AUTH,
        ).json()
        session_ids = {row["session_id"] for row in body["data"]}
        self.assertEqual(session_ids, {"ses_alg"})

    def test_truncation_header(self):
        with mock.patch("apps.attendance.export_views.MAX_ROWS", 1):
            res = self.client.get(self.url(), {"format": "json"}, **AUTH)
        self.assertEqual(res["X-Result-Truncated"], "true")
        self.assertEqual(len(res.json()["data"]), 1)
        self.assertTrue(res.json()["meta"]["truncated"])

    def test_requires_service_key(self):
        self.assertEqual(self.client.get(self.url()).status_code, 403)

    def test_feature_disabled_returns_503(self):
        features.set_enabled("external_attendance_api", False)
        res = self.client.get(self.url(), **AUTH)
        self.assertEqual(res.status_code, 503)


class LaravelCompatibilityAliasTests(ExternalAttendanceTestBase):
    """
    The documented Laravel client reads ``$response->json('data')`` for the
    session endpoints too, while the Vue SPA reads ``documents``/``document``.
    Both keys must be present.
    """

    def test_sessions_list_exposes_data_and_documents(self):
        body = self.client.get(reverse("attendance-sessions"), **AUTH).json()
        self.assertIn("data", body)
        self.assertIn("documents", body)
        self.assertEqual(len(body["data"]), len(body["documents"]))

    def test_session_detail_exposes_data_and_document(self):
        body = self.client.get(
            reverse("attendance-session-detail", kwargs={"session_id": "ses_alg"}),
            **AUTH,
        ).json()
        self.assertIn("data", body)
        self.assertIn("document", body)
        self.assertEqual(body["data"]["session"]["sessionId"], "ses_alg")


class AttendanceLogNormalizationTests(TestCase):
    """The two attendance tables have different field names; both must map."""

    def test_normalize_attendance_and_log_to_same_shape(self):
        from apps.attendance.export_views import _normalize

        lecturer = User.objects.create_user(
            email="l@x.edu", full_name="L", role="lecturer"
        )
        student = User.objects.create_user(
            email="s@x.edu", full_name="Ama", role="student",
            index_number="CS/1",
        )
        session = Session.objects.create(
            id="ses_n", title="T", host=lecturer, meeting_code="N-1",
            course="C", program="P", status="ended",
        )
        record = Attendance.objects.create(
            id="att_n", session=session, student=student,
            student_name="Ama", student_index_number="CS/1",
            is_present=True, verification_percentage=60,
        )
        log = AttendanceLog.objects.create(
            id="log_n", session=session, user=student,
            user_name="Ama", user_index_number="CS/1",
            is_present=True, verification_percentage=60,
        )

        a, b = _normalize(record), _normalize(log)
        for key in ("student_id", "student_name", "index_number",
                    "is_present", "verification_percentage"):
            self.assertEqual(a[key], b[key], key)
        self.assertEqual(a["source"], "attendance")
        self.assertEqual(b["source"], "attendance_logs")
