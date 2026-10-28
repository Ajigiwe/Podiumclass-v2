"""
A host's own class register, downloaded.

The bulk export in ``export_views`` is machine-to-machine and service-key only,
so a lecturer pressing "Export" in the app got nothing. These pin the
host-facing endpoint: JWT authentication, the host's own class, joined/left
times and check counts, in CSV and in a real .xlsx.
"""

import io
import zipfile
from datetime import timedelta

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.attendance.models import Attendance
from apps.classrooms.models import Session


class HostRosterExportTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.host = User.objects.create(
            id="usr_exp_host", email="exp-host@test.com",
            full_name="Dr. Export", role="lecturer",
        )
        self.other = User.objects.create(
            id="usr_exp_other", email="exp-other@test.com",
            full_name="Dr. Other", role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_exp_stud", email="exp-stud@test.com",
            full_name="Ama Mensah", role="student", index_number="10982341",
        )
        self.session = Session.objects.create(
            id="exp-session", title="Week 4 Networks", host=self.host,
            lecturer=self.host, meeting_code="EXP-1", course="CS301",
            status="ended", is_active=False,
            started_at=timezone.now() - timedelta(hours=1),
            ended_at=timezone.now(),
        )
        joined = timezone.now() - timedelta(minutes=50)
        Attendance.objects.create(
            id="att_exp_1", session=self.session, student=self.student,
            student_name="Ama Mensah", student_index_number="10982341",
            is_present=True, joined_at=joined,
            left_at=joined + timedelta(minutes=35),
            total_verifications_sent=3, total_verifications_completed=2,
            verification_percentage=67, last_responded_at=joined,
        )
        self.url = "/api/v1/attendance/sessions/exp-session/export"

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def test_host_downloads_csv_with_times_and_checks(self):
        self._auth(self.host)
        res = self.client.get(self.url, {"format": "csv"})
        self.assertEqual(res.status_code, 200)
        self.assertIn("text/csv", res["Content-Type"])
        self.assertIn("attachment", res["Content-Disposition"])

        body = res.content.decode("utf-8-sig")
        self.assertIn("Student,Index number,Joined,Left,Minutes in class", body)
        self.assertIn("Ama Mensah", body)
        self.assertIn("10982341", body)
        self.assertIn("35", body.splitlines()[1].split(",")[4])
        self.assertIn("2,3,67,Yes", body)

    def test_host_downloads_a_real_xlsx(self):
        self._auth(self.host)
        res = self.client.get(self.url, {"format": "xlsx"})
        self.assertEqual(res.status_code, 200)
        self.assertIn("spreadsheetml", res["Content-Type"])
        self.assertTrue(res.content.startswith(b"PK"), "xlsx must be a zip archive")

        with zipfile.ZipFile(io.BytesIO(res.content)) as archive:
            names = set(archive.namelist())
            self.assertIn("xl/workbook.xml", names)
            sheet = archive.read("xl/worksheets/sheet1.xml").decode("utf-8")
        self.assertIn("Ama Mensah", sheet)
        self.assertIn("Checks passed", sheet)

    def test_another_lecturer_cannot_read_the_register(self):
        self._auth(self.other)
        self.assertEqual(self.client.get(self.url, {"format": "csv"}).status_code, 403)

    def test_student_cannot_export_the_class(self):
        self._auth(self.student)
        self.assertEqual(self.client.get(self.url, {"format": "csv"}).status_code, 403)

    def test_unknown_format_is_rejected(self):
        self._auth(self.host)
        res = self.client.get(self.url, {"format": "pdf"})
        self.assertEqual(res.status_code, 400)
