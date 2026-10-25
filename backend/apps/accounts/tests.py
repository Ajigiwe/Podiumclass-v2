import jwt as pyjwt
from django.conf import settings
from django.test import TestCase
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
