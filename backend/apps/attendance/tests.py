import json
from django.test import TestCase, override_settings
from django.urls import reverse
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.attendance.models import Attendance

# A throwaway credential for the test process only. The real service key is
# never written into source; it lives in the environment.
TEST_API_KEY = "test-service-key"
AUTH = {"HTTP_AUTHORIZATION": f"Bearer {TEST_API_KEY}"}


@override_settings(ATTENDANCE_API_KEY=TEST_API_KEY)
class DocumentObjectResponseTest(TestCase):
    def setUp(self):
        self.host = User.objects.create_user(
            email="lecturer@university.edu",
            full_name="Prof. Mensah",
            role="lecturer"
        )
        self.session = Session.objects.create(
            id="test-session-101",
            title="Introduction to Computer Science",
            host=self.host,
            meeting_code="CS101-ABC",
            course="CS101",
            program="BSc Computer Science",
            status="ended",
            duration_minutes=60,
            participant_count=1
        )
        self.student = User.objects.create_user(
            email="student@university.edu",
            full_name="Kwame Asante",
            role="student",
            index_number="CS/2026/042"
        )
        self.attendance = Attendance.objects.create(
            id="att-101",
            session=self.session,
            student=self.student,
            student_name=self.student.full_name,
            student_index_number=self.student.index_number,
            total_verifications_sent=4,
            total_verifications_completed=4,
            verification_percentage=100,
            is_present=True
        )

    def test_sessions_list_responds_in_documents_object(self):
        url = reverse('attendance-sessions')
        response = self.client.get(url, **AUTH)
        self.assertEqual(response.status_code, 200)
        
        data = response.json()
        # Verify the Document Object structure
        self.assertIn("documents", data, "Response must contain 'documents' array")
        self.assertIn("pagination", data, "Response must contain 'pagination' metadata")
        self.assertEqual(len(data["documents"]), 1)
        self.assertEqual(data["documents"][0]["sessionId"], "test-session-101")

    def test_session_detail_responds_in_document_object(self):
        url = reverse('attendance-session-detail', kwargs={'session_id': 'test-session-101'})
        response = self.client.get(url, **AUTH)
        self.assertEqual(response.status_code, 200)
        
        data = response.json()
        # Verify the Document Object structure
        self.assertIn("document", data, "Response must be enveloped in 'document' object")
        self.assertIn("session", data["document"])
        self.assertIn("summary", data["document"])
        self.assertIn("records", data["document"])
        self.assertEqual(data["document"]["summary"]["present"], 1)
        self.assertEqual(data["document"]["records"][0]["studentName"], "Kwame Asante")

    def test_unauthorized_responds_in_error_document_object(self):
        url = reverse('attendance-sessions')
        response = self.client.get(url, HTTP_AUTHORIZATION="Bearer invalid-secret")
        # These routes serve the browser SPA as well as the service, so an
        # invalid credential is 401 ("not signed in") rather than 403.
        self.assertEqual(response.status_code, 401)

        data = response.json()
        self.assertIn("error", data, "Error response must be enveloped in 'error' object")
        self.assertEqual(data["error"]["code"], "INVALID_TOKEN")
