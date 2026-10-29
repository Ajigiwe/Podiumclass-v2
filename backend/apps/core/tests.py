from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.core import features
from apps.core.models import SystemSetting
from apps.core.registry import FEATURES, SETTINGS


class FeatureFlagApiTests(TestCase):
    """Public + admin feature-flag endpoints."""

    def setUp(self):
        self.client = APIClient()
        self.admin = User.objects.create(
            id="usr_core_admin", email="core-admin@podium.test",
            full_name="Core Admin", role="admin",
        )
        self.student = User.objects.create(
            id="usr_core_student", email="core-student@podium.test",
            full_name="Core Student", role="student",
        )

    def test_public_flags_require_no_auth_and_leak_nothing(self):
        res = self.client.get("/api/v1/core/features/")
        self.assertEqual(res.status_code, 200)
        body = res.json()["document"]["features"]
        # Only a boolean map -- no labels, descriptions, or secrets.
        self.assertTrue(set(body.keys()) >= {"classroom_live", "wallet_payments"})
        for value in body.values():
            self.assertIsInstance(value, bool)

    def test_admin_settings_rejects_non_admin(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.student)}")
        res = self.client.get("/api/v1/core/settings/")
        self.assertEqual(res.status_code, 403)

    def test_admin_settings_requires_auth(self):
        res = self.client.get("/api/v1/core/settings/")
        self.assertEqual(res.status_code, 401)

    def test_admin_settings_masks_secrets(self):
        # Ensure a known secret exists with a real value.
        SystemSetting.objects.update_or_create(
            key="attendance_api_key", defaults={"value": "super-secret-key"}
        )
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.get("/api/v1/core/settings/")
        self.assertEqual(res.status_code, 200)
        doc = res.json()["document"]

        settings_by_key = {s["key"]: s for s in doc["settings"]}
        secret = settings_by_key["attendance_api_key"]
        self.assertIsNone(secret["value"], "secret value must not be echoed")
        self.assertTrue(secret["isSet"])

        # A non-secret setting still returns its value.
        self.assertIsNotNone(settings_by_key["livekit_url"]["value"])

    def test_admin_settings_groups_every_setting(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.get("/api/v1/core/settings/")
        doc = res.json()["document"]
        self.assertEqual(
            {s["key"] for s in doc["settings"]},
            {s["key"] for s in SETTINGS},
        )
        self.assertEqual(len(doc["features"]), len(FEATURES))

    def test_toggle_feature_persists_and_is_publicly_visible(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.patch(
            "/api/v1/core/features/wallet_payments/", {"enabled": False}, format="json"
        )
        self.assertEqual(res.status_code, 200)
        self.assertFalse(res.json()["document"]["enabled"])

        # Persisted to the database under the feature_ prefix.
        self.assertEqual(SystemSetting.get_setting("feature_wallet_payments"), "false")

        # Public endpoint reflects the change immediately.
        public = self.client.get("/api/v1/core/features/")
        self.assertFalse(public.json()["document"]["features"]["wallet_payments"])

    def test_toggle_feature_rejects_unknown_key(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.patch(
            "/api/v1/core/features/not_a_real_flag/", {"enabled": True}, format="json"
        )
        self.assertEqual(res.status_code, 404)

    def test_toggle_feature_requires_admin(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.student)}")
        res = self.client.patch(
            "/api/v1/core/features/wallet_payments/", {"enabled": False}, format="json"
        )
        self.assertEqual(res.status_code, 403)

    def test_update_setting_validates_integer_type(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.patch(
            "/api/v1/core/settings/min_topup_amount/", {"value": "not-a-number"}, format="json"
        )
        self.assertEqual(res.status_code, 400)

    def test_update_setting_persists(self):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.patch(
            "/api/v1/core/settings/min_topup_amount/", {"value": "250"}, format="json"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(SystemSetting.get_setting("min_topup_amount"), "250")

    def test_blank_secret_write_leaves_existing_value(self):
        SystemSetting.objects.update_or_create(
            key="livekit_api_secret", defaults={"value": "original-secret"}
        )
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.admin)}")
        res = self.client.patch(
            "/api/v1/core/settings/livekit_api_secret/", {"value": ""}, format="json"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(SystemSetting.get_setting("livekit_api_secret"), "original-secret")


class FeatureEnforcementTests(TestCase):
    """Disabling a flag must actually stop the governed endpoint."""

    def setUp(self):
        self.client = APIClient()
        self.admin = User.objects.create(
            id="usr_enf_admin", email="enf-admin@podium.test",
            full_name="Enf Admin", role="admin",
        )
        self.lecturer = User.objects.create(
            id="usr_enf_lect", email="enf-lect@podium.test",
            full_name="Enf Lect", role="lecturer",
        )

    def _set(self, key, enabled):
        features.set_enabled(key, enabled)

    def test_disabled_classroom_live_blocks_livekit_token(self):
        self._set("classroom_live", False)
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.lecturer)}")
        res = self.client.post(
            "/api/v1/classrooms/token/", {"roomName": "room-1"}, format="json"
        )
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "FEATURE_DISABLED")

    def test_reenabled_classroom_live_allows_token(self):
        self._set("classroom_live", True)
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.lecturer)}")
        res = self.client.post(
            "/api/v1/classrooms/token/", {"roomName": "room-1"}, format="json"
        )
        self.assertEqual(res.status_code, 200)

    def test_disabled_wallet_blocks_transactions(self):
        self._set("wallet_payments", False)
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(self.lecturer)}")
        res = self.client.get("/api/v1/billing/transactions/")
        self.assertEqual(res.status_code, 503)

    def test_disabled_external_attendance_blocks_service_read(self):
        self._set("external_attendance_api", False)
        key = SystemSetting.get_setting("ATTENDANCE_API_KEY") or "test-key"
        from django.conf import settings
        res = self.client.get(
            "/api/v1/attendance/sessions",
            HTTP_AUTHORIZATION=f"Bearer {settings.ATTENDANCE_API_KEY}",
        )
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "FEATURE_DISABLED")

    def test_disabled_google_signin_blocks_login(self):
        self._set("google_signin", False)
        res = self.client.post("/api/v1/accounts/google/", {"credential": "x"}, format="json")
        self.assertEqual(res.status_code, 503)


class FeatureStateHelperTests(TestCase):
    """Unit checks for the flag helpers."""

    def test_unset_flag_reads_registry_default(self):
        # classroom_live defaults to True and has no row yet.
        SystemSetting.objects.filter(key="feature_classroom_live").delete()
        self.assertTrue(features.is_enabled("classroom_live"))

    def test_default_false_flag_reads_false(self):
        SystemSetting.objects.filter(key="feature_recordings").delete()
        self.assertFalse(features.is_enabled("recordings"))

    def test_set_enabled_round_trips(self):
        features.set_enabled("recordings", True)
        self.assertTrue(features.is_enabled("recordings"))
        features.set_enabled("recordings", False)
        self.assertFalse(features.is_enabled("recordings"))

    def test_seed_all_is_idempotent_and_preserves_custom(self):
        features.set_enabled("recordings", True)
        features.seed_all()
        features.seed_all()
        # Custom choice survives re-seeding.
        self.assertTrue(features.is_enabled("recordings"))


class DevLoginFlagExposureTests(TestCase):
    """
    The login page needs to know whether the backend will honour a
    credential-less POST, so it can hide the DEV quick-access buttons instead
    of showing controls that always fail.
    """

    def setUp(self):
        self.client = APIClient()

    def test_features_endpoint_reports_dev_login_off_by_default(self):
        with override_settings(DEBUG=True, DEV_LOGIN_ENABLED=False):
            res = self.client.get("/api/v1/core/features/")
            self.assertEqual(res.status_code, 200)
            body = res.json()["document"]
            self.assertIn("features", body)
            self.assertIs(body["devLogin"], False)

    @override_settings(DEBUG=False, DEV_LOGIN_ENABLED=True)
    def test_features_endpoint_reports_dev_login_off_without_debug(self):
        # Same effective test the view uses: production must advertise False
        # even when the switch itself is set.
        res = self.client.get("/api/v1/core/features/")
        self.assertIs(res.json()["document"]["devLogin"], False)

    @override_settings(DEBUG=True, DEV_LOGIN_ENABLED=True)
    def test_features_endpoint_reports_dev_login_when_enabled(self):
        res = self.client.get("/api/v1/core/features/")
        self.assertIs(res.json()["document"]["devLogin"], True)


class LiveKitUrlResolutionTests(TestCase):
    """
    Every join answer carries a LiveKit endpoint, and the clients turn a bad one
    into \"expected url scheme 'https' but no scheme was found for pending\".
    These pin the four ways it went wrong.
    """

    def _request(self, host="podium.example.org", secure=False):
        from django.test import RequestFactory

        factory = RequestFactory()
        request = factory.get("/api/v1/classrooms/token/")
        if secure:
            request = factory.get("/api/v1/classrooms/token/", secure=True)
        request.META["HTTP_HOST"] = host
        request.META["SERVER_NAME"] = host.split(":")[0]
        return request

    def test_placeholder_is_not_an_endpoint(self):
        from apps.core.livekit_url import normalize_livekit_url

        for value in ("pending", " Pending ", "TBD", "", None, "n/a"):
            self.assertEqual(normalize_livekit_url(value), "", f"{value!r} must be unusable")

    def test_bare_hostname_gets_wss(self):
        from apps.core.livekit_url import normalize_livekit_url

        self.assertEqual(
            normalize_livekit_url("myproject.livekit.cloud"),
            "wss://myproject.livekit.cloud",
        )

    def test_plaintext_schemes_are_upgraded_for_a_public_host(self):
        from apps.core.livekit_url import normalize_livekit_url

        self.assertEqual(
            normalize_livekit_url("http://livekit.example.org:7880"),
            "https://livekit.example.org:7880",
        )
        self.assertEqual(
            normalize_livekit_url("ws://livekit.example.org:7880"),
            "wss://livekit.example.org:7880",
        )

    def test_loopback_is_kept_for_a_loopback_request(self):
        from apps.core.livekit_url import normalize_livekit_url

        self.assertEqual(
            normalize_livekit_url("ws://localhost:7880", request=self._request("localhost:8000")),
            "ws://localhost:7880",
        )

    def test_loopback_is_rewritten_to_the_host_the_client_reached(self):
        # A deployed server left on the development default: \"localhost\" would
        # resolve to the student's own phone.
        from apps.core.livekit_url import normalize_livekit_url

        self.assertEqual(
            normalize_livekit_url(
                "ws://localhost:7880",
                request=self._request("192.168.1.20:8000"),
            ),
            "ws://192.168.1.20:7880",
        )

    @override_settings(LIVEKIT_URL="wss://env.livekit.cloud")
    def test_a_stored_placeholder_does_not_hide_the_environment_value(self):
        from apps.core.livekit_url import resolve_livekit_url

        SystemSetting.objects.update_or_create(key="livekit_url", defaults={"value": "pending"})
        self.assertEqual(resolve_livekit_url(self._request()), "wss://env.livekit.cloud")

    def test_saving_a_placeholder_is_refused(self):
        from apps.core.views import AdminSettingsView  # noqa: F401  (route wiring check)

        admin = User.objects.create(
            id="usr_core_admin_lk", email="lk-admin@podium.test",
            full_name="LiveKit Admin", role="admin", is_staff=True,
        )
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(admin)}")
        res = client.patch(
            "/api/v1/core/settings/livekit_url/", {"value": "pending"}, format="json"
        )
        self.assertEqual(res.status_code, 400, res.content)
        self.assertEqual(res.json()["error"]["code"], "INVALID_SETTING")

    def test_saving_a_bare_hostname_stores_a_usable_url(self):
        admin = User.objects.create(
            id="usr_core_admin_lk2", email="lk-admin2@podium.test",
            full_name="LiveKit Admin", role="admin", is_staff=True,
        )
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(admin)}")
        res = client.patch(
            "/api/v1/core/settings/livekit_url/",
            {"value": "myproject.livekit.cloud"},
            format="json",
        )
        self.assertEqual(res.status_code, 200, res.content)
        self.assertEqual(
            SystemSetting.objects.get(key="livekit_url").value,
            "wss://myproject.livekit.cloud",
        )

    @override_settings(
        LIVEKIT_URL="wss://demo.livekit.cloud",
        LIVEKIT_API_KEY="dev-only-livekit-key",
        LIVEKIT_API_SECRET="dev-only-livekit-secret",
    )
    def test_placeholder_keys_are_reported_as_not_configured(self):
        # A placeholder key signs a token LiveKit will refuse, so the join must
        # fail here with the setting named rather than during the handshake.
        lecturer = User.objects.create(
            id="usr_core_lk_lect", email="lk-lect@podium.test",
            full_name="Lecturer", role="lecturer",
        )
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {issue_token(lecturer)}")
        res = client.post(
            "/api/v1/classrooms/token/", {"roomName": "room-lk"}, format="json"
        )
        self.assertEqual(res.status_code, 503, res.content)
        self.assertEqual(res.json()["error"]["code"], "LIVEKIT_NOT_CONFIGURED")
