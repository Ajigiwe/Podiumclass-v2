from django.test import TestCase
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.storage.models import SessionMaterial


class MaterialApiTests(TestCase):
    """The classroom materials endpoint: read shape, sharing, and removal."""

    def setUp(self):
        self.client = APIClient()
        self.lecturer = User.objects.create(
            id="usr_mat_lect", email="mat-lect@test.com", full_name="Dr. Asante",
            role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_mat_stu", email="mat-stu@test.com", full_name="Kofi", role="student",
        )
        self.other_student = User.objects.create(
            id="usr_mat_stu2", email="mat-stu2@test.com", full_name="Ama", role="student",
        )
        self.session = Session.objects.create(
            id="ses_materials_1", title="Distributed Systems", host=self.lecturer,
            meeting_code="POD-MAT1", status="active", is_active=True,
        )
        self.base = f"/api/v1/classrooms/sessions/{self.session.id}/materials/"

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def test_list_uses_document_envelope_and_ui_shape(self):
        SessionMaterial.objects.create(
            id="mat_1", session=self.session, title="Week 5 Slides",
            file_url="https://example.com/slides.pdf", file_type="pdf",
            file_size_bytes=2_500_000, uploaded_by=self.lecturer.id,
        )
        self._auth(self.student)
        res = self.client.get(self.base)
        self.assertEqual(res.status_code, 200)
        payload = res.json()
        # Collections must arrive as { documents: [...] } -- the SPA reads that
        # key, so a bare array would be silently dropped by the client.
        self.assertIn("documents", payload)
        self.assertNotIn("document", payload)
        row = payload["documents"][0]
        self.assertEqual(row["id"], "mat_1")
        self.assertEqual(row["title"], "Week 5 Slides")
        self.assertEqual(row["url"], "https://example.com/slides.pdf")
        self.assertEqual(row["type"], "pdf")
        self.assertEqual(row["size"], "2.4 MB")
        self.assertEqual(row["icon"], "fas fa-file-pdf")
        self.assertEqual(row["sharedBy"], self.lecturer.id)

    def test_legacy_mime_type_is_normalised_for_the_modal(self):
        SessionMaterial.objects.create(
            id="mat_2", session=self.session, title="Notes",
            file_url="https://example.com/n", file_type="application/pdf",
            uploaded_by=self.lecturer.id,
        )
        self._auth(self.student)
        row = self.client.get(self.base).json()["documents"][0]
        self.assertEqual(row["type"], "pdf")
        self.assertEqual(row["size"], "")

    def test_host_can_share_a_resource(self):
        self._auth(self.lecturer)
        res = self.client.post(
            self.base,
            data={"title": "Revision Pack", "url": "https://example.com/pack.pdf",
                  "type": "pdf"},
            format="json",
        )
        self.assertEqual(res.status_code, 201)
        body = res.json()
        self.assertIn("document", body)
        self.assertEqual(body["document"]["url"], "https://example.com/pack.pdf")
        self.assertEqual(body["document"]["type"], "pdf")
        self.assertEqual(SessionMaterial.objects.filter(session=self.session).count(), 1)

    def test_javascript_link_is_rejected(self):
        # The modal renders `url` straight into an anchor.
        self._auth(self.lecturer)
        res = self.client.post(
            self.base,
            data={"title": "Evil", "url": "javascript:alert(1)", "type": "link"},
            format="json",
        )
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "VALIDATION_ERROR")
        self.assertEqual(SessionMaterial.objects.count(), 0)

    def test_missing_title_is_rejected(self):
        self._auth(self.lecturer)
        res = self.client.post(
            self.base, data={"url": "https://example.com/x"}, format="json"
        )
        self.assertEqual(res.status_code, 400)

    def test_student_cannot_share_a_resource(self):
        self._auth(self.student)
        res = self.client.post(
            self.base,
            data={"title": "Nope", "url": "https://example.com/x", "type": "link"},
            format="json",
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(SessionMaterial.objects.count(), 0)

    def test_anonymous_cannot_read_materials(self):
        res = self.client.get(self.base)
        self.assertEqual(res.status_code, 401)

    def test_host_can_remove_a_resource(self):
        SessionMaterial.objects.create(
            id="mat_del", session=self.session, title="Old",
            file_url="https://example.com/o", uploaded_by=self.lecturer.id,
        )
        self._auth(self.lecturer)
        res = self.client.delete(f"{self.base}mat_del/")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["document"]["deleted"], True)
        self.assertFalse(SessionMaterial.objects.filter(id="mat_del").exists())

    def test_unrelated_student_cannot_remove_a_resource(self):
        SessionMaterial.objects.create(
            id="mat_keep", session=self.session, title="Keep",
            file_url="https://example.com/k", uploaded_by=self.lecturer.id,
        )
        self._auth(self.other_student)
        res = self.client.delete(f"{self.base}mat_keep/")
        self.assertEqual(res.status_code, 403)
        self.assertTrue(SessionMaterial.objects.filter(id="mat_keep").exists())

    def test_material_of_another_session_is_not_deletable(self):
        other_session = Session.objects.create(
            id="ses_other_mat", title="Other", host=self.lecturer,
            meeting_code="POD-MAT2", status="active", is_active=True,
        )
        SessionMaterial.objects.create(
            id="mat_foreign", session=other_session, title="Foreign",
            file_url="https://example.com/f", uploaded_by=self.lecturer.id,
        )
        self._auth(self.lecturer)
        res = self.client.delete(f"{self.base}mat_foreign/")
        self.assertEqual(res.status_code, 404)
        self.assertTrue(SessionMaterial.objects.filter(id="mat_foreign").exists())

    def test_unknown_session_returns_404(self):
        self._auth(self.lecturer)
        res = self.client.get("/api/v1/classrooms/sessions/ses_missing/materials/")
        self.assertEqual(res.status_code, 404)
        self.assertEqual(res.json()["error"]["code"], "NOT_FOUND")
