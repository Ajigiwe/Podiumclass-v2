import json
from django.test import TestCase
from rest_framework.test import APIClient
from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import Session

class ClassroomApiTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create(
            id="usr_lecturer_1",
            email="lecturer@test.com",
            full_name="Dr. Kwame Asante",
            role="lecturer"
        )
        # Endpoints now require a verified JWT; the token is signed for the
        # real user rather than being a bare user id.
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.user)}")

    def test_create_and_list_sessions_in_document_object(self):
        # 1. Create Session
        payload = {
            "title": "Advanced Algorithmic Thinking",
            "course": "CS302",
            "lecturerName": "Dr. Kwame Asante",
            "isFree": True
        }
        res = self.client.post("/api/v1/classrooms/sessions/", data=payload, format="json")
        self.assertEqual(res.status_code, 201)
        data = res.json()
        
        # Verify Document Object enforcement
        self.assertIn("document", data)
        self.assertEqual(data["document"]["title"], "Advanced Algorithmic Thinking")
        self.assertEqual(data["document"]["status"], "active")
        session_id = data["document"]["id"]

        # 2. List Sessions
        res_list = self.client.get("/api/v1/classrooms/sessions/")
        self.assertEqual(res_list.status_code, 200)
        list_data = res_list.json()
        
        # Verify List Document Object enforcement
        self.assertIn("documents", list_data)
        self.assertTrue(any(s["id"] == session_id for s in list_data["documents"]))

    def test_generate_livekit_token(self):
        payload = {
            "roomName": "POD-CS302-LIVE",
            "participantName": "Ama Serwaa",
            # Client-declared role must be ignored in favour of the DB role.
            "role": "student"
        }
        res = self.client.post("/api/v1/classrooms/token/", data=payload, format="json")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        
        # Must be in Document Object
        self.assertIn("document", data)
        self.assertIn("token", data["document"])
        self.assertEqual(data["document"]["roomName"], "POD-CS302-LIVE")
        self.assertTrue(len(data["document"]["token"]) > 30)

    def test_end_session(self):
        session = Session.objects.create(
            id="ses_to_end_99",
            title="Intro to PostgreSQL",
            host=self.user,
            meeting_code="POD-PG99",
            status="active",
            is_active=True
        )
        res = self.client.post(f"/api/v1/classrooms/sessions/{session.id}/end/")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("document", data)
        self.assertEqual(data["document"]["status"], "ended")
        self.assertFalse(data["document"]["is_active"])


class ClassroomAuthzTests(TestCase):
    """Guards that the endpoints are no longer anonymous."""

    def setUp(self):
        self.client = APIClient()
        self.lecturer = User.objects.create(
            id="usr_lect_2", email="l2@test.com", full_name="Dr. B", role="lecturer"
        )
        self.student = User.objects.create(
            id="usr_stu_2", email="s2@test.com", full_name="Kofi", role="student"
        )
        self.session = Session.objects.create(
            id="ses_authz_1", title="Secure Class", host=self.lecturer,
            meeting_code="POD-AUTHZ1", status="active", is_active=True
        )

    def test_anonymous_cannot_create_session(self):
        res = self.client.post(
            "/api/v1/classrooms/sessions/", data={"title": "Nope"}, format="json"
        )
        self.assertEqual(res.status_code, 401)

    def test_student_cannot_create_session(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.student)}")
        res = self.client.post(
            "/api/v1/classrooms/sessions/", data={"title": "Nope"}, format="json"
        )
        self.assertEqual(res.status_code, 403)

    def test_student_cannot_end_someone_elses_session(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.student)}")
        res = self.client.post(f"/api/v1/classrooms/sessions/{self.session.id}/end/")
        self.assertEqual(res.status_code, 403)

    def test_student_cannot_escalate_livekit_role(self):
        # Student asks for lecturer; the granted privileges must still be a
        # student's. The role is carried in `metadata` and drives roomAdmin.
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.student)}")
        res = self.client.post(
            "/api/v1/classrooms/token/",
            data={"roomName": "room-x", "role": "lecturer"},
            format="json",
        )
        self.assertEqual(res.status_code, 200)
        import json as _json
        import jwt as pyjwt
        from django.conf import settings
        raw = res.json()["document"]["token"]
        claims = pyjwt.decode(
            raw, settings.LIVEKIT_API_SECRET, algorithms=["HS256"],
            options={"verify_aud": False},
        )
        meta = _json.loads(claims["metadata"])
        self.assertEqual(meta["role"], "student")
        # The privilege that matters: no room admin for a student.
        self.assertFalse(claims["video"]["roomAdmin"])
        # Identity must be the authenticated user, not a client-supplied id.
        self.assertEqual(claims["sub"], self.student.id)

    def test_forged_legacy_token_is_rejected(self):
        # The old "token" was just the user id wrapped in text; it must fail.
        self.client.credentials(HTTP_AUTHORIZATION="Bearer podium_jwt_usr_lect_2")
        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 401)
