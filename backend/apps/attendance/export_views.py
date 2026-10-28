"""
Read-only attendance history and export for the external integration.

Two endpoints are documented for the Laravel attendance service but were absent
from the Django rewrite:

    GET /api/v1/attendance/students/{studentId}   per-student history
    GET /api/v1/attendance/export                 bulk CSV or JSON export

Both speak the FLAT envelope the external contract specifies::

    {"data": [...], "pagination": {...}}   /  {"data": [...], "meta": {...}}

rather than the ``document`` wrapper used by the rest of the API. That is
deliberate: the Laravel client reads ``$response->json('data')``
(MASTER_SETUP_GUIDE.md 3.3), so wrapping the payload would silently yield an
empty array. Errors still use the documented ``{"error": {...}}`` envelope.

These are machine-to-machine endpoints: they accept ONLY the service key, never
a browser JWT, because they expose every student's attendance history.
"""

import base64
import binascii
import csv
import io
from datetime import datetime, time, timedelta, timezone as dt_timezone

from django.http import HttpResponse
from django.utils import timezone
from django.utils.dateparse import parse_date, parse_datetime
from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.renderers import JSONRenderer
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.auth import PodiumServiceKeyAuthentication
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.core.guards import require_feature

from .models import Attendance, AttendanceLog

# Mirrors the legacy limits so a client sees the same caps it always has.
MAX_SESSIONS = 2000
MAX_ROWS = 50000
HISTORY_DEFAULT_DAYS = 365
EXPORT_DEFAULT_DAYS = 30
HISTORY_DEFAULT_LIMIT = 50
HISTORY_MAX_LIMIT = 200

PRESENT_THRESHOLD = 50


class ExternalApiView(APIView):
    """
    Base for the external attendance contract.

    Two differences from every other view:

    * ``renderer_classes = [JSONRenderer]``: the Document Object renderer would
      nest the payload under ``document``, breaking ``json('data')``.
    * ``handle_exception``: without the Document renderer, DRF would emit
      ``{"detail": ...}``; the external contract documents
      ``{"error": {"code", "message", "status"}}``, so errors are normalised to
      keep both shapes consistent.
    """

    authentication_classes = [PodiumServiceKeyAuthentication]
    renderer_classes = [JSONRenderer]

    def handle_exception(self, exc):
        response = super().handle_exception(exc)
        data = response.data
        if isinstance(data, dict) and "error" in data:
            return response

        if isinstance(data, dict) and "code" in data and "message" in data:
            code, message = data["code"], data["message"]
        elif isinstance(data, dict) and "detail" in data:
            code = data.get("code", getattr(exc, "default_code", "REQUEST_FAILED"))
            message = str(data["detail"])
        else:
            code, message = "REQUEST_FAILED", str(data)

        response.data = {
            "error": {"code": code, "message": message, "status": response.status_code}
        }
        return response


# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------
def _parse_bound(raw, end_of_day):
    """
    Parse an ISO date or date-time.

    A date-only value is widened to the start (``from``) or end (``to``) of that
    day, so ``to=2026-03-01`` includes that whole day rather than excluding it.
    Returns ``None`` for a missing value and raises for a malformed one.
    """
    if not raw:
        return None

    parsed = parse_datetime(raw)
    if parsed is None:
        day = parse_date(raw)
        if day is None:
            raise ValidationError({"code": "INVALID_DATE", "message": "Expected an ISO date or date-time."})
        parsed = datetime.combine(day, time.max if end_of_day else time.min)

    if timezone.is_naive(parsed):
        parsed = timezone.make_aware(parsed, dt_timezone.utc)
    return parsed


def _window(request, default_days):
    """Resolve the (from, to) window, defaulting to the last ``default_days``."""
    now = timezone.now()
    start = _parse_bound(request.query_params.get("from"), end_of_day=False)
    end = _parse_bound(request.query_params.get("to"), end_of_day=True)

    start = start or (now - timedelta(days=default_days))
    end = end or now

    if start > end:
        raise ValidationError({"code": "INVALID_RANGE", "message": "'from' must be before 'to'."})
    return start, end


def _encode_cursor(session):
    """Opaque cursor = base64('<created_at_ms>:<session_id>')."""
    raw = f"{int(session.created_at.timestamp() * 1000)}:{session.id}"
    return base64.urlsafe_b64encode(raw.encode()).decode().rstrip("=")


def _decode_cursor(raw):
    """Return (timestamp_ms, session_id), or raise INVALID_CURSOR."""
    if not raw:
        return None
    try:
        padded = raw + "=" * (-len(raw) % 4)
        decoded = base64.urlsafe_b64decode(padded.encode()).decode()
        ts, session_id = decoded.split(":", 1)
        return int(ts), session_id
    except (binascii.Error, ValueError, UnicodeDecodeError):
        raise ValidationError({
            "code": "INVALID_CURSOR",
            "message": "'cursor' is not a value returned by this API.",
        })


def _normalize(record):
    """
    Flatten the two attendance sources into one shape.

    ``attendance`` and ``attendance_logs`` are separate tables with different
    field names (``student_id``/``student_name`` vs ``user_id``/``user_name``);
    the legacy API merged them and so must this one.
    """
    if isinstance(record, Attendance):
        return {
            "student_id": record.student_id,
            "student_name": record.student_name,
            "index_number": record.student_index_number,
            "is_present": record.is_present,
            "verification_percentage": record.verification_percentage,
            "verifications_sent": record.total_verifications_sent,
            "verifications_completed": record.total_verifications_completed,
            "joined_at": record.joined_at,
            "left_at": record.left_at,
            "source": "attendance",
        }
    return {
        "student_id": record.user_id,
        "student_name": record.user_name,
        "index_number": record.user_index_number,
        "is_present": record.is_present,
        "verification_percentage": record.verification_percentage,
        "verifications_sent": record.total_verifications_sent,
        "verifications_completed": record.total_verifications_completed,
        "joined_at": record.joined_at,
        "left_at": None,  # join logs never record a departure
        "source": "attendance_logs",
    }


def _stay_minutes(norm, session):
    """Stay time in minutes, using the session end when the student never left."""
    end = norm["left_at"] or session.ended_at
    if not norm["joined_at"] or not end:
        return None
    return max(0, int(round((end - norm["joined_at"]).total_seconds() / 60.0)))


def _record_dict(norm, session):
    """One attendance record in the documented camelCase shape."""
    return {
        "studentId": norm["student_id"],
        "studentName": norm["student_name"],
        "indexNumber": norm["index_number"],
        "joined": norm["is_present"],
        "present": norm["verification_percentage"] >= PRESENT_THRESHOLD,
        "stayMinutes": _stay_minutes(norm, session),
        "verificationPercentage": norm["verification_percentage"],
        "verificationsSent": norm["verifications_sent"],
        "verificationsCompleted": norm["verifications_completed"],
        "joinedAt": norm["joined_at"],
        "leftAt": norm["left_at"],
    }


def _session_dict(session):
    return {
        "sessionId": session.id,
        "title": session.title,
        "course": session.course,
        "program": session.program,
        "lecturerId": session.lecturer_id,
        "lecturerName": session.lecturer_name,
        "status": session.status,
        "isActive": session.is_active,
        "createdAt": session.created_at,
        "startedAt": session.started_at,
        "endedAt": session.ended_at,
        "durationMinutes": session.duration_minutes,
        "participantCount": session.participant_count,
    }


def _iso(value):
    return value.isoformat() if value else None


# ---------------------------------------------------------------------------
# GET /api/v1/attendance/students/{studentId}
# ---------------------------------------------------------------------------
class StudentHistoryView(ExternalApiView):
    """
    Attendance history for one student, newest session first.

    Query params: ``from``, ``to`` (default: last 365 days), ``limit`` (1-200,
    default 50), ``cursor``.

    Sources are merged the way the legacy API did it: the canonical
    ``attendance`` rows first, then ``attendance_logs`` rows for sessions that
    have no canonical record (older join events).
    """

    def get(self, request, student_id):
        require_feature("external_attendance_api")

        student = User.objects.filter(pk=student_id).first()
        if student is None:
            raise NotFound({
                "code": "STUDENT_NOT_FOUND",
                "message": f"No student with id '{student_id}'.",
            })

        start, end = _window(request, HISTORY_DEFAULT_DAYS)

        try:
            limit = int(request.query_params.get("limit", HISTORY_DEFAULT_LIMIT))
        except (TypeError, ValueError):
            raise ValidationError({"code": "INVALID_LIMIT", "message": "'limit' must be an integer."})
        limit = max(1, min(limit, HISTORY_MAX_LIMIT))

        cursor = _decode_cursor(request.query_params.get("cursor"))

        sessions = (
            Session.objects
            .filter(created_at__gte=start, created_at__lte=end)
            .order_by("-created_at", "-id")[:MAX_SESSIONS]
        )
        session_by_id = {s.id: s for s in sessions}

        # 1. Canonical records.
        entries = {}
        for record in Attendance.objects.filter(
            student_id=student_id, session_id__in=session_by_id
        ).select_related("session"):
            entries[record.session_id] = _normalize(record)

        # 2. Legacy join logs, only where no canonical record exists.
        for log in AttendanceLog.objects.filter(
            user_id=student_id, session_id__in=session_by_id
        ).select_related("session"):
            if log.session_id in entries:
                continue
            entries[log.session_id] = _normalize(log)

        # 3. Sort newest-first with a stable tiebreak, then page in memory.
        #    (The window is bounded by MAX_SESSIONS, so this stays cheap and is
        #    portable across SQLite and Postgres.)
        rows = sorted(
            (
                (session_by_id[sid], record)
                for sid, record in entries.items()
                if sid in session_by_id
            ),
            key=lambda pair: (pair[0].created_at, pair[0].id),
            reverse=True,
        )

        if cursor is not None:
            cursor_ts, cursor_id = cursor
            rows = [
                (session, record)
                for session, record in rows
                if (int(session.created_at.timestamp() * 1000), session.id) < (cursor_ts, cursor_id)
            ]

        page = rows[:limit]
        has_more = len(rows) > limit
        next_cursor = _encode_cursor(page[-1][0]) if has_more and page else None

        data = [
            {"session": _session_dict(session), "record": _record_dict(record, session)}
            for session, record in page
        ]

        return Response({
            "data": data,
            "pagination": {"limit": limit, "hasMore": has_more, "nextCursor": next_cursor},
            "filters": {
                "studentId": student_id,
                "from": _iso(start),
                "to": _iso(end),
            },
        })


# ---------------------------------------------------------------------------
# GET /api/v1/attendance/export
# ---------------------------------------------------------------------------
EXPORT_COLUMNS = [
    "session_id", "session_title", "course", "program", "session_status",
    "session_date", "started_at", "ended_at", "student_id", "student_name",
    "index_number", "joined_at", "left_at", "stay_minutes", "joined", "present",
    "verification_percentage", "verifications_sent", "verifications_completed",
    "source",
]


class AttendanceExportView(ExternalApiView):
    """
    Bulk attendance for a date window -- intended for a nightly sync.

    Query params: ``from``, ``to`` (default: last 30 days), ``course``,
    ``program``, ``sessionId``, ``format`` (``csv`` default, or ``json``).

    Rows are capped at 50,000; ``X-Result-Truncated: true`` signals a cutoff.
    """

    def get(self, request):
        require_feature("external_attendance_api")

        fmt = (request.query_params.get("format") or "csv").lower()
        if fmt not in ("csv", "json"):
            raise ValidationError({
                "code": "INVALID_FORMAT",
                "message": "'format' must be \"csv\" or \"json\".",
            })

        session_id = request.query_params.get("sessionId")
        start, end = _window(request, EXPORT_DEFAULT_DAYS)

        truncated = False
        if session_id:
            session = Session.objects.filter(pk=session_id).first()
            if session is None:
                raise NotFound({
                    "code": "SESSION_NOT_FOUND",
                    "message": f"No session with id \"{session_id}\".",
                })
            sessions = [session]
        else:
            qs = Session.objects.filter(
                created_at__gte=start, created_at__lte=end
            ).order_by("created_at", "id")

            course = request.query_params.get("course")
            program = request.query_params.get("program")
            if course:
                qs = qs.filter(course__iexact=course)
            if program:
                qs = qs.filter(program__iexact=program)

            sessions = list(qs[:MAX_SESSIONS])
            if len(sessions) >= MAX_SESSIONS:
                truncated = True

        session_by_id = {s.id: s for s in sessions}

        # Group records by session, canonical rows winning over legacy logs.
        by_session = {}
        for record in Attendance.objects.filter(
            session_id__in=session_by_id
        ).select_related("session"):
            by_session.setdefault(record.session_id, {})[record.student_id] = _normalize(record)

        for log in AttendanceLog.objects.filter(session_id__in=session_by_id).select_related("session"):
            bucket = by_session.setdefault(log.session_id, {})
            bucket.setdefault(log.user_id, _normalize(log))

        rows = []
        for session in sessions:
            for norm in by_session.get(session.id, {}).values():
                if len(rows) >= MAX_ROWS:
                    truncated = True
                    break
                rows.append({
                    "session_id": session.id,
                    "session_title": session.title,
                    "course": session.course,
                    "program": session.program,
                    "session_status": session.status,
                    "session_date": _iso(session.created_at),
                    "started_at": _iso(session.started_at),
                    "ended_at": _iso(session.ended_at),
                    "student_id": norm["student_id"],
                    "student_name": norm["student_name"],
                    "index_number": norm["index_number"],
                    "joined_at": _iso(norm["joined_at"]),
                    "left_at": _iso(norm["left_at"]),
                    "stay_minutes": _stay_minutes(norm, session),
                    "joined": norm["is_present"],
                    "present": norm["verification_percentage"] >= PRESENT_THRESHOLD,
                    "verification_percentage": norm["verification_percentage"],
                    "verifications_sent": norm["verifications_sent"],
                    "verifications_completed": norm["verifications_completed"],
                    "source": norm["source"],
                })
            if len(rows) >= MAX_ROWS:
                break

        rows.sort(key=lambda r: (
            r["session_date"] or "", r["student_name"] or "", r["student_id"]
        ))

        meta = {
            "from": _iso(start),
            "to": _iso(end),
            "sessionId": session_id or None,
            "sessionCount": len(sessions),
            "rowCount": len(rows),
            "truncated": truncated,
            "generatedAt": timezone.now().isoformat(),
        }

        if fmt == "json":
            response = Response({"data": rows, "meta": meta})
        else:
            buffer = io.StringIO()
            writer = csv.DictWriter(buffer, fieldnames=EXPORT_COLUMNS, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)
            filename = f"attendance_{start.date().isoformat()}_{end.date().isoformat()}.csv"
            response = HttpResponse(buffer.getvalue(), content_type="text/csv; charset=utf-8")
            response["Content-Disposition"] = f'attachment; filename="{filename}"'

        response["Cache-Control"] = "no-store"
        if truncated:
            response["X-Result-Truncated"] = "true"
        return response
