import os
import shutil
import tempfile
from unittest.mock import patch

from django.conf import settings
from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import CoHost, Session
from apps.core import features
from apps.storage import egress
from apps.storage.models import Recording, SessionMaterial


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

    def test_a_cohost_shares_and_removes_materials_like_the_host(self):
        # Full moderation parity: handing someone the room's controls must
        # include handing them the document tray, or the role is a fake.
        from apps.classrooms.models import CoHost

        CoHost.objects.create(
            id="coh_mat", session=self.session, user=self.student,
            user_name="Kofi", assigned_by=self.lecturer.id,
        )

        self._auth(self.student)
        res = self.client.post(
            self.base,
            data={"title": "Shared by cohost", "url": "https://example.com/ch",
                  "type": "link"},
            format="json",
        )
        self.assertEqual(res.status_code, 201)
        material_id = res.json()["document"]["id"]

        res = self.client.delete(f"{self.base}{material_id}/")
        self.assertEqual(res.status_code, 200)
        self.assertFalse(SessionMaterial.objects.filter(id=material_id).exists())


class RecordingApiTests(TestCase):
    """
    Server-side recording: start, poll, stop, stream -- and the rule that no
    response ever claims a recording exists unless the worker says so.

    The worker is mocked here. It is a separate container; tests that need the
    real one are the live-verification kind, not the unit kind. What matters in
    this file is that every failure path fails *closed*: no 2xx without a
    worker, no row left behind when the call died, no file URL for a file that
    is not on disk.
    """

    def setUp(self):
        self.client = APIClient()
        self.lecturer = User.objects.create(
            id="usr_rec_lect", email="rec-lect@test.com", full_name="Dr. Record",
            role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_rec_stu", email="rec-stu@test.com", full_name="Kofi Student",
            role="student",
        )
        self.cohost = User.objects.create(
            id="usr_rec_coh", email="rec-coh@test.com", full_name="Ama Co",
            role="student",
        )
        self.session = Session.objects.create(
            id="ses_rec_1", title="Recording Lab", host=self.lecturer,
            meeting_code="POD-REC1", status="active", is_active=True,
        )
        CoHost.objects.create(
            id="coh_rec", session=self.session, user=self.cohost,
            user_name="Ama Co", assigned_by=self.lecturer.id,
        )
        self.base = f"/api/v1/classrooms/sessions/{self.session.id}/recordings/"

        # MP4s are read from a scratch directory so the file routes can be
        # exercised without touching a real recording folder.
        self.media_root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.media_root, ignore_errors=True)
        self._media = self.settings(RECORDINGS_ROOT=self.media_root)
        self._media.enable()
        self.addCleanup(self._media.disable)

        features.set_enabled("recordings", True)
        self._auth(self.lecturer)

    def _auth(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    @staticmethod
    def _worker_info(status="EGRESS_STARTING", egress_id="EG_test_1", **extra):
        info = {"egress_id": egress_id, "room_name": "ses_rec_1", "status": status}
        info.update(extra)
        return info

    @staticmethod
    def _row(**overrides):
        defaults = dict(
            id="rec_seed", session_id="ses_rec_1", egress_id="EG_seed",
            room_id="ses_rec_1", lecturer_id="usr_rec_lect", class_title="Recording Lab",
            status="active", file_path="seed.mp4", started_at=timezone.now(),
        )
        defaults.update(overrides)
        return Recording.objects.create(**defaults)

    def _write_file(self, name, data=b"0123456789"):
        path = os.path.join(self.media_root, name)
        with open(path, "wb") as handle:
            handle.write(data)
        return path

    # --- flag and permissions --------------------------------------------

    def test_the_whole_surface_is_off_until_an_admin_turns_it_on(self):
        features.set_enabled("recordings", False)
        self.assertEqual(self.client.get(self.base).status_code, 503)
        self.assertEqual(
            self.client.get(self.base).json()["error"]["code"], "FEATURE_DISABLED"
        )

    def test_a_student_can_watch_but_cannot_record(self):
        self._row(id="rec_seen", status="complete")
        self._auth(self.student)
        self.assertEqual(self.client.get(self.base).status_code, 200)
        self.assertEqual(self.client.post(self.base, {}, format="json").status_code, 403)

    @patch("apps.storage.egress.start_room_composite")
    def test_a_cohost_may_record_like_the_host(self, start):
        # Full moderation parity: whoever runs the room records the room.
        start.return_value = self._worker_info(egress_id="EG_coh")
        self._auth(self.cohost)
        res = self.client.post(self.base, {}, format="json")
        self.assertEqual(res.status_code, 201)
        self.assertEqual(Recording.objects.get(session=self.session).egress_id, "EG_coh")

    # --- starting ---------------------------------------------------------

    @patch("apps.storage.egress.start_room_composite")
    def test_starting_creates_a_row_and_records_the_worker_path(self, start):
        start.return_value = self._worker_info()
        res = self.client.post(self.base, {}, format="json")
        self.assertEqual(res.status_code, 201)

        doc = res.json()["document"]
        self.assertEqual(doc["status"], "starting")
        self.assertTrue(doc["isLive"])
        self.assertFalse(doc["canPlay"])
        self.assertIsNone(doc["fileUrl"])

        row = Recording.objects.get(session=self.session)
        self.assertEqual(row.egress_id, "EG_test_1")
        # Only a basename is stored; the full path lives in the worker's world.
        self.assertEqual(os.path.dirname(row.file_path), "")
        self.assertTrue(row.file_path.endswith(".mp4"))
        worker_path = start.call_args[0][1]
        self.assertEqual(worker_path, f"{settings.RECORDINGS_CONTAINER_PATH}/{row.file_path}")

    @patch("apps.storage.egress.start_room_composite")
    def test_starting_requires_the_flag(self, start):
        features.set_enabled("recordings", False)
        res = self.client.post(self.base, {}, format="json")
        self.assertEqual(res.status_code, 503)
        self.assertFalse(start.called)

    @patch("apps.storage.egress.start_room_composite")
    def test_starting_fails_closed_when_the_worker_is_down(self, start):
        # The honest answer is "nothing is recording" -- never a 201 that
        # points at an MP4 nobody created.
        start.side_effect = egress.RecordingUnavailable(
            "The recording worker is unreachable: [WinError 10061]"
        )
        res = self.client.post(self.base, {}, format="json")
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "RECORDING_UNAVAILABLE")
        self.assertFalse(Recording.objects.filter(session=self.session).exists())

    @patch("apps.storage.egress.start_room_composite")
    def test_a_worker_refusal_is_a_400_not_a_503(self, start):
        # Reachable but says no (e.g. the room is not running): the caller can
        # fix this one, so it should not read as an outage.
        start.side_effect = egress.RecordingRejected("room 'ses_rec_1' not found")
        res = self.client.post(self.base, {}, format="json")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "RECORDING_REJECTED")

    @patch("apps.storage.egress.status")
    @patch("apps.storage.egress.start_room_composite")
    def test_pressing_record_twice_keeps_one_recording(self, start, worker_status):
        start.return_value = self._worker_info()
        worker_status.return_value = self._worker_info()
        first = self.client.post(self.base, {}, format="json")
        second = self.client.post(self.base, {}, format="json")
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 200)
        self.assertEqual(first.json()["document"]["id"], second.json()["document"]["id"])
        self.assertEqual(start.call_count, 1)
        self.assertEqual(Recording.objects.filter(session=self.session).count(), 1)

    @patch("apps.storage.egress.start_room_composite")
    def test_an_ended_session_cannot_be_recorded(self, start):
        self.session.is_active = False
        self.session.status = "ended"
        self.session.save()
        res = self.client.post(self.base, {}, format="json")
        self.assertEqual(res.status_code, 400)
        self.assertFalse(start.called)

    # --- stopping ---------------------------------------------------------

    @patch("apps.storage.egress.stop")
    @patch("apps.storage.egress.start_room_composite")
    def test_the_host_stops_the_recording(self, start, stop):
        start.return_value = self._worker_info()
        rec_id = self.client.post(self.base, {}, format="json").json()["document"]["id"]

        stop.return_value = self._worker_info(status="EGRESS_ENDING")
        res = self.client.post(f"{self.base}{rec_id}/stop/", {}, format="json")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["document"]["status"], "ending")
        stop.assert_called_once_with("EG_test_1")

    @patch("apps.storage.egress.stop")
    def test_stopping_something_already_stopped_is_a_no_op(self, stop):
        self._row(id="rec_done", status="complete", file_path="done.mp4")
        res = self.client.post(f"{self.base}rec_done/stop/", {}, format="json")
        self.assertEqual(res.status_code, 200)
        self.assertFalse(stop.called)

    @patch("apps.storage.egress.stop")
    def test_a_student_cannot_stop_a_recording(self, stop):
        self._row(id="rec_live")
        self._auth(self.student)
        res = self.client.post(f"{self.base}rec_live/stop/", {}, format="json")
        self.assertEqual(res.status_code, 403)
        self.assertFalse(stop.called)

    @patch("apps.storage.egress.stop")
    def test_stopping_fails_closed_when_the_worker_is_down(self, stop):
        self._row(id="rec_live")
        stop.side_effect = egress.RecordingUnavailable("worker unreachable")
        res = self.client.post(f"{self.base}rec_live/stop/", {}, format="json")
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "RECORDING_UNAVAILABLE")
        # Still live as far as we know: the caller may retry, so the row must
        # not have been flipped to something finished.
        self.assertEqual(Recording.objects.get(id="rec_live").status, "active")

    # --- polling ----------------------------------------------------------

    @patch("apps.storage.egress.status")
    def test_the_poll_finalises_a_finished_recording(self, worker_status):
        self._row(id="rec_poll")
        worker_status.return_value = self._worker_info(
            status="EGRESS_COMPLETE",
            started_at=1792900000,
            ended_at=1792900125,
            file_results=[{"filename": "/out/recordings/seed.mp4",
                            "duration": 125, "size": 4096}],
        )
        res = self.client.get(f"{self.base}rec_poll/")
        self.assertEqual(res.status_code, 200)

        doc = res.json()["document"]
        self.assertEqual(doc["status"], "complete")
        self.assertFalse(doc["isLive"])
        self.assertEqual(doc["durationSeconds"], 125)
        self.assertEqual(doc["sizeBytes"], 4096)
        # Nothing on disk in this test, so no playback URL is offered.
        self.assertFalse(doc["canPlay"])
        self.assertIsNone(doc["fileUrl"])

        row = Recording.objects.get(id="rec_poll")
        self.assertEqual(row.status, "complete")
        self.assertIsNotNone(row.ended_at)

    @patch("apps.storage.egress.status")
    def test_a_recording_with_a_file_on_disk_offers_playback(self, worker_status):
        self._row(id="rec_play", file_path="seed.mp4")
        self._write_file("seed.mp4", b"mp4-bytes")
        worker_status.return_value = self._worker_info(
            status="EGRESS_COMPLETE",
            file_results=[{"filename": "/out/recordings/seed.mp4", "size": 9}],
        )
        doc = self.client.get(f"{self.base}rec_play/").json()["document"]
        self.assertTrue(doc["canPlay"])
        self.assertIn(f"/sessions/{self.session.id}/recordings/rec_play/file/", doc["fileUrl"])

    @patch("apps.storage.egress.status")
    def test_a_recording_the_worker_forgets_settles_on_the_file(self, worker_status):
        # Fresh Redis, rebuilt container: no egress memory, but the MP4 is
        # there -- the file wins. With no file, the row records the loss.
        self._row(id="rec_forget", file_path="seed.mp4")
        self._write_file("seed.mp4")
        worker_status.return_value = {}
        doc = self.client.get(f"{self.base}rec_forget/").json()["document"]
        self.assertEqual(doc["status"], "complete")

        self._row(id="rec_gone", file_path="vanished.mp4", egress_id="EG_gone")
        doc = self.client.get(f"{self.base}rec_gone/").json()["document"]
        self.assertEqual(doc["status"], "failed")
        self.assertIn("no longer reports", doc["error"])

    @patch("apps.storage.egress.status")
    def test_a_read_survives_a_worker_that_is_down(self, worker_status):
        self._row(id="rec_stuck")
        worker_status.side_effect = egress.RecordingUnavailable("worker unreachable")
        res = self.client.get(f"{self.base}rec_stuck/")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["document"]["status"], "active")

    def test_an_unknown_recording_is_404_everywhere(self):
        self.assertEqual(self.client.get(f"{self.base}rec_missing/").status_code, 404)
        self.assertEqual(
            self.client.post(f"{self.base}rec_missing/stop/", {}, format="json").status_code,
            404,
        )
        self.assertEqual(self.client.get(f"{self.base}rec_missing/file/").status_code, 404)

    # --- streaming --------------------------------------------------------

    def test_the_file_route_serves_the_whole_file_then_ranges(self):
        self._write_file("full.mp4", b"0123456789")
        self._row(id="rec_full", status="complete", file_path="full.mp4")

        whole = self.client.get(f"{self.base}rec_full/file/")
        self.assertEqual(whole.status_code, 200)
        self.assertEqual(whole["Content-Type"], "video/mp4")
        self.assertEqual(whole["Accept-Ranges"], "bytes")
        self.assertEqual(b"".join(whole.streaming_content), b"0123456789")

        # The seek bar depends on this: without 206 a browser downloads the
        # whole lecture before it can jump to minute forty.
        part = self.client.get(f"{self.base}rec_full/file/", HTTP_RANGE="bytes=2-5")
        self.assertEqual(part.status_code, 206)
        self.assertEqual(part["Content-Range"], "bytes 2-5/10")
        self.assertEqual(part["Content-Length"], "4")
        self.assertEqual(b"".join(part.streaming_content), b"2345")

        tail = self.client.get(f"{self.base}rec_full/file/", HTTP_RANGE="bytes=-3")
        self.assertEqual(tail.status_code, 206)
        self.assertEqual(b"".join(tail.streaming_content), b"789")

    def test_a_range_outside_the_file_is_416(self):
        self._write_file("short.mp4", b"0123456789")
        self._row(id="rec_short", status="complete", file_path="short.mp4")
        res = self.client.get(f"{self.base}rec_short/file/", HTTP_RANGE="bytes=50-60")
        self.assertEqual(res.status_code, 416)
        self.assertEqual(res["Content-Range"], "bytes */10")

    def test_a_malformed_range_still_plays_the_file(self):
        self._write_file("lenient.mp4", b"abc")
        self._row(id="rec_lenient", status="complete", file_path="lenient.mp4")
        res = self.client.get(f"{self.base}rec_lenient/file/", HTTP_RANGE="bytes=oops")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(b"".join(res.streaming_content), b"abc")

    def test_a_file_that_is_gone_is_404_not_a_broken_player(self):
        # Row survives, disk does not (someone emptied the folder): never hand
        # the player a URL that 404s halfway through.
        self._row(id="rec_ghost", status="complete", file_path="ghost.mp4")
        res = self.client.get(f"{self.base}rec_ghost/file/")
        self.assertEqual(res.status_code, 404)

        doc = self.client.get(f"{self.base}rec_ghost/").json()["document"]
        self.assertFalse(doc["canPlay"])
        self.assertIsNone(doc["fileUrl"])

    def test_a_stored_path_can_never_escape_the_recordings_root(self):
        self._row(id="rec_escape", status="complete",
                  file_path=os.path.join("..", "..", "secrets.mp4"))
        res = self.client.get(f"{self.base}rec_escape/file/")
        self.assertEqual(res.status_code, 404)
