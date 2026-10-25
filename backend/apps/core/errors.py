"""
JSON error handlers for unmatched routes and unhandled exceptions.

Django's stock 404/500 pages are HTML, and in ``DEBUG`` mode the 404 page
additionally prints the entire URLconf to whoever requested it -- useful while
developing, but it publishes your full route inventory to anyone who typos a
path on a public host.

These handlers return the same ``error`` envelope the ``DocumentJSONRenderer``
produces for API errors, so a consumer that already parses API failures gets an
identical shape for a path that never reached the API at all:

    { "error": { "code": "...", "message": "...", "status": 404 } }

They are plain Django views, not DRF views: the resolver raises ``Http404``
before any DRF machinery runs.

NOTE ON DEBUG: Django consults ``handler404`` only when ``DEBUG`` is False --
with ``DEBUG=True`` it short-circuits to ``technical_404_response`` (see
``django/core/handlers/exception.py``). So in development you will still see
the debug page for unmatched paths; these handlers are what production gets.
That is intentional and worth knowing before assuming a route is wired up.
"""

from django.http import JsonResponse


def _error(code, message, status):
    return JsonResponse(
        {"error": {"code": code, "message": message, "status": status}},
        status=status,
    )


def not_found(request, exception=None):
    """handler404 -- unmatched path. Never echoes the URLconf."""
    return _error(
        "NOT_FOUND",
        "The requested endpoint does not exist. See GET / for available routes.",
        404,
    )


def permission_denied(request, exception=None):
    """handler403 -- authenticated but not allowed."""
    return _error("FORBIDDEN", "You do not have permission to perform this action.", 403)


def server_error(request):
    """handler500 -- unhandled exception. Deliberately reveals nothing."""
    return _error(
        "INTERNAL_ERROR",
        "An unexpected error occurred. The incident has been logged.",
        500,
    )


def bad_request(request, exception=None):
    """handler400 -- malformed request."""
    return _error("BAD_REQUEST", "The request could not be understood.", 400)
