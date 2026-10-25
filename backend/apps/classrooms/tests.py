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

class ClassroomModerationTests(TestCase):
    """
    Mute All, Smart Mic, the per-media approval policy and the request inbox.

    These settings used to live only in the browser, so a host who refreshed
    unlocked the room and a late joiner never saw it. They are durable now.
    """

    def setUp(self):
        self.client = APIClient()
        self.host = User.objects.create(
            id="usr_mod_host",
            email="host@mod.test",
            full_name="Dr. Moderator",
            role="lecturer",
        )
        self.student = User.objects.create(
            id="usr_mod_student",
            email="student@mod.test",
            full_name="Kofi Participant",
            role="student",
        )
        self.session = Session.objects.create(
            id="ses_mod_1",
            title="Moderation Lab",
            host=self.host,
            meeting_code="POD-MOD1",
            status="active",
            is_active=True,
        )
        self.as_user(self.host)

    def as_user(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def url(self, suffix=""):
        return f"/api/v1/classrooms/sessions/{self.session.id}/{suffix}"

    # --- room state -----------------------------------------------------

    def test_session_reports_defaults(self):
        res = self.client.get(self.url())
        self.assertEqual(res.status_code, 200)
        doc = res.json()["document"]
        self.assertFalse(doc["isMutedAll"])
        self.assertFalse(doc["autoApproveMic"])
        self.assertEqual(doc["permissions"], {"mic": "free", "camera": "free", "screen": "free"})

    def test_host_persists_mute_all_smart_mic_and_policy(self):
        res = self.client.patch(
            self.url(),
            data={
                "isMutedAll": True,
                "autoApproveMic": True,
                "permissions": {"mic": "approval", "camera": "free", "screen": "approval"},
            },
            format="json",
        )
        self.assertEqual(res.status_code, 200)

        self.session.refresh_from_db()
        self.assertTrue(self.session.is_muted_all)
        self.assertTrue(self.session.auto_approve_mic)

        doc = self.client.get(self.url()).json()["document"]
        self.assertTrue(doc["isMutedAll"])
        self.assertTrue(doc["autoApproveMic"])
        self.assertEqual(doc["permissions"], {"mic": "approval", "camera": "free", "screen": "approval"})

    def test_partial_policy_update_keeps_the_other_modes(self):
        self.client.patch(
            self.url(), data={"permissions": {"mic": "approval"}}, format="json"
        )
        doc = self.client.get(self.url()).json()["document"]
        self.assertEqual(doc["permissions"], {"mic": "approval", "camera": "free", "screen": "free"})

    def test_student_cannot_change_moderation(self):
        self.as_user(self.student)
        res = self.client.patch(self.url(), data={"isMutedAll": True}, format="json")
        self.assertEqual(res.status_code, 403)
        self.session.refresh_from_db()
        self.assertFalse(self.session.is_muted_all)

    def test_invalid_moderation_values_are_rejected(self):
        res = self.client.patch(
            self.url(), data={"permissions": {"mic": "sometimes"}}, format="json"
        )
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "VALIDATION_ERROR")

        res = self.client.patch(self.url(), data={"isMutedAll": "yes"}, format="json")
        self.assertEqual(res.status_code, 400)

        res = self.client.patch(self.url(), data={"verificationCount": "abc"}, format="json")
        self.assertEqual(res.status_code, 400)

    def test_mute_all_revokes_standing_mic_grants(self):
        from apps.classrooms.models import ParticipantPermission

        ParticipantPermission.objects.create(
            id="prm_keep_1", session=self.session, participant_id=self.student.id,
            mic_permission=True, granted_by=self.host.id,
        )
        self.client.patch(self.url(), data={"isMutedAll": True}, format="json")
        row = ParticipantPermission.objects.get(session=self.session, participant_id=self.student.id)
        self.assertFalse(row.mic_permission)
        self.assertIsNone(row.granted_by)

        # Re-sending the same state must not silently re-lock anything.
        self.client.patch(self.url(), data={"isMutedAll": True}, format="json")
        self.assertFalse(
            ParticipantPermission.objects.get(
                session=self.session, participant_id=self.student.id
            ).mic_permission
        )

    # --- per-participant grants ----------------------------------------

    def test_participant_without_a_row_is_denied(self):
        self.as_user(self.student)
        doc = self.client.get(self.url("permissions/")).json()["document"]
        self.assertEqual(doc["participantId"], self.student.id)
        self.assertEqual((doc["mic"], doc["camera"], doc["screen"]), (False, False, False))

    def test_host_grants_and_revokes_a_permission(self):
        res = self.client.patch(
            self.url("permissions/"),
            data={"participantId": self.student.id, "mic": True},
            format="json",
        )
        self.assertEqual(res.status_code, 200)
        self.assertTrue(res.json()["document"]["mic"])
        self.assertEqual(res.json()["document"]["grantedBy"], self.host.id)

        self.as_user(self.student)
        doc = self.client.get(self.url("permissions/")).json()["document"]
        self.assertTrue(doc["mic"])

        self.as_user(self.host)
        self.client.patch(
            self.url("permissions/"),
            data={"participantId": self.student.id, "mic": False},
            format="json",
        )
        self.as_user(self.student)
        self.assertFalse(self.client.get(self.url("permissions/")).json()["document"]["mic"])

    def test_a_student_cannot_grant_themselves_permissions(self):
        self.as_user(self.student)
        res = self.client.patch(
            self.url("permissions/"),
            data={"participantId": self.student.id, "mic": True},
            format="json",
        )
        self.assertEqual(res.status_code, 403)

    def test_grant_requires_a_participant_and_a_known_key(self):
        res = self.client.patch(self.url("permissions/"), data={"mic": True}, format="json")
        self.assertEqual(res.status_code, 400)

        res = self.client.patch(
            self.url("permissions/"),
            data={"participantId": "usr_nobody", "telepathy": True},
            format="json",
        )
        self.assertEqual(res.status_code, 400)

    def test_permissions_for_an_unknown_session_404(self):
        res = self.client.get("/api/v1/classrooms/sessions/ses_missing/permissions/")
        self.assertEqual(res.status_code, 404)

    # --- request inbox --------------------------------------------------

    def test_request_lands_in_the_host_inbox_as_pending(self):
        self.as_user(self.student)
        res = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "mic"}, format="json"
        )
        self.assertEqual(res.status_code, 201)
        self.assertEqual(res.json()["document"]["status"], "pending")
        self.assertEqual(res.json()["document"]["participantName"], "Kofi Participant")

        self.as_user(self.host)
        docs = self.client.get(self.url("permissions/requests/")).json()["documents"]
        self.assertEqual(len(docs), 1)
        self.assertEqual(docs[0]["requestType"], "mic")

    def test_repeated_clicks_do_not_duplicate_pending_requests(self):
        self.as_user(self.student)
        first = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "mic"}, format="json"
        )
        second = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "mic"}, format="json"
        )
        self.assertEqual(first.json()["document"]["id"], second.json()["document"]["id"])
        self.as_user(self.host)
        self.assertEqual(len(self.client.get(self.url("permissions/requests/")).json()["documents"]), 1)

    def test_smart_mic_approves_microphone_asks_server_side(self):
        self.client.patch(self.url(), data={"autoApproveMic": True}, format="json")

        self.as_user(self.student)
        res = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "mic"}, format="json"
        )
        self.assertEqual(res.status_code, 201)
        self.assertEqual(res.json()["document"]["status"], "approved")
        self.assertTrue(self.client.get(self.url("permissions/")).json()["document"]["mic"])

        # Nothing waits in the inbox for the host to click.
        self.as_user(self.host)
        self.assertEqual(self.client.get(self.url("permissions/requests/")).json()["documents"], [])

    def test_smart_mic_only_covers_the_microphone(self):
        self.client.patch(self.url(), data={"autoApproveMic": True}, format="json")
        self.as_user(self.student)
        res = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "camera"}, format="json"
        )
        self.assertEqual(res.json()["document"]["status"], "pending")
        self.assertFalse(self.client.get(self.url("permissions/")).json()["document"]["camera"])

    def test_inbox_is_host_only(self):
        self.as_user(self.student)
        res = self.client.get(self.url("permissions/requests/"))
        self.assertEqual(res.status_code, 403)

    def test_reviewing_an_approval_grants_the_permission(self):
        self.as_user(self.student)
        req_id = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "camera"}, format="json"
        ).json()["document"]["id"]

        self.as_user(self.host)
        res = self.client.patch(
            self.url(f"permissions/requests/{req_id}/"), data={"status": "approved"}, format="json"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["document"]["status"], "approved")

        self.as_user(self.student)
        self.assertTrue(self.client.get(self.url("permissions/")).json()["document"]["camera"])

        # Reviewed requests cannot be flipped back and forth.
        self.as_user(self.host)
        res = self.client.patch(
            self.url(f"permissions/requests/{req_id}/"), data={"status": "rejected"}, format="json"
        )
        self.assertEqual(res.status_code, 400)

    def test_rejection_leaves_the_permission_denied(self):
        self.as_user(self.student)
        req_id = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "screen"}, format="json"
        ).json()["document"]["id"]

        self.as_user(self.host)
        res = self.client.patch(
            self.url(f"permissions/requests/{req_id}/"), data={"status": "rejected"}, format="json"
        )
        self.assertEqual(res.status_code, 200)

        self.as_user(self.student)
        self.assertFalse(self.client.get(self.url("permissions/")).json()["document"]["screen"])

    def test_a_student_cannot_review_requests(self):
        self.as_user(self.student)
        req_id = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "mic"}, format="json"
        ).json()["document"]["id"]
        res = self.client.patch(
            self.url(f"permissions/requests/{req_id}/"), data={"status": "approved"}, format="json"
        )
        self.assertEqual(res.status_code, 403)

    def test_review_validates_status_and_request_id(self):
        self.as_user(self.student)
        req_id = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "mic"}, format="json"
        ).json()["document"]["id"]

        self.as_user(self.host)
        res = self.client.patch(
            self.url(f"permissions/requests/{req_id}/"), data={"status": "maybe"}, format="json"
        )
        self.assertEqual(res.status_code, 400)

        res = self.client.patch(
            self.url("permissions/requests/prq_missing/"), data={"status": "approved"}, format="json"
        )
        self.assertEqual(res.status_code, 404)

    def test_unknown_request_type_is_rejected(self):
        self.as_user(self.student)
        res = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "telepathy"}, format="json"
        )
        self.assertEqual(res.status_code, 400)


class CoHostTests(TestCase):
    """
    Co-hosts: who may hand out the role, what it grants, and where it stops.

    The split in ``apps.classrooms.access`` exists so a co-host has full
    moderation parity without gaining authority over the session -- a host must
    never be able to be locked out of their own room by someone they trusted
    with a mute button. Most of these tests are about that boundary.
    """

    def setUp(self):
        self.client = APIClient()
        self.host = User.objects.create(
            id="usr_ch_host", email="host@ch.test", full_name="Dr. Host", role="lecturer"
        )
        self.student = User.objects.create(
            id="usr_ch_student", email="stu@ch.test", full_name="Kofi Co", role="student"
        )
        self.bystander = User.objects.create(
            id="usr_ch_bystander", email="by@ch.test", full_name="Ama By", role="student"
        )
        self.session = Session.objects.create(
            id="ses_ch_1",
            title="Co-host Lab",
            host=self.host,
            meeting_code="POD-CH1",
            status="active",
            is_active=True,
        )
        self.as_user(self.host)

    def as_user(self, user):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(user)}")

    def url(self, suffix=""):
        return f"/api/v1/classrooms/sessions/{self.session.id}/{suffix}"

    def appoint(self, user_id=None):
        return self.client.post(
            self.url("cohosts/"), data={"userId": user_id or self.student.id}, format="json"
        )

    # --- appointment -----------------------------------------------------

    def test_host_appoints_a_cohost_and_the_list_reads_it_back(self):
        res = self.appoint()
        self.assertEqual(res.status_code, 201)
        doc = res.json()["document"]
        self.assertEqual(doc["userId"], self.student.id)
        self.assertEqual(doc["userName"], "Kofi Co")
        self.assertEqual(doc["assignedBy"], self.host.id)

        listing = self.client.get(self.url("cohosts/")).json()
        self.assertEqual(len(listing["documents"]), 1)
        self.assertEqual(listing["documents"][0]["userId"], self.student.id)

    def test_a_student_may_read_the_list_because_the_roster_badge_needs_it(self):
        self.appoint()
        self.as_user(self.bystander)
        res = self.client.get(self.url("cohosts/"))
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["documents"][0]["userId"], self.student.id)

    def test_a_cohost_cannot_hand_the_role_to_someone_else(self):
        self.appoint()
        self.as_user(self.student)
        res = self.client.post(
            self.url("cohosts/"), data={"userId": self.bystander.id}, format="json"
        )
        self.assertEqual(res.status_code, 403)
        self.assertFalse(
            self.session.co_hosts.filter(user_id=self.bystander.id, is_active=True).exists()
        )

    def test_a_plain_student_cannot_appoint(self):
        self.as_user(self.bystander)
        self.assertEqual(self.appoint().status_code, 403)

    def test_appointing_the_host_is_rejected(self):
        res = self.appoint(self.host.id)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "VALIDATION_ERROR")

    def test_appointing_an_unknown_user_is_rejected(self):
        self.assertEqual(self.appoint("usr_nobody").status_code, 400)

    def test_appoint_requires_a_user_id(self):
        res = self.client.post(self.url("cohosts/"), data={}, format="json")
        self.assertEqual(res.status_code, 400)

    def test_the_emptied_list_reads_as_no_documents(self):
        res = self.client.get(self.url("cohosts/"))
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["documents"], [])

    def test_unknown_session_is_404(self):
        res = self.client.post(
            "/api/v1/classrooms/sessions/ses_missing/cohosts/",
            data={"userId": self.student.id},
            format="json",
        )
        self.assertEqual(res.status_code, 404)

    # --- the co-host's own view of themselves ----------------------------

    def test_permissions_endpoint_reports_cohost_status(self):
        self.as_user(self.student)
        before = self.client.get(self.url("permissions/")).json()["document"]
        self.assertFalse(before["cohost"])

        self.as_user(self.host)
        self.appoint()

        self.as_user(self.student)
        after = self.client.get(self.url("permissions/")).json()["document"]
        self.assertTrue(after["cohost"])

    # --- moderation parity ------------------------------------------------

    def test_a_cohost_can_moderate(self):
        self.appoint()
        self.as_user(self.student)

        res = self.client.patch(
            self.url(),
            data={
                "isMutedAll": True,
                "permissions": {"mic": "approval"},
            },
            format="json",
        )
        self.assertEqual(res.status_code, 200)
        self.session.refresh_from_db()
        self.assertTrue(self.session.is_muted_all)

        doc = self.client.get(self.url()).json()["document"]
        self.assertEqual(doc["permissions"]["mic"], "approval")
        self.assertEqual(doc["permissions"]["camera"], "free")

    def test_a_cohost_can_run_the_permission_inbox(self):
        self.appoint()

        self.as_user(self.student)
        req_id = self.client.post(
            self.url("permissions/requests/"), data={"requestType": "camera"}, format="json"
        ).json()["document"]["id"]

        # Not the host -- a co-host reviews it, and approving grants the media.
        res = self.client.patch(
            self.url(f"permissions/requests/{req_id}/"),
            data={"status": "approved"},
            format="json",
        )
        self.assertEqual(res.status_code, 200)

        grant = self.client.get(self.url("permissions/")).json()["document"]
        self.assertTrue(grant["camera"])

    # --- where the powers stop -------------------------------------------

    def test_a_cohost_cannot_rename_the_session(self):
        self.appoint()
        self.as_user(self.student)
        res = self.client.patch(self.url(), data={"title": "Mine now"}, format="json")
        self.assertEqual(res.status_code, 403)
        self.assertEqual(res.json()["error"]["code"], "FORBIDDEN")
        self.session.refresh_from_db()
        self.assertEqual(self.session.title, "Co-host Lab")

    def test_a_moderation_key_does_not_smuggle_a_setting_past_the_check(self):
        # A co-host could otherwise rename the room in the same PATCH as a mute.
        self.appoint()
        self.as_user(self.student)
        res = self.client.patch(
            self.url(), data={"title": "Mine now", "isMutedAll": True}, format="json"
        )
        self.assertEqual(res.status_code, 403)
        self.session.refresh_from_db()
        self.assertEqual(self.session.title, "Co-host Lab")
        self.assertFalse(self.session.is_muted_all)

    def test_a_cohost_cannot_end_the_session(self):
        self.appoint()
        self.as_user(self.student)
        res = self.client.post(self.url("end/"))
        self.assertEqual(res.status_code, 403)
        self.session.refresh_from_db()
        self.assertTrue(self.session.is_active)

    def test_a_cohost_cannot_appoint_or_remove_cohosts(self):
        self.appoint()
        self.as_user(self.student)

        res = self.client.delete(
            self.url(f"cohosts/{self.student.id}/")
        )
        self.assertEqual(res.status_code, 403)

    def test_removal_takes_the_powers_away(self):
        self.appoint()
        self.as_user(self.student)
        self.assertTrue(
            self.client.get(self.url("permissions/")).json()["document"]["cohost"]
        )

        self.as_user(self.host)
        self.assertEqual(self.client.delete(self.url(f"cohosts/{self.student.id}/")).status_code, 200)

        self.as_user(self.student)
        self.assertFalse(
            self.client.get(self.url("permissions/")).json()["document"]["cohost"]
        )
        self.assertEqual(
            self.client.patch(self.url(), data={"isMutedAll": True}, format="json").status_code,
            403,
        )

    def test_only_host_authority_may_remove_a_cohost(self):
        self.appoint()
        self.as_user(self.bystander)
        res = self.client.delete(self.url(f"cohosts/{self.student.id}/"))
        self.assertEqual(res.status_code, 403)
        self.assertTrue(
            self.session.co_hosts.filter(user_id=self.student.id, is_active=True).exists()
        )

    def test_removing_someone_who_is_not_a_cohost_is_404(self):
        res = self.client.delete(self.url(f"cohosts/{self.bystander.id}/"))
        self.assertEqual(res.status_code, 404)

    def test_reappointing_revives_the_same_row(self):
        self.appoint()
        self.as_user(self.host)
        self.client.delete(self.url(f"cohosts/{self.student.id}/"))

        res = self.appoint()
        # Not a new row: the unique constraint holds and history stays put.
        self.assertEqual(res.status_code, 200)
        self.assertEqual(
            self.session.co_hosts.filter(user_id=self.student.id).count(), 1
        )
        self.assertEqual(
            self.session.co_hosts.filter(user_id=self.student.id, is_active=True).count(), 1
        )

    def test_backup_mod_id_tracks_the_newest_cohost_and_clears(self):
        self.appoint()
        self.session.refresh_from_db()
        self.assertEqual(self.session.backup_mod_id, self.student.id)

        self.appoint(self.bystander.id)
        self.session.refresh_from_db()
        self.assertEqual(self.session.backup_mod_id, self.bystander.id)

        self.client.delete(self.url(f"cohosts/{self.bystander.id}/"))
        self.session.refresh_from_db()
        self.assertEqual(self.session.backup_mod_id, self.student.id)

        self.client.delete(self.url(f"cohosts/{self.student.id}/"))
        self.session.refresh_from_db()
        self.assertIsNone(self.session.backup_mod_id)
