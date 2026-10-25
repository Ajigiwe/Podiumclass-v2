from unittest import mock

from django.test import TestCase

from apps.accounts.models import User
from apps.core import health


class HealthEndpointTests(TestCase):
    """Live-shape checks for /health, /health/live, /health/ready."""

    @classmethod
    def setUpTestData(cls):
        # A deployed system has an admin; without one the advisory user_store
        # check warns and the aggregate is "degraded", not "healthy".
        User.objects.create(
            id="usr_health_admin", email="health-admin@podium.test",
            full_name="Health Admin", role="admin",
        )

    def setUp(self):
        health.clear_cache()

    def tearDown(self):
        health.clear_cache()

    def test_health_returns_flat_json_not_document_wrapper(self):
        res = self.client.get("/health")
        self.assertEqual(res.status_code, 200)
        body = res.json()
        # Health consumers expect a conventional top-level "status" key; the
        # Document Object renderer must NOT nest it under "document".
        self.assertIn("status", body)
        self.assertNotIn("document", body)
        self.assertEqual(body["status"], "healthy")

    def test_health_includes_required_fields(self):
        body = self.client.get("/health").json()
        self.assertIn("checks", body)
        self.assertIn("timestamp", body)
        self.assertIn("uptimeSeconds", body)
        for chk in body["checks"]:
            self.assertIn("name", chk)
            self.assertIn("status", chk)
            self.assertIn("durationMs", chk)
            self.assertIn("critical", chk)

    def test_health_no_slash_and_slash_both_work(self):
        # Monitors poll /health exactly; APPEND_SLASH would 301 it to /health/.
        self.assertEqual(self.client.get("/health").status_code, 200)
        self.assertEqual(self.client.get("/health/").status_code, 200)

    def test_health_requires_no_authentication(self):
        # A probe carries no credential; no WWW-Authenticate challenge either.
        res = self.client.get("/health")
        self.assertEqual(res.status_code, 200)
        self.assertNotIn("WWW-Authenticate", res)

    def test_live_endpoint_runs_no_dependency_checks(self):
        body = self.client.get("/health/live").json()
        self.assertEqual(body["status"], "alive")
        self.assertNotIn("checks", body)  # liveness must never probe deps

    def test_ready_endpoint_reports_ready(self):
        res = self.client.get("/health/ready")
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertTrue(body["ready"])
        # Readiness runs only critical checks.
        for chk in body["checks"]:
            self.assertTrue(chk["critical"])

    def test_critical_failure_returns_503(self):
        health.clear_cache()
        with mock.patch.object(
            health, "CHECKS",
            [{"name": "database", "fn": lambda: health.Probe(health.STATUS_FAIL, "down"),
              "critical": True}],
        ):
            health.clear_cache()
            res = self.client.get("/health")
            self.assertEqual(res.status_code, 503)
            self.assertEqual(res.json()["status"], "unhealthy")

    def test_advisory_failure_degrades_but_still_200(self):
        health.clear_cache()
        with mock.patch.object(
            health, "CHECKS",
            [{"name": "feature_flags", "fn": lambda: health.Probe(health.STATUS_WARN, "odd"),
              "critical": False}],
        ):
            health.clear_cache()
            res = self.client.get("/health")
            self.assertEqual(res.status_code, 200)  # still serving
            self.assertEqual(res.json()["status"], "degraded")

    def test_ready_fails_on_critical_but_ignores_advisory(self):
        health.clear_cache()
        with mock.patch.object(
            health, "CHECKS",
            [{"name": "feature_flags", "fn": lambda: health.Probe(health.STATUS_FAIL, "x"),
              "critical": False}],
        ):
            health.clear_cache()
            self.assertEqual(self.client.get("/health/ready").status_code, 200)

        health.clear_cache()
        with mock.patch.object(
            health, "CHECKS",
            [{"name": "database", "fn": lambda: health.Probe(health.STATUS_FAIL, "x"),
              "critical": True}],
        ):
            health.clear_cache()
            self.assertEqual(self.client.get("/health/ready").status_code, 503)

    def test_health_is_cached_between_polls(self):
        """Within the TTL, repeat polls must not re-run the probes."""
        health.clear_cache()
        with mock.patch.object(health, "run_checks", wraps=health.run_checks) as spy:
            self.client.get("/health")
            self.assertEqual(spy.call_count, 1)
            # A second poll inside the TTL is served from cache.
            self.client.get("/health")
            self.assertEqual(spy.call_count, 1)


class HealthSafetyTests(TestCase):
    """The health endpoint is public: it must never leak secrets or crash."""

    def tearDown(self):
        health.clear_cache()

    def test_exception_detail_is_class_name_not_message(self):
        # A DB error message can embed a DSN; only the class name may surface.
        def boom():
            raise ConnectionError("host=prod-db.internal user=postgres password=hunter2")

        with mock.patch.object(health, "CHECKS",
                               [{"name": "database", "fn": boom, "critical": True}]):
            results = health.run_checks()
        self.assertEqual(results[0].status, health.STATUS_FAIL)
        self.assertEqual(results[0].detail, "ConnectionError")
        # The raw message must not appear anywhere in the serialized report.
        body = health.build_report(results)
        serialized = str(body)
        self.assertNotIn("hunter2", serialized)
        self.assertNotIn("prod-db.internal", serialized)
        self.assertNotIn("password", serialized)

    def test_check_returning_garbage_is_a_failure_not_a_crash(self):
        with mock.patch.object(health, "CHECKS",
                               [{"name": "bad", "fn": lambda: 42, "critical": True}]):
            results = health.run_checks()
        self.assertEqual(results[0].status, health.STATUS_FAIL)
        self.assertEqual(results[0].detail, "check returned an unexpected value")

    def test_check_raising_does_not_crash_the_runner(self):
        with mock.patch.object(health, "CHECKS",
                               [{"name": "bad", "fn": lambda: (_ for _ in ()).throw(ValueError("x")),
                                "critical": False}]):
            results = health.run_checks()
        self.assertEqual(results[0].status, health.STATUS_FAIL)
        self.assertEqual(results[0].detail, "ValueError")

    def test_summarize_unhealthy_beats_degraded(self):
        results = [
            health.CheckResult("a", health.STATUS_FAIL, 1.0, critical=True),
            health.CheckResult("b", health.STATUS_WARN, 1.0, critical=False),
        ]
        self.assertEqual(health.summarize(results), health.OVERALL_UNHEALTHY)

    def test_is_ready_ignores_advisory_failures(self):
        results = [health.CheckResult("b", health.STATUS_FAIL, 1.0, critical=False)]
        self.assertTrue(health.is_ready(results))

    def test_is_ready_false_on_critical_failure(self):
        results = [health.CheckResult("a", health.STATUS_FAIL, 1.0, critical=True)]
        self.assertFalse(health.is_ready(results))


class HealthRegistrationTests(TestCase):
    """Every registered check must be classified and well-formed."""

    def tearDown(self):
        health.clear_cache()

    def test_registry_has_the_expected_checks(self):
        names = {c["name"] for c in health.CHECKS}
        self.assertLessEqual(
            {"database", "migrations", "settings_store", "feature_flags", "user_store"},
            names,
        )

    def test_critical_checks_are_classified(self):
        critical = {c["name"] for c in health.CHECKS if c["critical"]}
        self.assertIn("database", critical)
        self.assertIn("migrations", critical)

    def test_all_checks_pass_on_a_healthy_database(self):
        health.clear_cache()
        results = health.run_checks()
        failures = [r for r in results if r.status == health.STATUS_FAIL and r.critical]
        self.assertEqual(failures, [], f"critical checks failed: {[r.as_dict() for r in failures]}")
