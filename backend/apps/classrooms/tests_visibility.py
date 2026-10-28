"""
What a class list answers, and what a class reports about itself.

Two rules are pinned here, both of which were wrong before:

* the list answers for the caller -- a student sees the classes they are in,
  not the institution's whole timetable; and
* a class reports the attendance that actually happened, counted from the
  attendance records rather than from a counter nothing ever incremented.
"""

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.attendance import engine
from apps.attendance.models import Attendance
from apps.classrooms.models import Session


class ClassVisibilityTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.lecturer = User.objects.create(
            id="usr_vis_lect", email="vis-lect@test.com",
            full_name="Dr. Vis", role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_vis_stud", email="vis-stud@test.com",
            full_name="Vis Student", role="student", index_number="V/1",
        )
        self.session = Session.objects.create(
            id="vis-session", title="Visible Class", host=self.lecturer,
            lecturer=self.lecturer, meeting_code="VIS-1",
            status="active", is_active=True,
        )
        self.other_session = Session.objects.create(
            id="vis-session-2", title="Someone Else's Class", host=self.lecturer,
            lecturer=self.lecturer, meeting_code="VIS-2",
            status="active", is_active=True,
        )

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def _list(self, user, **params):
        self._auth(user)
        res = self.client.get("/api/v1/classrooms/sessions/", params or None)
        self.assertEqual(res.status_code, 200)
        return [s["id"] for s in res.json()["documents"]]

    def test_a_new_student_sees_no_classes(self):
        # The complaint this guards: an account created a minute ago was shown
        # every live class in the institution.
        self.assertEqual(self._list(self.student), [])

    def test_a_student_sees_only_the_classes_they_joined(self):
        Attendance.objects.create(
            id="att-vis", session=self.session, student=self.student,
            student_name="Vis Student", joined_at=timezone.now(),
        )
        self.assertEqual(self._list(self.student), ["vis-session"])

    def test_a_lecturer_still_sees_the_timetable(self):
        self.assertEqual(
            sorted(self._list(self.lecturer)), ["vis-session", "vis-session-2"]
        )

    def test_joined_classes_still_exclude_deleted_ones_from_the_live_list(self):
        Attendance.objects.create(
            id="att-vis", session=self.session, student=self.student,
            student_name="Vis Student", joined_at=timezone.now(),
        )
        self.session.is_deleted = True
        self.session.save()
        self.assertEqual(self._list(self.student), [])

    def test_a_meeting_code_reaches_a_class_the_caller_never_joined(self):
        # Join by code has to work for a student who is not in the class yet --
        # the scoped list cannot answer that question on its own.
        ids = self._list(self.student, meetingCode="VIS-1")
        self.assertEqual(ids, ["vis-session"])

    def test_an_unknown_meeting_code_answers_with_no_documents(self):
        self.assertEqual(self._list(self.student, meetingCode="NOPE-9"), [])

    def test_a_class_reports_attendance_that_actually_happened(self):
        Attendance.objects.create(
            id="att-vis-1", session=self.session, student=self.student,
            student_name="Vis Student", joined_at=timezone.now(),
            verification_percentage=100,
        )
        Attendance.objects.create(
            id="att-vis-2", session=self.session, student=self.lecturer,
            student_name="Dr. Vis", joined_at=timezone.now(),
            verification_percentage=0,
        )
        # A check ran, so presence is decided by who answered it.
        engine.trigger_verification(self.session, triggered_by="host")

        self._auth(self.lecturer)
        rows = self.client.get("/api/v1/classrooms/sessions/").json()["documents"]
        row = next(s for s in rows if s["id"] == "vis-session")
        self.assertEqual(row["attendeeCount"], 2)
        self.assertEqual(row["presentCount"], 1)
        # The stored counter is kept in step too, since older readers use it.
        self.session.refresh_from_db()
        self.assertEqual(self.session.participant_count, 2)

    def test_a_class_whose_checker_never_fired_counts_its_attendance(self):
        # The checker's first check is due an interval after the start, so a
        # short class fires none. Nobody could answer anything, and everybody
        # who was there counts as present -- reading 0 would be a lie about a
        # class that just happened.
        Attendance.objects.create(
            id="att-short", session=self.session, student=self.student,
            student_name="Vis Student", joined_at=timezone.now(),
            verification_percentage=0, total_verifications_sent=0,
        )
        self._auth(self.lecturer)
        rows = self.client.get("/api/v1/classrooms/sessions/").json()["documents"]
        row = next(s for s in rows if s["id"] == "vis-session")
        self.assertEqual(row["attendeeCount"], 1)
        self.assertEqual(row["presentCount"], 1)


class EndedClassTests(TestCase):
    """A class the host ended is closed, and only its host can reopen it."""

    def setUp(self):
        self.client = APIClient()
        self.lecturer = User.objects.create(
            id="usr_end_lect", email="end-lect@test.com",
            full_name="Dr. End", role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_end_stud", email="end-stud@test.com",
            full_name="End Student", role="student", index_number="E/1",
        )
        self.session = Session.objects.create(
            id="end-session", title="Ended Class", host=self.lecturer,
            lecturer=self.lecturer, meeting_code="END-1",
            status="active", is_active=True, started_at=timezone.now(),
        )

    def _token(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")
        return self.client.post(
            f"/api/v1/classrooms/sessions/{self.session.id}/token/",
            data={"participantName": "Someone"},
            format="json",
        )

    def test_a_student_cannot_rejoin_an_ended_class_and_is_told_why(self):
        self.session.status = "ended"
        self.session.is_active = False
        self.session.ended_at = timezone.now()
        self.session.save()

        res = self._token(self.student)
        self.assertEqual(res.status_code, 403)
        error = res.json()["error"]
        self.assertEqual(error["code"], "SESSION_ENDED")
        # The message has to say what the student is waiting for.
        self.assertIn("host", error["message"].lower())

    def test_the_host_rejoining_reopens_the_class(self):
        self.session.status = "ended"
        self.session.is_active = False
        self.session.save()
        started_at = self.session.started_at

        res = self._token(self.lecturer)
        self.assertEqual(res.status_code, 200)
        self.session.refresh_from_db()
        self.assertEqual(self.session.status, "active")
        self.assertTrue(self.session.is_active)
        # Reopening must not erase the first sitting's runtime.
        self.assertEqual(self.session.started_at, started_at)

    def test_ending_a_class_clears_the_room(self):
        # Marking it ended changed only our own record: everyone still held a
        # live WebRTC session, so the class carried on without its host.
        self._auth_lecturer()
        with self.assertLogs("apps.classrooms.livekit", level="INFO"):
            res = self.client.post(f"/api/v1/classrooms/sessions/{self.session.id}/end/")
        self.assertEqual(res.status_code, 200)
        self.session.refresh_from_db()
        self.assertEqual(self.session.status, "ended")
        self.assertIsNotNone(self.session.ended_at)

    def _auth_lecturer(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.lecturer)}")
