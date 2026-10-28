"""
A student's own attendance, and what makes them "present".

The reported bug: a student answered a verification check and their attendance
still read zero. Two causes are pinned here -- answering a check recorded
nothing when the join had not been registered, and the only number any client
could read was a session counter that was never written.
"""

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.attendance import engine
from apps.attendance.models import Attendance
from apps.classrooms.models import Session


class MyAttendanceTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.lecturer = User.objects.create(
            id="usr_mine_lect", email="mine-lect@test.com",
            full_name="Dr. Mine", role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_mine_stud", email="mine-stud@test.com",
            full_name="Mine Student", role="student", index_number="M/1",
        )
        self.session = Session.objects.create(
            id="mine-session", title="Mine Class", host=self.lecturer,
            lecturer=self.lecturer, meeting_code="MINE-1",
            course="CS101", status="active", is_active=True,
            started_at=timezone.now(),
        )

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def _mine(self, user):
        self._auth(user)
        res = self.client.get("/api/v1/attendance/mine/")
        self.assertEqual(res.status_code, 200)
        return res.json()["documents"]

    def test_answering_a_check_records_attendance_without_a_previous_join(self):
        # No /attendance/join call: the student is in the room, but the join was
        # never registered. Answering the check is the proof of presence.
        verif = engine.trigger_verification(self.session, triggered_by="host")

        self._auth(self.student)
        res = self.client.post(
            "/api/v1/attendance/verification/respond/",
            data={"sessionId": self.session.id, "verificationId": verif.id},
            format="json",
        )
        self.assertEqual(res.status_code, 200)

        records = self._mine(self.student)
        self.assertEqual(len(records), 1)
        self.assertTrue(records[0]["present"])
        self.assertEqual(records[0]["verificationPercentage"], 100)
        self.assertEqual(records[0]["verificationsCompleted"], 1)

    def test_a_student_only_sees_their_own_records(self):
        Attendance.objects.create(
            id="att-someone-else", session=self.session, student=self.lecturer,
            student_name="Dr. Mine", joined_at=timezone.now(),
            verification_percentage=100,
        )
        self.assertEqual(self._mine(self.student), [])

    def test_a_record_stays_readable_after_the_class_is_deleted(self):
        Attendance.objects.create(
            id="att-mine", session=self.session, student=self.student,
            student_name="Mine Student", joined_at=timezone.now(),
            verification_percentage=100,
        )
        self.session.is_deleted = True
        self.session.status = "deleted"
        self.session.save()

        records = self._mine(self.student)
        self.assertEqual(len(records), 1)
        self.assertTrue(records[0]["isDeleted"])
        self.assertTrue(records[0]["present"])

    def test_the_roster_counts_an_answered_check_as_present(self):
        verif = engine.trigger_verification(self.session, triggered_by="host")
        self._auth(self.student)
        self.client.post(
            "/api/v1/attendance/verification/respond/",
            data={"sessionId": self.session.id, "verificationId": verif.id},
            format="json",
        )

        self._auth(self.lecturer)
        res = self.client.get(f"/api/v1/attendance/sessions/{self.session.id}/")
        summary = res.json()["document"]["summary"]
        self.assertEqual(summary["joined"], 1)
        self.assertEqual(summary["present"], 1)

    def test_a_class_whose_checker_never_fired_still_counts_the_student(self):
        # Short classes fire no check at all (the first is due an interval in),
        # so there was nothing to answer and the student was simply there.
        self._auth(self.student)
        self.client.post(
            "/api/v1/attendance/join/",
            data={"sessionId": self.session.id},
            format="json",
        )
        records = self._mine(self.student)
        self.assertEqual(len(records), 1)
        self.assertTrue(records[0]["present"])
        self.assertEqual(records[0]["verificationsSent"], 0)

    def test_a_student_who_answered_nothing_is_not_counted_present(self):
        engine.trigger_verification(self.session, triggered_by="host")
        Attendance.objects.create(
            id="att-quiet", session=self.session, student=self.student,
            student_name="Mine Student", joined_at=timezone.now(),
        )

        self._auth(self.lecturer)
        res = self.client.get(f"/api/v1/attendance/sessions/{self.session.id}/")
        summary = res.json()["document"]["summary"]
        self.assertEqual(summary["joined"], 1)
        self.assertEqual(summary["present"], 0)

    def test_a_service_key_cannot_read_someone_elses_mine(self):
        from django.test import override_settings

        with override_settings(ATTENDANCE_API_KEY="test-service-key"):
            self.client.credentials(HTTP_AUTHORIZATION="Bearer test-service-key")
            res = self.client.get("/api/v1/attendance/mine/")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "VALIDATION_ERROR")
