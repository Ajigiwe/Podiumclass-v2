"""
Root service index.

This backend is API-only: the Vue SPA is built and served separately (by Vite
in dev, by nginx in production), so nothing here serves HTML for the empty
path. Without an explicit route, GET / falls through to Django's 404 handler.

Instead of a bare 404, the root returns a small discovery document: what this
service is, which version it speaks, and where the entry points are. That makes
a bare-domain hit self-explanatory and gives operators a one-shot way to see
that the process is the one they expect.

Flat JSON (not the ``document`` wrapper) because, like the health probes, this
lives outside ``/api/v1/`` and is a machine/consumer-facing index rather than a
domain resource.
"""

from django.http import JsonResponse
from django.utils import timezone
from django.views import View

import django

from . import health


class ApiV1IndexView(View):
    """
    GET /api/v1/  -- index of the API, plus the envelope contract.

    The root index lists namespaces; this one documents what a caller gets back
    from them, which is the part clients most often get wrong (a single
    resource lives under ``document``, a collection under ``documents``, and
    both failures and the external attendance routes use ``error`` / ``data``).
    """

    http_method_names = ["get", "head"]

    def get(self, request):
        return JsonResponse({
            "apiVersion": "v1",
            "envelope": {
                "single": {"document": {"id": "...", "...": "..."}},
                "collection": {"documents": ["..."], "pagination": {"count": 0, "page": 1}},
                "error": {"error": {"code": "...", "message": "...", "status": 400}},
            },
            "namespaces": {
                "accounts": {
                    "base": "/api/v1/accounts/",
                    "description": "Authentication, profile, and user administration.",
                },
                "classrooms": {
                    "base": "/api/v1/classrooms/",
                    "description": "Sessions, materials, chat, and LiveKit join tokens.",
                },
                "attendance": {
                    "base": "/api/v1/attendance/",
                    "description": "Rosters, student history, and CSV/JSON export. "
                                   "Service-key endpoints use a flat {\"data\": ...} envelope.",
                },
                "billing": {
                    "base": "/api/v1/billing/",
                    "description": "Wallet balance, transactions, and top-ups.",
                },
                "core": {
                    "base": "/api/v1/core/",
                    "description": "Feature flags and platform settings.",
                },
            },
            "health": {
                "aggregate": "/health",
                "liveness": "/health/live",
                "readiness": "/health/ready",
            },
        })


class ServiceIndexView(View):
    """
    GET /  -- service discovery document.

    Public and unauthenticated: it exposes only route names and versions, no
    configuration, no counts, no secrets.
    """

    http_method_names = ["get", "head"]

    def get(self, request):
        payload = {
            "service": "podium-lms",
            "kind": "api",
            "apiVersion": "v1",
            "django": django.get_version(),
            "status": "ok",
            "timestamp": timezone.now().isoformat(),
            "uptimeSeconds": health.uptime_seconds(),
            "endpoints": {
                # Health probes (flat JSON, no auth) -- these are what the
                # external monitor and any load balancer should poll.
                "health": "/health",
                "liveness": "/health/live",
                "readiness": "/health/ready",
                # API namespaces (Document Object contract, JWT auth).
                "api": {
                    "index": "/api/v1/",
                    "accounts": "/api/v1/accounts/",
                    "classrooms": "/api/v1/classrooms/",
                    "attendance": "/api/v1/attendance/",
                    "billing": "/api/v1/billing/",
                    "core": "/api/v1/core/",
                },
                "admin": "/admin/",
            },
        }
        return JsonResponse(payload)
