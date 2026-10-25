from django.db import models
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
from apps.classrooms.models import Session
from .serializers import AttendanceRecordSerializer, SessionAttendanceSerializer

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

        queryset = Session.objects.filter(is_deleted=False).order_by('-created_at')

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
        _require_lecturer(caller)

        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            raise NotFound({"code": "SESSION_NOT_FOUND", "message": f"Session '{session_id}' not found."})

        records_qs = Attendance.objects.filter(session=session).select_related('session')
        records_serializer = AttendanceRecordSerializer(records_qs, many=True)
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
        require_feature("attendance_verification")
        import uuid
        from django.utils import timezone
        from datetime import timedelta

        auth_user = check_attendance_api_key(request)
        _require_lecturer(auth_user)

        session_id = request.data.get("sessionId")
        # The actor is the authenticated user, not a client-supplied label.
        if auth_user is not None:
            triggered_by = auth_user.id
        else:
            triggered_by = request.data.get("triggeredBy", "host")
        time_limit = int(request.data.get("timeLimitSeconds", 30))

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

        now = timezone.now()
        expires_at = now + timedelta(seconds=time_limit)

        verif_count = Verification.objects.filter(session=session).count() + 1
        verification_id = f"verif_{uuid.uuid4().hex[:16]}"

        verif = Verification.objects.create(
            id=verification_id,
            session=session,
            verification_number=verif_count,
            triggered_by=triggered_by,
            triggered_at=now,
            expires_at=expires_at,
            time_limit_seconds=time_limit,
        )

        # Increment total_verifications_sent for all attendees in this session
        Attendance.objects.filter(session=session).update(
            total_verifications_sent=models.F("total_verifications_sent") + 1
        )
        AttendanceLog.objects.filter(session=session).update(
            total_verifications_sent=models.F("total_verifications_sent") + 1
        )

        return Response({
            "document": {
                "verificationId": verif.id,
                "verificationNumber": verif.verification_number,
                "expiresAt": verif.expires_at,
                "timeLimitSeconds": verif.time_limit_seconds,
            }
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

        # Update student's attendance stats
        att = Attendance.objects.filter(session_id=session_id, student=student).first()
        if att:
            att.total_verifications_completed += 1
            sent = max(att.total_verifications_sent, 1)
            att.verification_percentage = min(100, int((att.total_verifications_completed / sent) * 100))
            att.last_responded_at = now
            att.is_present = True
            att.save()

        # Update log
        log_id = f"{session_id}_{student_id}"
        log = AttendanceLog.objects.filter(id=log_id).first()
        if log:
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

