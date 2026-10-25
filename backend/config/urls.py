from django.contrib import admin
from django.urls import path, include

from apps.core.health_views import HealthView, HealthLiveView, HealthReadyView
from apps.core.index_views import ServiceIndexView, ApiV1IndexView
from apps.core import errors

urlpatterns = [
    # Service discovery document for the empty path. This backend serves no
    # HTML (the Vue SPA is built and served separately), so without this route
    # GET / falls through to a bare 404.
    path('', ServiceIndexView.as_view(), name='service-index'),

    path('admin/', admin.site.urls),

    # Health probes (flat JSON, unauthenticated, outside /api/v1/ so the
    # Document Object wrapper never applies). Registered both with and without
    # a trailing slash: monitors poll /health exactly, and APPEND_SLASH would
    # otherwise turn a 200 into a 301 redirect.
    path('health', HealthView.as_view(), name='health'),
    path('health/', HealthView.as_view(), name='health-slash'),
    path('health/live', HealthLiveView.as_view(), name='health-live'),
    path('health/live/', HealthLiveView.as_view(), name='health-live-slash'),
    path('health/ready', HealthReadyView.as_view(), name='health-ready'),
    path('health/ready/', HealthReadyView.as_view(), name='health-ready-slash'),

    # Attendance API v1 (for Laravel and external systems)
    path('api/v1/', ApiV1IndexView.as_view(), name='api-v1-index'),
    path('api/v1', ApiV1IndexView.as_view(), name='api-v1-index-noslash'),
    path('api/v1/attendance/', include('apps.attendance.urls')),
    # Classrooms & LiveKit API v1
    path('api/v1/classrooms/', include('apps.classrooms.urls')),
    # Accounts & Profile API v1
    path('api/v1/accounts/', include('apps.accounts.urls')),
    # Billing & Wallet API v1
    path('api/v1/billing/', include('apps.billing.urls')),
    # Core: feature flags & platform settings API v1
    path('api/v1/core/', include('apps.core.urls')),
]

# JSON error pages instead of Django's HTML ones. In DEBUG mode Django
# short-circuits to its technical 404 (which prints the whole URLconf), so
# these only take effect in production -- see apps/core/errors.py.
handler400 = errors.bad_request
handler403 = errors.permission_denied
handler404 = errors.not_found
handler500 = errors.server_error
