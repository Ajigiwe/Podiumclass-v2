from django.db import models
from django.http import HttpResponse
from django.conf import settings
from django.utils.dateparse import parse_date, parse_datetime
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.exceptions import AuthenticationFailed, PermissionDenied, NotFound, ValidationError
from apps.accounts.auth import (
    PodiumAuthentication,
    decode_token,
)
from apps.core.guards import require_feature
from .models import Attendance, AttendanceLog, Verification, VerificationResponse
from . import counts, engine
from apps.classrooms.access import can_moderate
from apps.classrooms.models import Session
from .serializers import (
    AttendanceRecordSerializer,
    SessionAttendanceSerializer,
    StudentAttendanceSerializer,
)

def check_attendance_api_key(request):
    """
    Authenticate a caller of the attendance API.

    Two caller classes are supported:

      * The external Laravel service authenticates with the server-to-server
        ``ATTENDANCE_API_KEY`` via ``X-API-Key`` or ``Authorization: Bearer``.
      * The Vue SPA authenticates with a normal user JWT.

    Returns the authenticated ``User`` for JWT callers, or ``None`` for the
    service key (which acts as a trusted system caller with no single user).
    Raises ``AuthenticationFailed`` when neither credential is valid.
    """
    expected_key = getattr(settings, 'ATTENDANCE_API_KEY', None)

    auth_header = request.headers.get('Authorization') or ''
    api_key_header = request.headers.get('X-API-Key') or ''

    raw = None
    if auth_header.startswith('Bearer '):
        raw = auth_header.split('Bearer ', 1)[1].strip()
    elif api_key_header:
        raw = api_key_header.strip()

    if not raw:
        raise AuthenticationFailed({"code": "MISSING_API_KEY", "message": "API key or user token required."})

    # Service-to-server key: trusted system caller, no single user attached.
    if expected_key and raw == expected_key:
        return None

    # Otherwise treat the credential as a user JWT.
    payload = decode_token(raw)
    from apps.accounts.models import User
    try:
        user = User.objects.get(pk=payload["sub"])
    except User.DoesNotExist:
        raise AuthenticationFailed({"code": "USER_NOT_FOUND", "message": "The account for this token no longer exists."})
    if not user.is_active or user.status == "disabled":
        raise AuthenticationFailed({"code": "ACCOUNT_DISABLED", "message": "This account has been disabled."})
    return user


def _require_lecturer(user):
    """Service key is trusted; JWT callers must be a lecturer or admin."""
    if user is None:
        return
    if user.role not in ("lecturer", "admin"):
        raise PermissionDenied({"code": "LECTURER_REQUIRED", "message": "Only lecturers or admins may perform this action."})


def _require_session_access(user, session_id):
    """
    Service key, lecturer/admin, or an active co-host of *this* session.

    Driving a room's timed presence checks is exactly what a co-host is
    appointed to do, so session-scoped verification endpoints accept them.

    The unscoped session list deliberately keeps ``_require_lecturer``: a
    co-host's authority never extends past the room they were handed.
    """
    if user is None:
        return  # trusted service key
    if user.role in ("lecturer", "admin"):
        return
    if session_id:
        # Deliberately not filtered on is_deleted: a deleted class keeps its
        # attendance record, and its co-hosts keep the access they had to it.
        session = Session.objects.filter(id=session_id).first()
        if session is not None and can_moderate(user, session):
            return
    raise PermissionDenied({"code": "LECTURER_REQUIRED", "message": "Only lecturers, admins or co-hosts of this session may perform this action."})

class AttendanceSessionsAPIView(APIView):
    """
    GET /api/v1/attendance/sessions
    Returns sessions with attendance metadata wrapped in the Document Object.

    Accepts the server-to-server API key or a browser JWT (see
    ``check_attendance_api_key``). Reading the roster is a lecturer/admin or
    trusted-service action.
    """
    # Dual-use endpoints: the Vue SPA sends a user JWT, the Laravel service sends
    # the service key. PodiumAuthentication accepts both; the previous
    # service-key-only authenticator rejected every browser request with
    # INVALID_API_KEY before the view ran.
    authentication_classes = [PodiumAuthentication]

    def get(self, request):
        require_feature("external_attendance_api")
        caller = check_attendance_api_key(request)
        _require_lecturer(caller)

        # Attendance is a permanent record, so this history view deliberately
        # includes deleted classes: removing a class from the dashboard must not
        # remove the evidence of who attended it.
        queryset = Session.objects.all().order_by('-created_at')

        # Filters
        status_param = request.query_params.get('status')
        if status_param:
            queryset = queryset.filter(status=status_param)

        course_param = request.query_params.get('course')
        if course_param:
            queryset = queryset.filter(course__iexact=course_param)

        from_param = request.query_params.get('from')
        if from_param:
            dt = parse_date(from_param) or parse_datetime(from_param)
            if dt:
                queryset = queryset.filter(created_at__gte=dt)

        to_param = request.query_params.get('to')
        if to_param:
            dt = parse_date(to_param) or parse_datetime(to_param)
            if dt:
                queryset = queryset.filter(created_at__lte=dt)

        limit = min(int(request.query_params.get('limit', 25)), 100)
        sessions = queryset[:limit]

        serializer = SessionAttendanceSerializer(sessions, many=True)
        # Returns { "documents": [ ... ], "pagination": { "limit": limit, "count": ... } }.
        # "data" is a compatibility alias: the external Laravel client reads
        # $response->json('data') (MASTER_SETUP_GUIDE.md), while the Vue SPA
        # reads "documents". Emitting both keeps each consumer working without
        # duplicating any query work.
        return Response({
            "data": serializer.data,
            "documents": serializer.data,
            "pagination": {
                "limit": limit,
                "count": queryset.count(),
            }
        })

class AttendanceSessionDetailAPIView(APIView):
    """
    GET /api/v1/attendance/sessions/<sessionId>
    Returns detailed attendance roster for one session wrapped in the Document Object.

    Lecturer/admin or trusted service only — the roster exposes every student.
    """
    # Dual-use endpoints: the Vue SPA sends a user JWT, the Laravel service sends
    # the service key. PodiumAuthentication accepts both; the previous
    # service-key-only authenticator rejected every browser request with
    # INVALID_API_KEY before the view ran.
    authentication_classes = [PodiumAuthentication]

    def get(self, request, session_id):
        require_feature("external_attendance_api")
        caller = check_attendance_api_key(request)
        _require_session_access(caller, session_id)

        try:
            session = Session.objects.get(id=session_id)
        except Session.DoesNotExist:
            raise NotFound({"code": "SESSION_NOT_FOUND", "message": f"Session '{session_id}' not found."})

        records_qs = Attendance.objects.filter(session=session).select_related('session')
        # Answer "did any check run in this class?" once, for the whole roster:
        # it is the difference between a student who ignored the questions and
        # one who was never asked any.
        records_serializer = AttendanceRecordSerializer(
            records_qs, many=True, context={"checks_ran": counts.checks_ran_for(session)}
        )
        session_serializer = SessionAttendanceSerializer(session)

        records_data = records_serializer.data
        total = len(records_data)
        present = sum(1 for r in records_data if r['present'])
        joined = sum(1 for r in records_data if r['joined'])
        absent = total - present
        avg_pct = round(sum(r['verificationPercentage'] for r in records_data) / max(total, 1), 1)

        include_mode = request.query_params.get('include', 'records')

        document_payload = {
            "session": session_serializer.data,
            "summary": {
                "total": total,
                "joined": joined,
                "present": present,
                "absent": absent,
                "averageVerificationPercentage": avg_pct,
            }
        }
        if include_mode != 'summary':
            document_payload["records"] = records_data

        # Responds in the document object. "data" is a compatibility alias for
        # the external Laravel client, which reads $response->json('data').
        return Response({
            "data": document_payload,
            "document": document_payload,
        })

class AttendanceJoinView(APIView):
    """
    POST /api/v1/attendance/join
    Record a participant joining a classroom session.

    For JWT callers the student is always the authenticated user. A client may
    not join as someone else by passing an arbitrary studentId, which would
    let anyone forge another student's attendance record. The explicit
    studentId parameter is still honoured for the trusted service key only.
    """
    # Dual-use endpoints: the Vue SPA sends a user JWT, the Laravel service sends
    # the service key. PodiumAuthentication accepts both; the previous
    # service-key-only authenticator rejected every browser request with
    # INVALID_API_KEY before the view ran.
    authentication_classes = [PodiumAuthentication]

    def post(self, request):
        import uuid
        from django.utils import timezone
        from apps.accounts.models import User

        auth_user = check_attendance_api_key(request)

        session_id = request.data.get("sessionId")
        student_name = request.data.get("studentName")
        student_index = request.data.get("studentIndex")

        if auth_user is not None:
            # Browser caller: identity comes from the verified token.
            student = auth_user
            student_id = student.id
            if not student_name:
                student_name = student.full_name
            if not student_index:
                student_index = student.index_number
        else:
            # Trusted service caller: explicit identity is allowed.
            student_id = request.data.get("studentId")
            if not session_id or not student_id:
                return Response(
                    {"error": {"code": "VALIDATION_ERROR", "message": "sessionId and studentId are required."}},
                    status=400
                )
            student_name = student_name or "Student"
            student, _ = User.objects.get_or_create(
                id=student_id,
                defaults={"full_name": student_name, "email": f"{student_id}@student.podiumclass.online", "role": "student"}
            )

        if not session_id:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "sessionId is required."}},
                status=400
            )

        try:
            session = Session.objects.get(id=session_id)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=404
            )

        attendance, created = Attendance.objects.get_or_create(
            session=session,
            student=student,
            defaults={
                "id": f"att_{uuid.uuid4().hex[:16]}",
                "student_name": student_name or student.full_name,
                "student_index_number": student_index,
                "joined_at": timezone.now(),
                "is_present": True,
            }
        )

        # Also create/update flat attendance log
        log_id = f"{session_id}_{student.id}"
        AttendanceLog.objects.update_or_create(
            id=log_id,
            defaults={
                "session": session,
                "user": student,
                "user_name": student_name or student.full_name,
                "user_index_number": student_index,
                "user_email": student.email,
                "role": student.role,
                "session_title": session.title,
                "is_present": True,
            }
        )

        return Response({
            "document": {
                "id": attendance.id,
                "sessionId": session.id,
                "studentId": student.id,
                "joinedAt": attendance.joined_at,
                "isPresent": attendance.is_present,
            }
        })

class MyAttendanceView(APIView):
    """
    GET /api/v1/attendance/mine/

    The caller's own attendance records, newest class first, wrapped in
    ``documents``.

    Students need to see their own record -- which checks they answered, whether
    it counted, and how long they were in the room. Every other attendance
    endpoint is lecturer-scoped (roster, export) or service-key only
    (student history), so the student clients had nothing real to read and
    showed zeros. Identity comes from the token, never from a parameter.
    """
    authentication_classes = [PodiumAuthentication]

    def get(self, request):
        caller = check_attendance_api_key(request)
        if caller is None:
            # The trusted service key has no single student attached; ask it to
            # use /attendance/students/<id> instead of guessing.
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "A user token is required for your own attendance."}},
                status=400,
            )

        queryset = (
            Attendance.objects
            .filter(student=caller)
            .select_related("session")
            # How many checks each class fired, so "present" can tell "sat
            # through a class that never asked" from "ignored the questions"
            # without a query per row.
            .annotate(**counts.my_records_annotations())
            .order_by("-joined_at")
        )

        limit_param = request.query_params.get("limit")
        try:
            limit = max(1, min(int(limit_param), 200)) if limit_param else 100
        except (TypeError, ValueError):
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "'limit' must be an integer."}},
                status=400,
            )

        records = queryset[:limit]
        serializer = StudentAttendanceSerializer(records, many=True)
        return Response({
            "data": serializer.data,
            "documents": serializer.data,
        })


class VerificationTriggerView(APIView):
    """
    POST /api/v1/attendance/verification/trigger
    Trigger a verification check for all students in a classroom session.

    Only the session host, another lecturer, or the trusted service may
    trigger a check. Anyone able to trigger verifications could otherwise
    disrupt or manipulate attendance for a running class.
    """
    # Dual-use endpoints: the Vue SPA sends a user JWT, the Laravel service sends
    # the service key. PodiumAuthentication accepts both; the previous
    # service-key-only authenticator rejected every browser request with
    # INVALID_API_KEY before the view ran.
    authentication_classes = [PodiumAuthentication]

    def post(self, request):
        from django.utils import timezone

        require_feature("attendance_verification")
        auth_user = check_attendance_api_key(request)
        # Auth on a session-scoped action needs the session: read it first so a
        # co-host of that room is recognised. A caller without credentials is
        # already rejected inside check_attendance_api_key.
        session_id = request.data.get("sessionId")
        _require_session_access(auth_user, session_id)

        if not session_id:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "sessionId is required."}},
                status=400
            )

        try:
            session = Session.objects.get(id=session_id)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=404
            )

        # The actor is the authenticated user, not a client-supplied label.
        if auth_user is not None:
            triggered_by = auth_user.id
        else:
            triggered_by = request.data.get("triggeredBy", "host")
        time_limit = request.data.get("timeLimitSeconds")

        verif = engine.trigger_verification(
            session,
            triggered_by=triggered_by,
            time_limit_seconds=int(time_limit) if time_limit else None,
        )

        return Response({
            "document": {
                "verificationId": verif.id,
                "verificationNumber": verif.verification_number,
                "expiresAt": verif.expires_at,
                "timeLimitSeconds": verif.time_limit_seconds,
            }
        })


class VerificationActiveView(APIView):
    """
    GET /api/v1/attendance/verification/active?sessionId=...&after=<number>

    Returns the check every room member needs right now: the latest
    verification in the session (with server time so clients can compute an
    honest countdown) and when the automated scheduler is due to fire the
    next one. The classroom client polls this — both so students get the
    prompt even if a LiveKit data packet is missed, and so moderators see
    scheduler progress without running the schedule in their own tab.

    ``after`` skips 304-equivalent responses when nothing newer than the
    client's last seen check is active.
    """
    authentication_classes = [PodiumAuthentication]

    def get(self, request):
        require_feature("attendance_verification")
        from django.utils import timezone

        auth_user = check_attendance_api_key(request)

        session_id = request.query_params.get("sessionId")
        if not session_id:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "sessionId is required."}},
                status=400
            )

        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=404
            )

        now = timezone.now()
        latest = (
            Verification.objects
            .filter(session=session)
            .order_by("-verification_number")
            .first()
        )

        settings = engine.parse_auto_settings(session)
        completed = engine.checks_completed(session)
        due_at = engine.next_check_due_at(session, settings)
        scheduled_active = (
            settings["enabled"]
            and completed < settings["check_count"]
            and due_at is not None
        )

        after_param = request.query_params.get("after")
        try:
            after = int(after_param) if after_param else None
        except (TypeError, ValueError):
            after = None

        # A check is surfaced until it expires; a past one is only news to
        # clients that never saw it, and expired checks no longer allow a
        # response anyway (VerificationRespondView answers 410).
        verif_payload = None
        if latest is not None and latest.expires_at > now:
            if after is None or latest.verification_number > after:
                verif_payload = {
                    "verificationId": latest.id,
                    "verificationNumber": latest.verification_number,
                    "triggeredAt": latest.triggered_at,
                    "expiresAt": latest.expires_at,
                    "timeLimitSeconds": latest.time_limit_seconds,
                    "secondsRemaining": int(
                        (latest.expires_at - now).total_seconds()
                    ),
                }
        elif latest is not None and after is not None and latest.verification_number > after:
            # Tell the client it missed an (already expired) check so its
            # "last seen" watermark can advance past it.
            verif_payload = None

        return Response({
            "document": {
                "serverNow": now,
                "checksCompleted": completed,
                "checksTarget": settings["check_count"],
                "autoEnabled": settings["enabled"],
                "nextCheckAt": due_at if scheduled_active else None,
                "verification": verif_payload,
            },
            # Legacy key: the SPA's getDocument() reads `document`.
        })

class VerificationRespondView(APIView):
    """
    POST /api/v1/attendance/verification/respond
    Student responds to a verification prompt within the time limit.

    The responding student is always the authenticated user for JWT callers.
    Accepting a client-supplied studentId would let one student answer on
    another's behalf and fake their attendance.
    """
    # Dual-use endpoints: the Vue SPA sends a user JWT, the Laravel service sends
    # the service key. PodiumAuthentication accepts both; the previous
    # service-key-only authenticator rejected every browser request with
    # INVALID_API_KEY before the view ran.
    authentication_classes = [PodiumAuthentication]

    def post(self, request):
        import uuid
        from django.utils import timezone
        from apps.accounts.models import User

        auth_user = check_attendance_api_key(request)

        session_id = request.data.get("sessionId")
        verification_id = request.data.get("verificationId")

        if auth_user is not None:
            student = auth_user
            student_id = student.id
        else:
            student_id = request.data.get("studentId")
            if not student_id:
                return Response(
                    {"error": {"code": "VALIDATION_ERROR", "message": "studentId is required."}},
                    status=400
                )

        if not session_id or not verification_id or not student_id:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "sessionId, verificationId, and studentId are required."}},
                status=400
            )

        try:
            verif = Verification.objects.get(id=verification_id, session_id=session_id)
        except Verification.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": "Verification event not found."}},
                status=404
            )

        now = timezone.now()
        if now > verif.expires_at:
            return Response(
                {"error": {"code": "EXPIRED", "message": "Verification window has expired."}},
                status=410
            )

        # For JWT callers `student` is already the authenticated user. Only
        # the trusted service key may resolve (or provision) an explicit ID.
        if auth_user is None:
            try:
                student = User.objects.get(id=student_id)
            except User.DoesNotExist:
                student = User.objects.create(
                    id=student_id,
                    email=f"{student_id}@student.podiumclass.online",
                    full_name="Student Attendee"
                )

        resp_time = int((now - verif.triggered_at).total_seconds())

        response_id = f"vresp_{uuid.uuid4().hex[:16]}"
        VerificationResponse.objects.update_or_create(
            verification=verif,
            student=student,
            defaults={
                "id": response_id,
                "responded_at": now,
                "response_time_seconds": resp_time,
            }
        )

        # Update the student's attendance stats.
        #
        # The record is created if it is missing. Answering a check *is* the
        # proof of presence, so a student who is in the room but whose join was
        # never registered (a dropped request, or a device that resumed into an
        # already-running class) must still get credit for it. Previously this
        # only updated an existing row, so their answer was silently discarded
        # and the roster showed them absent.
        att, _ = Attendance.objects.get_or_create(
            session_id=session_id,
            student=student,
            defaults={
                "id": f"att_{uuid.uuid4().hex[:16]}",
                "student_name": student.full_name or "Student",
                "student_index_number": student.index_number,
                "joined_at": now,
                "is_present": True,
            },
        )
        att.total_verifications_completed += 1
        sent = max(att.total_verifications_sent, 1)
        att.verification_percentage = min(100, int((att.total_verifications_completed / sent) * 100))
        att.last_responded_at = now
        att.is_present = True
        att.save()

        # Update log (created for the same reason as the record above)
        log_id = f"{session_id}_{student_id}"
        log, _ = AttendanceLog.objects.get_or_create(
            id=log_id,
            defaults={
                "session_id": session_id,
                "user": student,
                "user_name": student.full_name or "Student",
                "user_index_number": student.index_number,
                "user_email": student.email,
                "role": student.role,
                "session_title": verif.session.title,
                "joined_at": now,
            },
        )
        log.total_verifications_completed += 1
        sent = max(log.total_verifications_sent, 1)
        log.verification_percentage = min(100, int((log.total_verifications_completed / sent) * 100))
        log.last_responded_at = now
        log.is_present = True
        log.save()

        return Response({
            "document": {
                "success": True,
                "verificationId": verif.id,
                "studentId": student.id,
                "responseTimeSeconds": resp_time,
            }
        })


# --- host-facing roster export ---------------------------------------------


def _human_moment(value):
    """ISO timestamp -> "2026-10-27 14:05" in the server's local zone."""
    if not value:
        return ""
    from django.utils.dateparse import parse_datetime
    from django.utils import timezone as tz

    moment = parse_datetime(str(value))
    if moment is None:
        return str(value)
    if tz.is_naive(moment):
        moment = tz.make_aware(moment)
    return tz.localtime(moment).strftime("%Y-%m-%d %H:%M")


def _export_filename(session, suffix):
    import re as _re
    from django.utils import timezone as tz

    slug = _re.sub(r"[^A-Za-z0-9]+", "-", session.title or "class").strip("-").lower()
    day = tz.localtime(session.created_at).strftime("%Y-%m-%d")
    return f"attendance-{slug or 'class'}-{day}.{suffix}"


class SessionAttendanceExportView(APIView):
    """
    GET /api/v1/attendance/sessions/<sessionId>/export?format=csv|xlsx

    One class's roster as a file a host can keep: who joined, when they joined
    and left, how long they stayed, and how many presence checks they answered.

    Unlike the bulk export in ``export_views`` this accepts a browser JWT as
    well as the service key: a host downloading their own register is the
    point. ``_require_session_access`` still limits it to the host, a co-host,
    a lecturer, an admin or the trusted service.
    """
    authentication_classes = [PodiumAuthentication]

    HEADERS = [
        "Student",
        "Index number",
        "Joined",
        "Left",
        "Minutes in class",
        "Checks passed",
        "Checks sent",
        "Verification %",
        "Present",
    ]

    def get(self, request, session_id):
        caller = check_attendance_api_key(request)

        session = Session.objects.filter(id=session_id).first()
        if session is None:
            raise NotFound({"code": "SESSION_NOT_FOUND", "message": f"Session '{session_id}' not found."})

        # Deliberately narrower than "any lecturer": a register names every
        # student in the class, so it belongs to the people who ran it -- its
        # host, its assigned lecturer, an active co-host, or an admin. The
        # trusted service key (caller is None) is the external system.
        if caller is not None:
            from apps.classrooms.access import is_cohost

            allowed = (
                caller.role == "admin"
                or session.host_id == caller.id
                or session.lecturer_id == caller.id
                or is_cohost(caller, session)
            )
            if not allowed:
                raise PermissionDenied({
                    "code": "FORBIDDEN",
                    "message": "Only this class's host, lecturer or co-host can download its register.",
                })

        fmt = (request.query_params.get("format") or "csv").lower()
        if fmt not in ("csv", "xlsx"):
            raise ValidationError({
                "code": "INVALID_FORMAT",
                "message": "'format' must be \"csv\" or \"xlsx\".",
            })

        records = (
            Attendance.objects.filter(session=session)
            .select_related("session")
            .order_by("student_name", "student_id")
        )
        serialized = AttendanceRecordSerializer(
            records, many=True, context={"checks_ran": counts.checks_ran_for(session)}
        ).data

        rows = [
            [
                row.get("studentName") or "",
                row.get("indexNumber") or "",
                _human_moment(row.get("joinedAt")),
                _human_moment(row.get("leftAt")),
                row.get("stayMinutes") if row.get("stayMinutes") is not None else "",
                row.get("verificationsCompleted") or 0,
                row.get("verificationsSent") or 0,
                row.get("verificationPercentage") or 0,
                "Yes" if row.get("present") else "No",
            ]
            for row in serialized
        ]

        if fmt == "xlsx":
            from .xlsx import build_workbook

            payload = build_workbook(self.HEADERS, rows, sheet_name=session.title or "Attendance")
            response = HttpResponse(
                payload,
                content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            )
            response["Content-Disposition"] = (
                f'attachment; filename="{_export_filename(session, "xlsx")}"'
            )
        else:
            import csv as _csv
            import io as _io

            buffer = _io.StringIO()
            writer = _csv.writer(buffer)
            writer.writerow(self.HEADERS)
            writer.writerows(rows)
            # Excel needs a BOM to read UTF-8 names correctly on Windows.
            response = HttpResponse(
                "\ufeff" + buffer.getvalue(), content_type="text/csv; charset=utf-8"
            )
            response["Content-Disposition"] = (
                f'attachment; filename="{_export_filename(session, "csv")}"'
            )

        response["Cache-Control"] = "no-store"
        return response
