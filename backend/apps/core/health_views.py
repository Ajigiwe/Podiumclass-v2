"""
Health endpoints.

Design notes:

  * Flat JSON, not the ``document`` wrapper. Health consumers (load balancers,
    uptime monitors, container orchestrators) expect a conventional
    ``{"status": ...}`` body, and DRF's default DocumentJSONRenderer would
    nest it. These views therefore set ``renderer_classes`` explicitly.

  * Correct HTTP status codes: 200 when healthy/degraded (still serving) and
    503 when unhealthy, so a prober only needs the status line.

  * Public (AllowAny). A health probe has no credential. The payload contains
    only check names, statuses, timings, and safe detail strings -- never
    config values or exception messages that could embed secrets.
"""

from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.renderers import JSONRenderer
from rest_framework.views import APIView

from django.utils import timezone

from . import health


class HealthBaseView(APIView):
    """Shared wiring: no auth, plain JSON renderer, no throttling."""

    permission_classes = [AllowAny]
    renderer_classes = [JSONRenderer]
    authentication_classes = []  # never challenge a probe with WWW-Authenticate
    throttle_classes = []

    def finalize_response(self, request, response, *args, **kwargs):
        # Probes and dashboards poll frequently; discourage intermediary caches
        # from serving a stale health verdict.
        response["Cache-Control"] = "no-store"
        return super().finalize_response(request, response, *args, **kwargs)


class HealthLiveView(HealthBaseView):
    """
    GET /health/live  -- liveness probe.

    Answers "is this process up and able to serve a request at all?" It runs
    NO dependency checks: a liveness probe must not fail because a downstream
    is temporarily down, otherwise an orchestrator would restart a process that
    is perfectly healthy. Always 200 while the process can respond.
    """

    def get(self, request):
        return Response(
            {
                "status": "alive",
                "time": timezone.now().isoformat(),
                "uptimeSeconds": health.uptime_seconds(),
            }
        )


class HealthReadyView(HealthBaseView):
    """
    GET /health/ready -- readiness probe.

    Runs only CRITICAL checks (database, migrations, settings store). A
    non-critical/advisory problem does not stop us from serving traffic, so it
    must not fail readiness. 200 when ready, 503 when a critical dependency is
    down (which also means "take this instance out of the load balancer").
    """

    def get(self, request):
        results = health.cached_checks(critical_only=True)
        ready = health.is_ready(results)
        payload = health.build_report(
            results,
            status=health.OVERALL_HEALTHY if ready else health.OVERALL_UNHEALTHY,
        )
        payload["ready"] = ready
        return Response(payload, status=200 if ready else 503)


class HealthView(HealthBaseView):
    """
    GET /health  -- the full aggregate report (what monitors poll).

    Runs every registered check (critical + advisory), folds them into
    healthy / degraded / unhealthy, and returns 200 for healthy or degraded
    (still serving) and 503 for unhealthy.
    """

    def get(self, request):
        results = health.cached_checks()
        status = health.summarize(results)
        payload = health.build_report(results, status=status)
        http_status = 503 if status == health.OVERALL_UNHEALTHY else 200
        return Response(payload, status=http_status)
