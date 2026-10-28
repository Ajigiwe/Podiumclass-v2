"""
When a class is live, and who may walk into it.

A class used to be created already running, which meant the door was open
before anyone was inside. The rules now are:

* creating a class prepares it -- it is listed, with its code, and not yet live;
* the host arriving in it is what starts it, once; and
* a student who gets there first waits, and is told why.
"""

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.core import features


class ClassStartTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.host = User.objects.create(
            id="usr_start_host", email="start-host@test.com",
            full_name="Dr. Start", role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_start_stud", email="start-stud@test.com",
            full_name="Start Student", role="student", index_number="S/1",
        )

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def _create(self):
        self._auth(self.host)
        res = self.client.post(
            "/api/v1/classrooms/sessions/",
            data={"title": "Prepared Class", "course": "PC101"},
            format="json",
        )
        self.assertEqual(res.status_code, 201)
        return res.json()["document"]

    def _token(self, user):
        self._auth(user)
        return self.client.post(
            "/api/v1/classrooms/sessions/%s/token/" % self.session_id,
            data={"participantName": "Someone"},
            format="json",
        )

    def test_creating_leaves_the_class_prepared_but_not_live(self):
        doc = self._create()
        self.session_id = doc["id"]
        session = Session.objects.get(id=self.session_id)
        self.assertFalse(session.is_active)
        self.assertEqual(session.status, "scheduled")
        self.assertIsNone(session.started_at)
        # The code is ready to share the moment the class exists.
        self.assertTrue(doc["meetingCode"])

    def test_a_student_who_arrives_first_waits_instead_of_joining(self):
        self.session_id = self._create()["id"]
        res = self._token(self.student)
        self.assertEqual(res.status_code, 403)
        error = res.json()["error"]
        self.assertEqual(error["code"], "SESSION_NOT_STARTED")
        self.assertIn("host", error["message"].lower())

    def test_the_host_arriving_starts_the_class_and_lets_the_student_in(self):
        self.session_id = self._create()["id"]
        self.assertEqual(self._token(self.student).status_code, 403)

        self.assertEqual(self._token(self.host).status_code, 200)
        session = Session.objects.get(id=self.session_id)
        self.assertTrue(session.is_active)
        self.assertEqual(session.status, "active")
        self.assertIsNotNone(session.started_at)
        self.assertIsNotNone(session.host_last_seen)

        self.assertEqual(self._token(self.student).status_code, 200)

    def test_a_second_visit_does_not_restart_the_clock(self):
        self.session_id = self._create()["id"]
        self._token(self.host)
        first_start = Session.objects.get(id=self.session_id).started_at

        self._token(self.host)
        self.assertEqual(Session.objects.get(id=self.session_id).started_at, first_start)

    def test_a_run_class_that_was_never_live_cannot_be_recorded(self):
        # Recording an empty room was possible while a class was created
        # active; now there is genuinely nothing to record yet.
        features.set_enabled("recordings", True)
        self.addCleanup(features.set_enabled, "recordings", False)
        self.session_id = self._create()["id"]
        self._auth(self.host)
        res = self.client.post(
            f"/api/v1/classrooms/sessions/{self.session_id}/recordings/",
            data={},
            format="json",
        )
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "VALIDATION_ERROR")

    def test_the_class_does_not_appear_as_live_before_the_host_arrives(self):
        self.session_id = self._create()["id"]
        self._auth(self.host)
        rows = self.client.get("/api/v1/classrooms/sessions/", {"status": "active"}).json()
        self.assertEqual(rows["documents"], [])

        self._token(self.host)
        rows = self.client.get("/api/v1/classrooms/sessions/", {"status": "active"}).json()
        self.assertEqual([r["id"] for r in rows["documents"]], [self.session_id])

    def test_an_ended_class_can_be_restarted_by_its_host(self):
        self.session_id = self._create()["id"]
        self._token(self.host)
        self.client.post(f"/api/v1/classrooms/sessions/{self.session_id}/end/")

        session = Session.objects.get(id=self.session_id)
        self.assertEqual(session.status, "ended")

        self.assertEqual(self._token(self.student).status_code, 403)
        self.assertEqual(self._token(self.host).status_code, 200)
        session.refresh_from_db()
        self.assertEqual(session.status, "active")
        self.assertIsNone(session.ended_at)
