import json

from django.http import Http404, JsonResponse
from django.test import TestCase, override_settings

from apps.core import errors


class ServiceIndexTests(TestCase):
    """GET / must return a discovery document, not Django's bare 404."""

    def test_root_returns_200_json(self):
        res = self.client.get("/")
        self.assertEqual(res.status_code, 200)
        self.assertIn("application/json", res["Content-Type"])
        body = res.json()
        # Flat, not wrapped: like the health probes this lives outside /api/v1/.
        self.assertNotIn("document", body)
        self.assertEqual(body["service"], "podium-lms")
        self.assertEqual(body["status"], "ok")

    def test_root_lists_entry_points(self):
        body = self.client.get("/").json()
        endpoints = body["endpoints"]
        self.assertEqual(endpoints["health"], "/health")
        self.assertEqual(endpoints["liveness"], "/health/live")
        self.assertEqual(endpoints["readiness"], "/health/ready")
        self.assertEqual(endpoints["admin"], "/admin/")
        for ns in ("accounts", "classrooms", "attendance", "billing", "core"):
            self.assertIn(ns, endpoints["api"])
            self.assertTrue(endpoints["api"][ns].startswith("/api/v1/"))

    def test_root_requires_no_authentication(self):
        res = self.client.get("/")
        self.assertEqual(res.status_code, 200)
        self.assertNotIn("WWW-Authenticate", res)

    def test_root_leaks_no_secrets_or_debug_flag(self):
        """A public index must not advertise config or the DEBUG posture."""
        content = self.client.get("/").content.decode()
        for forbidden in (
            "django-insecure",     # SECRET_KEY fallback
            "ATTENDANCE_API_KEY",  # the service key's NAME
            "GOCSPX-",             # Google OAuth secret prefix
            '"debug"',             # never advertise the DEBUG posture
            "SECRET",
        ):
            self.assertNotIn(forbidden, content)

        # Assert the ACTUAL configured secrets are absent. Reading them from
        # settings avoids embedding the very secrets this test guards against
        # (a literal here would leak them into source).
        from django.conf import settings
        for value in (settings.ATTENDANCE_API_KEY, settings.LIVEKIT_API_SECRET):
            if value and not str(value).startswith("dev-only-"):
                self.assertNotIn(value, content)

    def test_root_rejects_non_get(self):
        self.assertEqual(self.client.post("/").status_code, 405)


@override_settings(DEBUG=False)
class JsonErrorPageTests(TestCase):
    """
    With DEBUG off, unmatched paths must return the JSON error envelope rather
    than Django's HTML page (which also prints the whole URLconf in DEBUG).

    override_settings(DEBUG=False) is required because Django short-circuits to
    technical_404_response whenever DEBUG is True.
    """

    def test_unknown_path_returns_json_404(self):
        res = self.client.get("/definitely/not/a/route")
        self.assertEqual(res.status_code, 404)
        self.assertIn("application/json", res["Content-Type"])
        err = res.json()["error"]
        self.assertEqual(err["code"], "NOT_FOUND")
        self.assertEqual(err["status"], 404)

    def test_unknown_path_does_not_leak_urlconf(self):
        """The failure mode this fixes: DEBUG's 404 printed every route."""
        content = self.client.get("/definitely/not/a/route").content.decode()
        for route in (
            "api/v1/attendance/",
            "api/v1/classrooms/",
            "health/ready",
            "namespace='admin'",
            "Using the URLconf",
        ):
            self.assertNotIn(route, content)

    def test_unknown_api_path_is_json_not_html(self):
        res = self.client.get("/api/v1/nonexistent/")
        self.assertEqual(res.status_code, 404)
        self.assertIn("application/json", res["Content-Type"])
        # Not the HTML debug page.
        self.assertNotIn("<html", res.content.decode().lower())


class ErrorHandlerUnitTests(TestCase):
    """The handlers directly -- they are plain Django views, not DRF views."""

    @staticmethod
    def _body(res):
        # JsonResponse is an HttpResponse: it has no .json() (that is a
        # test-client convenience), so decode + parse manually.
        return json.loads(res.content.decode())

    def test_not_found_shape(self):
        res = errors.not_found(HttpRequestStub(), Http404("gone"))
        self.assertIsInstance(res, JsonResponse)
        self.assertEqual(res.status_code, 404)
        body = self._body(res)
        self.assertEqual(body["error"]["code"], "NOT_FOUND")
        self.assertEqual(body["error"]["status"], 404)
        self.assertTrue(body["error"]["message"])
        # Must not echo the exception text (may carry resolver internals).
        self.assertNotIn("gone", res.content.decode())

    def test_server_error_shape_and_opacity(self):
        res = errors.server_error(HttpRequestStub())
        self.assertEqual(res.status_code, 500)
        body = self._body(res)["error"]
        self.assertEqual(body["code"], "INTERNAL_ERROR")
        self.assertEqual(body["status"], 500)
        self.assertNotIn("Traceback", res.content.decode())

    def test_permission_denied_shape(self):
        res = errors.permission_denied(HttpRequestStub(), Exception("boom secret"))
        self.assertEqual(res.status_code, 403)
        self.assertEqual(self._body(res)["error"]["code"], "FORBIDDEN")
        self.assertNotIn("boom secret", res.content.decode())

    def test_bad_request_shape(self):
        res = errors.bad_request(HttpRequestStub(), None)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(self._body(res)["error"]["code"], "BAD_REQUEST")


class ErrorHandlersWiredTests(TestCase):
    """config.urls must actually resolve to these handlers.

    Django looks them up with ``resolver.resolve_error_handler(n)``, which
    reads ``handler<n>`` off the urlconf *module* (see
    django/urls/resolvers.py) -- not off the resolver object itself.
    """

    def test_urlconf_exposes_error_handlers(self):
        from django.urls import get_resolver

        resolver = get_resolver()
        self.assertEqual(resolver.resolve_error_handler(400), errors.bad_request)
        self.assertEqual(resolver.resolve_error_handler(403), errors.permission_denied)
        self.assertEqual(resolver.resolve_error_handler(404), errors.not_found)
        self.assertEqual(resolver.resolve_error_handler(500), errors.server_error)


class ApiV1IndexTests(TestCase):
    """GET /api/v1/ documents the envelope contract and namespaces."""

    def test_index_returns_namespaces_and_envelope(self):
        res = self.client.get("/api/v1/")
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertEqual(body["apiVersion"], "v1")
        self.assertIn("envelope", body)
        for key in ("single", "collection", "error"):
            self.assertIn(key, body["envelope"])
        self.assertIn("document", body["envelope"]["single"])
        self.assertIn("documents", body["envelope"]["collection"])
        for ns in ("accounts", "classrooms", "attendance", "billing", "core"):
            self.assertIn(ns, body["namespaces"])

    def test_index_works_without_trailing_slash(self):
        self.assertEqual(self.client.get("/api/v1").status_code, 200)

    def test_index_requires_no_authentication(self):
        res = self.client.get("/api/v1/")
        self.assertEqual(res.status_code, 200)
        self.assertNotIn("WWW-Authenticate", res)

    def test_root_index_links_to_api_index(self):
        body = self.client.get("/").json()
        self.assertEqual(body["endpoints"]["api"]["index"], "/api/v1/")


class HttpRequestStub:
    """Minimal stand-in: these handlers never touch the request object."""

    def __init__(self):
        self.method = "GET"
        self.path = "/"
