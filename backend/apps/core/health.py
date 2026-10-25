"""
Health engine -- composable liveness and readiness checks.

Why an engine rather than a single "always return 200" view:

  * Monitors, load balancers, and the external attendance service poll this
    endpoint on an interval, so it must be cheap, unauthenticated, and must
    never leak configuration. Each check reports only its name, a status, a
    timing, and a SAFE detail string. Raw exception messages are deliberately
    reduced to the exception class name, because a database error string can
    carry a host, user, or DSN fragment -- unacceptable on a public endpoint.

  * A bare "up" boolean is not actionable. Every check is classified as either
    *critical* or *advisory*, and the aggregate status distinguishes:

        healthy    - every check passed; route traffic normally
        degraded   - advisory checks failing; still serving traffic
        unhealthy  - a critical dependency is down; stop routing traffic

  * The HTTP status does the work for clients that do not parse bodies:
    200 when we can serve, 503 when we cannot. Alerting can key off the
    status code alone.

Adding a check is one decorated function::

    @health_check("object_storage", critical=False)
    def check_object_storage():
        ...  # return Probe(STATUS_PASS, "reachable")

Checks are cached for a short TTL so a burst of pollers does not become a
burst of database queries. Call :func:`clear_cache` after mutating
infrastructure to force the next poll to re-probe.
"""

from collections import namedtuple
from datetime import datetime, timezone
import time

STATUS_PASS = "pass"
STATUS_WARN = "warn"
STATUS_FAIL = "fail"

OVERALL_HEALTHY = "healthy"
OVERALL_DEGRADED = "degraded"
OVERALL_UNHEALTHY = "unhealthy"

# Short enough that a recovered dependency is noticed quickly, long enough that
# a 30s poll interval costs at most one probe per TTL window.
CACHE_TTL_SECONDS = 5.0

# Process start, reported as uptime so operators can spot crash-looping.
_STARTED_AT = time.time()

# What a check function returns: a status plus a safe, human-readable detail.
Probe = namedtuple("Probe", ["status", "detail"], defaults=[STATUS_PASS, ""])

# Registry entries populated by the @health_check decorator.
CHECKS = []


class CheckResult:
    """One executed check, with its timing and classification."""

    __slots__ = ("name", "status", "duration_ms", "detail", "critical")

    def __init__(self, name, status, duration_ms, detail="", critical=True):
        self.name = name
        self.status = status
        self.duration_ms = duration_ms
        self.detail = detail
        self.critical = critical

    @property
    def passed(self):
        return self.status == STATUS_PASS

    @property
    def degraded(self):
        return self.status in (STATUS_WARN, STATUS_FAIL)

    def as_dict(self):
        return {
            "name": self.name,
            "status": self.status,
            "durationMs": round(self.duration_ms, 2),
            "detail": self.detail,
            "critical": self.critical,
        }


def health_check(name, critical=True):
    """Register a health check. ``critical`` failures force status ``unhealthy``."""

    def decorator(fn):
        CHECKS.append({"name": name, "fn": fn, "critical": bool(critical)})
        return fn

    return decorator


def _ms(since):
    return (time.perf_counter() - since) * 1000.0


def run_checks(critical_only=False):
    """
    Execute the registered checks, timing each one.

    Any exception is caught and recorded as a failure whose detail is only the
    exception class name -- never the message, which may embed credentials.
    """
    results = []
    for spec in CHECKS:
        if critical_only and not spec["critical"]:
            continue

        start = time.perf_counter()
        try:
            probe = spec["fn"]()
        except Exception as exc:  # noqa: BLE001 - a probe must never crash the view
            status, detail = STATUS_FAIL, type(exc).__name__
        else:
            if isinstance(probe, Probe):
                status, detail = probe.status, probe.detail
            else:
                status, detail = STATUS_FAIL, "check returned an unexpected value"

        results.append(
            CheckResult(
                name=spec["name"],
                status=status,
                duration_ms=_ms(start),
                detail=detail,
                critical=spec["critical"],
            )
        )
    return results


def summarize(results):
    """
    Fold check results into an overall status.

    * any critical failure -> unhealthy
    * any warn/advisory failure -> degraded
    * otherwise -> healthy
    """
    if any(r.status == STATUS_FAIL and r.critical for r in results):
        return OVERALL_UNHEALTHY
    if any(r.degraded for r in results):
        return OVERALL_DEGRADED
    return OVERALL_HEALTHY


def is_ready(results):
    """Readiness: no critical check may be failing."""
    return not any(r.status == STATUS_FAIL and r.critical for r in results)


def uptime_seconds():
    """Seconds since this process started handling requests."""
    return round(time.time() - _STARTED_AT, 1)


# --------------------------------------------------------------------------
# Cache
# --------------------------------------------------------------------------
_cache = {"at": 0.0, "results": None}


def clear_cache():
    """Invalidate cached probes (call after infrastructure changes)."""
    _cache["at"] = 0.0
    _cache["results"] = None


def cached_checks(critical_only=False):
    """Run checks at most once per CACHE_TTL_SECONDS."""
    now = time.time()
    if _cache["results"] is None or (now - _cache["at"]) > CACHE_TTL_SECONDS:
        _cache["results"] = run_checks(critical_only=critical_only)
        _cache["at"] = now
    return list(_cache["results"])


def build_report(results, status=None):
    """Assemble the JSON-serialisable health payload."""
    overall = status or summarize(results)
    return {
        "status": overall,
        "checks": [r.as_dict() for r in results],
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "uptimeSeconds": uptime_seconds(),
    }


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------
@health_check("database", critical=True)
def check_database():
    """The database must accept a trivial query."""
    from django.db import connection

    with connection.cursor() as cursor:
        cursor.execute("SELECT 1")
        cursor.fetchone()
    return Probe(STATUS_PASS, "query ok")


@health_check("migrations", critical=True)
def check_migrations():
    """Unapplied migrations mean the schema does not match the code."""
    from django.db import connection
    from django.db.migrations.executor import MigrationExecutor

    executor = MigrationExecutor(connection)
    plan = executor.migration_plan(executor.loader.graph.leaf_nodes())
    if plan:
        pending = ", ".join(f"{m.app_label}.{m.name}" for m, _ in plan[:3])
        more = "" if len(plan) <= 3 else f" (+{len(plan) - 3} more)"
        return Probe(STATUS_FAIL, f"{len(plan)} unapplied: {pending}{more}")
    return Probe(STATUS_PASS, "up to date")


@health_check("settings_store", critical=True)
def check_settings_store():
    """Runtime configuration must be readable."""
    from .models import SystemSetting

    count = SystemSetting.objects.count()
    return Probe(STATUS_PASS, f"{count} settings")


@health_check("feature_flags", critical=False)
def check_feature_flags():
    """Advisory: the feature registry should resolve and be queryable."""
    from . import features

    flags = features.public_flags()
    if not flags:
        return Probe(STATUS_WARN, "no flags registered")
    enabled = sum(1 for v in flags.values() if v)
    return Probe(STATUS_PASS, f"{enabled}/{len(flags)} enabled")


@health_check("user_store", critical=False)
def check_user_store():
    """Advisory: the user table is reachable and holds an admin account."""
    from apps.accounts.models import User

    admins = User.objects.filter(role="admin", is_active=True).count()
    if admins == 0:
        return Probe(STATUS_WARN, "no active admin account")
    return Probe(STATUS_PASS, f"{admins} active admin(s)")
