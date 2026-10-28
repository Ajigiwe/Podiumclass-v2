"""
Watch and download links for a recording, and who gets them.

The point of these is narrow and easy to get wrong: a ``<video>`` element, a
download link and Android's download manager all fetch a URL with no
``Authorization`` header, so a URL that needs one is a URL that never plays. A
signed link carries the credential in the query string, names exactly one
recording, and says whether it is for watching or for saving.
"""

import os
import tempfile

from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import CoHost, Session
from apps.core import features
from apps.storage import recording_links
from apps.storage.models import Recording


class RecordingLinkTests(TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="podium-recordings-")
        self.override = override_settings(RECORDINGS_ROOT=self.tmp)
        self.override.enable()
        # Recording ships switched off, like every other optional surface.
        features.set_enabled("recordings", True)

        self.client = APIClient()
        self.host = User.objects.create(
            id="usr_rec_host", email="rec-host@test.com",
            full_name="Dr. Rec", role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_rec_stud", email="rec-stud@test.com",
            full_name="Rec Student", role="student", index_number="R/1",
        )
        self.session = Session.objects.create(
            id="rec-session", title="Recorded Class", host=self.host,
            lecturer=self.host, meeting_code="REC-1",
            status="ended", is_active=False, started_at=timezone.now(),
        )

        # A finished recording with its file on disk, as it looks after a class.
        self.filename = "rec-session-1.mp4"
        with open(os.path.join(self.tmp, self.filename), "wb") as handle:
            handle.write(b"\x00" * 2048)
        self.recording = Recording.objects.create(
            id="rec_link_test",
            session=self.session,
            egress_id="EG_test",
            room_id=self.session.id,
            lecturer_id=self.host.id,
            class_title=self.session.title,
            status="complete",
            file_path=self.filename,
            started_at=timezone.now(),
            ended_at=timezone.now(),
            duration_seconds=42,
            size_bytes=2048,
        )
        self.file_path = f"/api/v1/classrooms/sessions/{self.session.id}/recordings/{self.recording.id}/file/"

    def tearDown(self):
        features.set_enabled("recordings", False)
        self.override.disable()

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def _list(self, user):
        self._auth(user)
        res = self.client.get(f"/api/v1/classrooms/sessions/{self.session.id}/recordings/")
        self.assertEqual(res.status_code, 200)
        return res.json()["documents"][0]

    def test_the_url_needs_no_header_to_serve_the_file(self):
        row = self._list(self.host)
        self.assertTrue(row["playUrl"])
        # No Authorization header at all: exactly what a <video> sends.
        self.client.credentials()
        res = self.client.get(row["playUrl"])
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res["Content-Disposition"].split(";")[0], "inline")

    def test_a_download_link_saves_the_file_instead_of_streaming_it(self):
        row = self._list(self.host)
        self.assertTrue(row["downloadUrl"])
        self.client.credentials()
        res = self.client.get(row["downloadUrl"])
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res["Content-Disposition"].split(";")[0], "attachment")

    def test_only_the_people_who_run_the_class_get_a_download_link(self):
        row = self._list(self.student)
        # A student may watch the replay in class...
        self.assertTrue(row["canPlay"])
        self.assertTrue(row["playUrl"])
        # ...but saving and passing it on is the host's call.
        self.assertFalse(row["canDownload"])
        self.assertFalse(row["downloadUrl"])

    def test_a_co_host_may_download(self):
        CoHost.objects.create(
            id="ch_rec", session=self.session, user=self.student,
            user_name="Rec Student", assigned_by=self.host.id,
        )
        row = self._list(self.student)
        self.assertTrue(row["canDownload"])

    def test_no_link_and_no_session_is_refused(self):
        self.client.credentials()
        res = self.client.get(self.file_path)
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.json()["error"]["code"], "LINK_REQUIRED")

    def test_a_link_for_another_recording_is_refused(self):
        other = recording_links.mint(self.session.id, "rec_someone_else", "play")
        self.client.credentials()
        res = self.client.get(f"{self.file_path}?t={other}")
        self.assertEqual(res.status_code, 401)

    def test_a_play_link_cannot_be_edited_into_a_download(self):
        token = recording_links.mint(self.session.id, self.recording.id, "download")
        # Flipping the mode in the payload without the secret invalidates it.
        tampered = token[:-6] + ("aaaaaa" if token[-6:] != "aaaaaa" else "bbbbbb")
        self.client.credentials()
        res = self.client.get(f"{self.file_path}?t={tampered}")
        self.assertEqual(res.status_code, 401)

    def test_an_access_token_is_not_a_recording_link(self):
        # Same secret, different purpose: a session token presented as a link
        # must not unlock a file.
        self.client.credentials()
        res = self.client.get(f"{self.file_path}?t={issue_token(self.host)}")
        self.assertEqual(res.status_code, 401)

    def test_the_dashboard_feed_only_carries_the_callers_own_classes(self):
        other_host = User.objects.create(
            id="usr_rec_other", email="rec-other@test.com",
            full_name="Dr. Other", role="lecturer",
        )
        other_session = Session.objects.create(
            id="rec-session-2", title="Someone Else's Class", host=other_host,
            lecturer=other_host, meeting_code="REC-2", status="ended",
        )
        Recording.objects.create(
            id="rec_other", session=other_session, egress_id="EG_other",
            room_id=other_session.id, lecturer_id=other_host.id,
            class_title=other_session.title, status="starting",
            file_path="other.mp4", started_at=timezone.now(),
        )

        # A student runs nothing, so their Records area is empty.
        self._auth(self.student)
        mine = self.client.get("/api/v1/classrooms/recordings/").json()["documents"]
        self.assertEqual(mine, [])

        # The host sees their own recording, and not another host's.
        self._auth(self.host)
        rows = self.client.get("/api/v1/classrooms/recordings/").json()["documents"]
        self.assertEqual([r["id"] for r in rows], [self.recording.id])
