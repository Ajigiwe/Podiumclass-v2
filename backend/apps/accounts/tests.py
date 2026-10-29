import os

import jwt as pyjwt
from django.conf import settings
from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User


class UserPrimaryKeyTests(TestCase):
    """
    Regression: User.id is a CharField primary key, so it defaults to "" rather
    than auto-generating. Only UserManager.create_user used to fill it, which
    meant any User.objects.create()/get_or_create() bypassing the manager
    produced a user whose id -- and therefore whose JWT "sub" -- was "". That
    user could never be resolved from a token.
    """

    def test_get_or_create_without_manager_generates_id(self):
        user, created = User.objects.get_or_create(
            email="nomanager@podium.test",
            defaults={"full_name": "No Manager", "role": "student"},
        )
        self.assertTrue(created)
        self.assertTrue(user.id, "User created via get_or_create must get a non-empty id")

    def test_plain_create_generates_id(self):
        user = User.objects.create(email="plaincreate@podium.test", full_name="Plain")
        self.assertTrue(user.id, "User created via create() must get a non-empty id")

    def test_existing_id_is_preserved(self):
        user = User.objects.create(
            id="usr_explicit_1", email="explicit@podium.test", full_name="Explicit"
        )
        self.assertEqual(user.id, "usr_explicit_1")

    def test_token_subject_matches_user_id(self):
        user = User.objects.create(email="subcheck@podium.test", full_name="Sub Check")
        claims = pyjwt.decode(
            issue_token(user),
            options={"verify_signature": False},
        )
        self.assertEqual(claims["sub"], user.id)


class AuthenticationTests(TestCase):
    """End-to-end checks that a signed token is an actual credential."""

    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create(
            id="usr_auth_1", email="auth1@podium.test", full_name="Auth One", role="student"
        )

    def test_valid_token_resolves_user(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.user)}")
        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["document"]["id"], self.user.id)

    def test_missing_token_is_rejected(self):
        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 401)

    def test_legacy_forged_token_is_rejected(self):
        # The old "token" was just the user id wrapped in text; it must fail.
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer podium_jwt_{self.user.id}")
        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 401)

    def test_token_for_deleted_user_is_rejected(self):
        token = issue_token(self.user)
        User.objects.filter(pk=self.user.id).delete()
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 401)

    def test_tampered_token_is_rejected(self):
        token = issue_token(self.user)
        tampered = token[:-4] + ("AAAA" if not token.endswith("AAAA") else "BBBB")
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {tampered}")
        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 401)


class ProfileUpdateTests(TestCase):
    """
    PATCH /api/v1/accounts/me/ is the first-time setup write path.

    Google sign-in only supplies a name and email, so the index number and the
    rest of the profile are filled in here. The endpoint did not exist before,
    which is why the profile form silently discarded edits.
    """

    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create(
            id="usr_prof_1", email="prof1@podium.test", full_name="Prof One",
            role="student",
        )
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.user)}")

    def test_first_time_setup_persists(self):
        res = self.client.patch(
            "/api/v1/accounts/me/",
            {
                "fullName": "Ama Boateng",
                "indexNumber": "CS/2026/001",
                "university": "University of Ghana",
                "bio": "CS student",
            },
            format="json",
        )
        self.assertEqual(res.status_code, 200, res.content)
        self.user.refresh_from_db()
        self.assertEqual(self.user.full_name, "Ama Boateng")
        self.assertEqual(self.user.index_number, "CS/2026/001")
        self.assertEqual(self.user.university, "University of Ghana")
        self.assertEqual(self.user.bio, "CS student")
        self.assertEqual(res.json()["document"]["indexNumber"], "CS/2026/001")

    def test_privilege_fields_are_not_writable(self):
        res = self.client.patch(
            "/api/v1/accounts/me/",
            {
                "role": "admin",
                "walletBalance": 999999,
                "isVerified": True,
                "is_staff": True,
                "is_superuser": True,
            },
            format="json",
        )
        self.assertEqual(res.status_code, 200)
        self.user.refresh_from_db()
        self.assertEqual(self.user.role, "student")
        self.assertEqual(self.user.wallet_balance, 0)
        self.assertFalse(self.user.is_staff)
        self.assertFalse(self.user.is_superuser)

    def test_duplicate_index_number_rejected(self):
        User.objects.create(
            id="usr_prof_2", email="prof2@podium.test", full_name="Prof Two",
            role="student", index_number="CS/2026/001",
        )
        res = self.client.patch(
            "/api/v1/accounts/me/", {"indexNumber": "cs/2026/001"}, format="json"
        )
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INDEX_NUMBER_TAKEN")

    def test_empty_index_number_clears_it(self):
        self.user.index_number = "CS/2026/001"
        self.user.save()
        res = self.client.patch(
            "/api/v1/accounts/me/", {"indexNumber": ""}, format="json"
        )
        self.assertEqual(res.status_code, 200)
        self.user.refresh_from_db()
        self.assertIsNone(self.user.index_number)

    def test_unauthenticated_patch_rejected(self):
        self.client.credentials()
        res = self.client.patch(
            "/api/v1/accounts/me/", {"fullName": "Impostor"}, format="json"
        )
        self.assertEqual(res.status_code, 401)


class GoogleOnlySignInTests(TestCase):
    """
    Only a Google-verified credential may mint a token.

    The endpoint previously accepted a bare index number or email address and
    returned a signed JWT for the matching account -- an unauthenticated login
    bypass, since index numbers appear on ID cards and in class rosters.
    """

    def setUp(self):
        from apps.core import features
        features.set_enabled("google_signin", True)
        self.client = APIClient()
        self.student = User.objects.create(
            id="usr_bypass_1", email="victim@podium.test", full_name="Victim",
            role="student", index_number="CS/2026/009",
        )

    def test_index_number_alone_does_not_issue_token(self):
        res = self.client.post(
            "/api/v1/accounts/login/", {"indexNumber": "CS/2026/009"}, format="json"
        )
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "CREDENTIAL_REQUIRED")
        self.assertNotIn("token", res.json())

    def test_email_alone_does_not_issue_token(self):
        res = self.client.post(
            "/api/v1/accounts/login/", {"email": "victim@podium.test"}, format="json"
        )
        self.assertEqual(res.status_code, 400)
        self.assertNotIn("token", res.json())

    def test_email_alone_cannot_provision_an_account(self):
        res = self.client.post(
            "/api/v1/accounts/login/", {"email": "newperson@podium.test"}, format="json"
        )
        self.assertEqual(res.status_code, 400)
        self.assertFalse(
            User.objects.filter(email="newperson@podium.test").exists(),
            "An unauthenticated request must not create accounts.",
        )


class DevLoginGateTests(TestCase):
    """
    Credential-less sign-in is a local convenience, not a fallback.

    The admin branch mints a fully privileged JWT for a bare POST, so it has to
    stay unreachable unless a deployment asks for it by name. DEBUG alone is
    explicitly NOT enough: a staging box forgotten on DEBUG would otherwise be
    a one-request admin takeover.
    """

    url = "/api/v1/accounts/google/"

    def setUp(self):
        self.client = APIClient()

    @override_settings(DEBUG=True, DEV_LOGIN_ENABLED=False)
    def test_debug_alone_does_not_enable_dev_login(self):
        res = self.client.post(self.url, {"dev_login": True, "role": "admin"}, format="json")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "CREDENTIAL_REQUIRED")

    @override_settings(DEBUG=False, DEV_LOGIN_ENABLED=True)
    def test_production_can_never_enable_dev_login(self):
        # The switch is set, DEBUG is not: this is the configuration a
        # deployment ships, and it must refuse regardless.
        res = self.client.post(self.url, {"dev_login": True, "role": "admin"}, format="json")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "CREDENTIAL_REQUIRED")

    @override_settings(DEBUG=True, DEV_LOGIN_ENABLED=False)
    def test_switched_off_dev_login_falls_through_to_the_credential_check(self):
        res = self.client.post(self.url, {"dev_login": True, "role": "student"}, format="json")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "CREDENTIAL_REQUIRED")

    @override_settings(DEBUG=True, DEV_LOGIN_ENABLED=True)
    def test_enabled_dev_login_signs_in_and_the_token_works(self):
        res = self.client.post(self.url, {"dev_login": True, "role": "student"}, format="json")
        self.assertEqual(res.status_code, 200)
        doc = res.json()["document"]
        self.assertEqual(doc["user"]["email"], "student@podium.local")
        self.assertNotEqual(doc["user"]["role"], "admin")

        # A token minted this way must actually authenticate.
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {doc['token']}")
        me = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(me.status_code, 200)
        self.assertEqual(me.json()["document"]["email"], "student@podium.local")

    @override_settings(DEBUG=True, DEV_LOGIN_ENABLED=True)
    def test_admin_branch_is_the_admin_account(self):
        res = self.client.post(self.url, {"dev_login": True, "role": "admin"}, format="json")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["document"]["user"]["role"], "admin")

    @override_settings(DEBUG=True, DEV_LOGIN_ENABLED=True)
    def test_enabling_dev_login_does_not_weaken_the_normal_path(self):
        # Turning the switch on must not also turn off the credential check.
        res = self.client.post(self.url, {}, format="json")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "CREDENTIAL_REQUIRED")


class ProfilePhotoTests(TestCase):
    """
    Profile pictures are persisted files, not a transient client-side preview.

    The old UI had a camera button with no handler at all: picking an image did
    nothing, and any picture that did reach the server was a Google URL that
    disappeared the moment the person changed their Google photo.
    """

    def setUp(self):
        import tempfile

        self.root = tempfile.mkdtemp(prefix="podium-avatars-")
        self.addCleanup(lambda: __import__("shutil").rmtree(self.root, ignore_errors=True))
        self.override = override_settings(AVATARS_ROOT=__import__("pathlib").Path(self.root))
        self.override.enable()
        self.addCleanup(self.override.disable)

        self.user = User.objects.create(
            id="usr_avatar_test", email="avatar@podium.test", full_name="Avatar Test"
        )
        self.client = APIClient()
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.user)}")
        # A one-pixel JPEG: enough for the magic-byte check.
        self.jpeg = (
            b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
            b"\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t"
            b"\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a"
            b"\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4"
            b"\x00\x14\x00\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00"
            b"\x00\x00\x00\x00\x08\xff\xda\x00\x08\x01\x01\x00\x00?\x00\x7f\xff\xd9"
        )

    def _upload(self, content=None, name="me.jpg"):
        from django.core.files.uploadedfile import SimpleUploadedFile

        return self.client.post(
            "/api/v1/accounts/me/photo/",
            {"photo": SimpleUploadedFile(name, content or self.jpeg, content_type="image/jpeg")},
            format="multipart",
        )

    def test_upload_persists_and_is_served(self):
        res = self._upload()
        self.assertEqual(res.status_code, 200)
        photo_url = res.json()["document"]["photoUrl"]
        self.assertIn("/api/v1/accounts/avatar/usr_avatar_test/", photo_url)

        self.user.refresh_from_db()
        self.assertEqual(self.user.photo_url, photo_url)

        served = self.client.get("/api/v1/accounts/avatar/usr_avatar_test/")
        self.assertEqual(served.status_code, 200)
        self.assertEqual(served["Content-Type"], "image/jpeg")
        self.assertIn("max-age", served["Cache-Control"])

    def test_avatar_is_readable_without_a_token(self):
        self._upload()
        anonymous = APIClient()
        self.assertEqual(
            anonymous.get("/api/v1/accounts/avatar/usr_avatar_test/").status_code, 200
        )

    def test_non_image_upload_is_rejected(self):
        res = self._upload(b"<html>not an image</html>", name="fake.jpg")
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "UNSUPPORTED_IMAGE")
        self.assertEqual(os.listdir(self.root), [])

    def test_replacing_leaves_one_file_and_a_new_url(self):
        first = self._upload().json()["document"]["photoUrl"]
        second = self._upload().json()["document"]["photoUrl"]
        self.assertNotEqual(first, second, "a replacement must bust the cached URL")
        self.assertEqual(len(os.listdir(self.root)), 1, "the old picture should be removed")

    def test_delete_clears_the_picture(self):
        self._upload()
        res = self.client.delete("/api/v1/accounts/me/photo/")
        self.assertEqual(res.status_code, 200)
        self.assertIsNone(res.json()["document"].get("photoUrl"))
        self.assertEqual(os.listdir(self.root), [])

    def test_unknown_account_has_no_avatar(self):
        self.assertEqual(
            self.client.get("/api/v1/accounts/avatar/usr_does_not_exist/").status_code, 404
        )


class DisabledAccountTests(TestCase):
    """
    Disabling an account in the admin panel must actually keep it out.

    The panel was writing the right field, but the sign-in endpoint still minted
    a token for a disabled user: they could sign in, then every request failed
    with ACCOUNT_DISABLED. From the admin's side that looks like the switch did
    nothing, so both halves are pinned here.
    """

    def setUp(self):
        self.client = APIClient()
        self.student = User.objects.create(
            id="usr_disabled_1",
            email="blocked@podium.test",
            full_name="Blocked Student",
            role="student",
            is_verified=True,
        )

    def _admin(self):
        return User.objects.create(
            id="usr_admin_9",
            email="operator@podium.test",
            full_name="Operator",
            role="admin",
            is_staff=True,
            is_superuser=True,
            is_verified=True,
        )

    def _disable(self):
        self.student.status = "disabled"
        self.student.is_active = False
        self.student.save(update_fields=["status", "is_active"])

    def test_existing_token_stops_working_when_disabled(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.student)}")
        self.assertEqual(self.client.get("/api/v1/accounts/me/").status_code, 200)

        self._disable()

        res = self.client.get("/api/v1/accounts/me/")
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.json()["error"]["code"], "ACCOUNT_DISABLED")

    def test_admin_patch_sets_status_and_is_active(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self._admin())}")
        res = self.client.patch(
            f"/api/v1/accounts/users/{self.student.id}/",
            {"status": "disabled"},
            format="json",
        )
        self.assertEqual(res.status_code, 200, res.content)
        self.student.refresh_from_db()
        self.assertEqual(self.student.status, "disabled")
        self.assertFalse(self.student.is_active)

    def test_google_sign_in_refuses_a_disabled_account(self):
        from unittest.mock import patch

        self._disable()

        with patch("apps.accounts.views.id_token.verify_oauth2_token") as verify:
            verify.return_value = {
                "email": self.student.email,
                "email_verified": True,
                "name": "Blocked Student",
                "picture": None,
            }
            res = self.client.post(
                "/api/v1/accounts/google/",
                {"credential": "fake-google-id-token"},
                format="json",
            )

        self.assertEqual(res.status_code, 403, res.content)
        self.assertEqual(res.json()["error"]["code"], "ACCOUNT_DISABLED")
        self.assertNotIn("token", res.json())

    def test_active_account_signs_in(self):
        from unittest.mock import patch

        with patch("apps.accounts.views.id_token.verify_oauth2_token") as verify:
            verify.return_value = {
                "email": self.student.email,
                "email_verified": True,
                "name": "Blocked Student",
                "picture": None,
            }
            res = self.client.post(
                "/api/v1/accounts/google/",
                {"credential": "fake-google-id-token"},
                format="json",
            )

        self.assertEqual(res.status_code, 200, res.content)
        # Every 2xx body is wrapped in the Document Object by the renderer.
        self.assertTrue(res.json()["document"]["token"])
