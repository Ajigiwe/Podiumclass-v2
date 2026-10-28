import uuid
from django.conf import settings
from django.db.models import Q, Subquery
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.views import APIView
from rest_framework.response import Response
from apps.accounts.permissions import IsLecturerOrAdmin, is_lecturer
from apps.accounts.models import User
from apps.core.guards import require_feature
from apps.core.models import SystemSetting
from .access import can_moderate, is_cohost, is_session_authority
from .models import (
    Session,
    Participant,
    ChatMessage,
    CoHost,
    PermissionRequest,
    ParticipantPermission,
)
# The presence rule and the attendance rows both belong to apps.attendance; the
# class lists only read them, so they stay defined there.
from apps.attendance import counts as attendance_counts
from apps.attendance.models import Attendance
from .serializers import (
    PERMISSION_MODES,
    PERMISSION_TYPES,
    SessionSerializer,
    ParticipantSerializer,
    ChatMessageSerializer,
    permission_grant_payload,
    permission_request_payload,
    read_permissions,
)
from .livekit import delete_livekit_room, generate_livekit_token


def _session_or_404(session_id):
    try:
        return Session.objects.get(id=session_id, is_deleted=False), None
    except Session.DoesNotExist:
        return None, Response(
            {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
            status=status.HTTP_404_NOT_FOUND,
        )


# The access rules live in .access so apps.storage and apps.attendance ask the
# same question and get the same answer; these are just the local spellings.
_is_session_authority = is_session_authority
_is_cohost = is_cohost
_can_moderate = can_moderate


def _bad(message):
    return Response(
        {"error": {"code": "VALIDATION_ERROR", "message": message}},
        status=status.HTTP_400_BAD_REQUEST,
    )


def _forbidden(message):
    return Response(
        {"error": {"code": "FORBIDDEN", "message": message}},
        status=status.HTTP_403_FORBIDDEN,
    )


def _as_bool(value, field):
    if not isinstance(value, bool):
        raise ValueError(f"{field} must be true or false.")
    return value


def _as_permissions(value) -> dict:
    """
    Validate a partial permission policy.

    Only the keys present are returned, so a PATCH that locks the microphone
    leaves the camera and screen policy untouched.
    """
    if not isinstance(value, dict):
        raise ValueError('permissions must be an object such as {"mic": "approval"}.')
    out = {}
    for key in PERMISSION_TYPES:
        if key not in value:
            continue
        if value[key] not in PERMISSION_MODES:
            raise ValueError(f"permissions.{key} must be one of: {', '.join(PERMISSION_MODES)}.")
        out[key] = value[key]
    return out


def _grant_permission(session, participant_id, request_type, granted_by):
    """
    Upsert "this participant may use <medium>".

    Used both by an explicit host grant and by Smart Mic auto-approval, so the
    two paths can never diverge in what they write.
    """
    row, _ = ParticipantPermission.objects.get_or_create(
        session=session,
        participant_id=participant_id,
        defaults={"id": f"prm_{uuid.uuid4().hex[:16]}"},
    )
    setattr(row, {
        "mic": "mic_permission",
        "camera": "camera_permission",
        "screen": "screen_share_permission",
    }[request_type], True)
    row.granted_by = granted_by
    row.granted_at = timezone.now()
    row.save()
    return row

class SessionListCreateView(APIView):
    """
    List active or scheduled classroom sessions, or create a new session.
    Always responds with the Document Object format:
    GET -> { "documents": [ ... ] }
    POST -> { "document": { ... } }
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        status_filter = request.query_params.get("status")
        course_filter = request.query_params.get("course")
        code_filter = (request.query_params.get("meetingCode") or "").strip()

        # Deleting a class hides it from the live lists; it does not erase what
        # happened in it. Callers that show history ask for the deleted rows
        # explicitly, so a removed class still reports who attended it instead
        # of taking its whole attendance record out of sight with it.
        include_deleted = request.query_params.get("includeDeleted", "").lower() in (
            "1", "true", "yes",
        )

        qs = Session.objects.all() if include_deleted else Session.objects.filter(is_deleted=False)
        if status_filter:
            qs = qs.filter(status=status_filter)
        if course_filter:
            qs = qs.filter(course__icontains=course_filter)

        # Join by code or invite link. A student is meant to reach a class they
        # were never enrolled in by typing its code, so this one lookup answers
        # for exactly the class asked about -- it does not hand back the rest of
        # the timetable along with it. An unknown code answers with an empty
        # list, which is what the "no class with that code" state reads.
        if code_filter:
            session = qs.filter(meeting_code__iexact=code_filter).first()
            if session is None:
                return Response([])
            # A list, like the unfiltered call: every client reads this endpoint
            # as a collection and takes the first match, so answering with a
            # single object here would leave them all with "nothing found".
            return Response([SessionSerializer(session).data])

        # Scope the list to what the caller is actually part of. Staff see the
        # institution's timetable; everyone else -- including an account an
        # admin granted class creation to -- sees only the classes they host,
        # teach, co-host or have joined. The grant adds the ability to host, not
        # the right to browse everybody else's classes.
        if request.user.role not in ("lecturer", "admin"):
            joined = Attendance.objects.filter(student=request.user).values("session_id")
            cohosted = CoHost.objects.filter(user=request.user, is_active=True).values("session_id")
            qs = qs.filter(
                Q(host=request.user)
                | Q(lecturer=request.user)
                | Q(id__in=Subquery(joined))
                | Q(id__in=Subquery(cohosted))
            )

        # Counted in the database rather than per row, and counted from the
        # attendance records themselves: the stored participant_count was only
        # ever 0, so every "verified" figure built on it read zero.
        sessions = qs.annotate(**attendance_counts.session_count_annotations()).order_by(
            "-created_at"
        )
        serializer = SessionSerializer(sessions, many=True)
        return Response(serializer.data)

    def post(self, request):
        # Hosting a class is a lecturer/admin action. The creator becomes the
        # session's host and lecturer, so without this check any signed-in
        # student could mint a session and hand themselves host privileges over
        # it. Anonymous callers are already stopped by IsAuthenticated above.
        if not is_lecturer(request.user):
            return Response(
                {
                    "error": {
                        "code": "LECTURER_REQUIRED",
                        "message": "Only hosts can create sessions: lecturers, admins, "
                        "or accounts an admin has granted class creation to.",
                    }
                },
                status=status.HTTP_403_FORBIDDEN
            )

        data = request.data
        title = data.get("title")
        if not title:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "Session title is required."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        meeting_code = data.get("meetingCode") or f"POD-{uuid.uuid4().hex[:6].upper()}"
        session_id = data.get("id") or str(uuid.uuid4())
        
        # The host is always the authenticated user. We no longer read
        # hostEmail from the request or auto-provision a lecturer account,
        # which previously let any caller create staff identities.
        host_user = request.user

        # A new class is *prepared*, not *running*.
        #
        # It used to be created active and started, which meant the door was
        # open before anyone was inside: a student could walk into an empty
        # room, and the class's clock had already been counting since the host
        # filled in a form. Creating now only puts the class on the host's
        # dashboard with its code, ready to share; the host arriving in it is
        # what starts it (see LiveKitTokenView).
        session = Session.objects.create(
            id=session_id,
            title=title,
            host=host_user,
            lecturer=host_user,
            meeting_code=meeting_code,
            is_active=False,
            status="scheduled",
            is_free=data.get("isFree", True),
            price=data.get("price", 0),
            currency=data.get("currency", "GHS"),
            lecturer_name=data.get("lecturerName", host_user.full_name),
            program=data.get("program", "General"),
            course=data.get("course", "POD101"),
            started_at=None,
            verification_count=data.get("verificationCount", data.get("verification_count", 3)),
            auto_attendance_settings=data.get("autoAttendanceSettings", data.get("auto_attendance_settings", {
                "enabled": data.get("autoAttendanceEnabled", True),
                "checkCount": data.get("verificationCount", 3),
                "timeLimitSeconds": data.get("timeLimitSeconds", 30),
                "intervalMinutes": data.get("intervalMinutes", 15),
            })),
        )

        serializer = SessionSerializer(session)
        return Response(serializer.data, status=status.HTTP_201_CREATED)

class SessionDetailView(APIView):
    """
    Get, update or remove a specific classroom session.
    GET -> { "document": { "id": "...", "title": "...", ... } }
    PATCH -> { "document": { ... } }
    DELETE -> { "document": { "deleted": true } }
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND
            )
        serializer = SessionSerializer(session)
        return Response(serializer.data)

    def patch(self, request, session_id):
        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND
            )

        # Host, lecturer and admin own the session's settings. A co-host may
        # still PATCH this endpoint, but only for the moderation keys -- see
        # the restriction below.
        is_authority = _is_session_authority(request.user, session)
        if not is_authority and not _can_moderate(request.user, session):
            return Response(
                {"error": {"code": "FORBIDDEN", "message": "Only the host can modify session settings."}},
                status=status.HTTP_403_FORBIDDEN
            )

        data = request.data
        if not is_authority:
            # Full moderation parity, no session-level authority: a co-host may
            # lock and unlock the room but not rename it, end it or touch the
            # attendance configuration.
            stray = [
                key for key in data
                if key not in ("isMutedAll", "autoApproveMic", "permissions")
            ]
            if stray:
                return Response(
                    {
                        "error": {
                            "code": "FORBIDDEN",
                            "message": "Co-hosts can only change moderation settings.",
                            "details": f"Allowed: isMutedAll, autoApproveMic, permissions. Got: {', '.join(map(str, stray))}.",
                        }
                    },
                    status=status.HTTP_403_FORBIDDEN
                )
        try:
            if "verificationCount" in data:
                session.verification_count = int(data["verificationCount"])
            if "autoAttendanceSettings" in data:
                session.auto_attendance_settings = data["autoAttendanceSettings"]
            if "title" in data:
                session.title = data["title"]
            if "status" in data:
                session.status = data["status"]
            if "is_active" in data:
                session.is_active = data["is_active"]

            # --- classroom moderation -------------------------------------
            # Mute All and Smart Mic are real, durable room settings: a host
            # who refreshes mid-lecture must not silently unlock the room, and
            # a student joining late must see it as it actually is.
            if "isMutedAll" in data:
                muted = _as_bool(data["isMutedAll"], "isMutedAll")
                if muted and not session.is_muted_all:
                    # Locking the room also revokes every standing "may use the
                    # mic" exception, so earlier approvals cannot outlive it.
                    ParticipantPermission.objects.filter(
                        session=session, mic_permission=True
                    ).update(mic_permission=False, granted_by=None, granted_at=None)
                session.is_muted_all = muted
            if "autoApproveMic" in data:
                session.auto_approve_mic = _as_bool(data["autoApproveMic"], "autoApproveMic")
            if "permissions" in data:
                session.metadata = {
                    **(session.metadata or {}),
                    "permissions": {
                        **read_permissions(session),
                        **_as_permissions(data["permissions"]),
                    },
                }
        except (TypeError, ValueError) as exc:
            return _bad(str(exc))

        session.save()

        serializer = SessionSerializer(session)
        return Response(serializer.data)

    def delete(self, request, session_id):
        """
        Remove a session from every list.

        This is a soft delete: the row is kept with is_deleted=True so the
        attendance history and recordings that point at it stay attributable,
        but every list query (which already filters is_deleted=False) stops
        returning it. Same rule as ending it -- host, lecturer or admin only;
        a co-host may run the room but cannot delete the class.
        """
        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND
            )

        if not _is_session_authority(request.user, session):
            return Response(
                {"error": {"code": "FORBIDDEN", "message": "Only the session host can delete this session."}},
                status=status.HTTP_403_FORBIDDEN
            )

        session.is_deleted = True
        # The model already documents "deleted" as one of a session's statuses;
        # using it keeps a removed class distinguishable from a finished one
        # for anything reading history rather than the live list.
        session.status = "deleted"
        session.save()
        return Response({"deleted": True})

class LiveKitTokenView(APIView):
    """
    Generates a LiveKit JWT token for joining a classroom.

    The participant's role is derived from the authenticated user's database
    role, NOT from the request body. Trusting a client-declared role would let
    any student request `role: "lecturer"` and obtain host privileges such as
    screen share and mute-all.

    Request body:
      {
        "roomName": "session_id_or_meeting_code",
        "participantName": "Kofi Mensah"   # optional, defaults to the user's name
      }
    Returns:
      {
        "document": {
          "token": "...",
          "roomName": "...",
          "identity": "..."
        }
      }
    """
    permission_classes = [IsAuthenticated]

    def post(self, request, session_id=None):
        require_feature("classroom_live")
        data = request.data
        room_name = data.get("roomName") or session_id
        participant_name = data.get("participantName") or request.user.full_name or "Guest"
        # Server-authoritative identity and role.
        participant_id = request.user.id
        role = request.user.role if request.user.role in ("student", "lecturer", "admin") else "student"

        if not room_name:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "roomName or session_id is required."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        # The class's own state decides who may walk in.
        #
        # Two rules, one place, because this is the only call that opens a room:
        #
        #  * a class the host ended is closed until the host comes back, and
        #  * a class that has not started yet is closed until the host arrives
        #    for the first time -- a student who follows the invite link early
        #    belongs in a waiting room, not in an empty room.
        #
        # Both are answered for the host by the host's own token request: it is
        # the single, un-forgeable signal that they are actually in the class,
        # and it already happens once per join, so starting costs no extra call.
        if session_id:
            session, error = _session_or_404(session_id)
            if error:
                return error

            authority = _is_session_authority(request.user, session)
            ended = session.status == "ended"

            if ended and not authority:
                # Worded around what the student has to wait for: a class that
                # has ended is not joinable again until its host opens it, and
                # "reopens when the host returns" left that implicit.
                return Response(
                    {
                        "error": {
                            "code": "SESSION_ENDED",
                            "message": (
                                "This class has ended and needs its host to start it again. "
                                "Wait for the host to rejoin, then try again."
                            ),
                        }
                    },
                    status=status.HTTP_403_FORBIDDEN,
                )

            if not session.is_active and not authority:
                return Response(
                    {
                        "error": {
                            "code": "SESSION_NOT_STARTED",
                            "message": (
                                "This class has not started yet. It opens as soon as its host "
                                "joins -- stay here and you will be let in."
                            ),
                        }
                    },
                    status=status.HTTP_403_FORBIDDEN,
                )

            if authority:
                # The host's arrival is what opens the class. Rejoining after an
                # end reopens it for everybody; joining a prepared class starts
                # its clock. Either way the *original* start is kept: how long
                # the class ran is history, and resetting it here would erase the
                # first sitting from the reported time spent.
                fields = ["is_active", "status", "ended_at", "host_last_seen"]
                session.is_active = True
                session.status = "active"
                session.ended_at = None
                session.host_last_seen = timezone.now()
                if session.started_at is None:
                    session.started_at = timezone.now()
                    fields.append("started_at")
                session.save(update_fields=fields)

        token, identity = generate_livekit_token(
            room_name=room_name,
            participant_name=participant_name,
            participant_id=participant_id,
            role=role,
            user_id=participant_id,
            photo_url=data.get("photoURL") or request.user.photo_url,
        )

        return Response({
            "token": token,
            "roomName": room_name,
            "identity": identity,
            "livekitUrl": SystemSetting.get_setting("livekit_url", getattr(settings, "LIVEKIT_URL", "ws://localhost:7880")),
        })

#: The board a class can hold. The client caps itself at 1200 strokes as well;
#: these are the server's own limits so a hand-written request cannot park a
#: megabyte of junk in a JSON column (or make every rejoin parse it).
WHITEBOARD_MAX_STROKES = 1200
WHITEBOARD_MAX_POINTS = 20000


def _whiteboard_payload(session):
    board = session.whiteboard or {}
    return {
        "open": bool(board.get("open")),
        "strokes": board.get("strokes") or [],
        "updatedAt": board.get("updatedAt"),
    }


def _read_whiteboard(data) -> dict:
    """
    Validate one board write and return what will be stored.

    Strokes are normalised to the same shape the client draws from (flat pairs
    of 0..1 coordinates), so a stored board can never make a viewer's canvas
    throw or draw outside itself.
    """
    if not isinstance(data, dict):
        raise ValueError('The whiteboard must be an object with "open" and "strokes".')

    is_open = data.get("open", False)
    if not isinstance(is_open, bool):
        raise ValueError('"open" must be true or false.')

    raw = data.get("strokes") or []
    if not isinstance(raw, list):
        raise ValueError('"strokes" must be a list.')
    if len(raw) > WHITEBOARD_MAX_STROKES:
        raise ValueError(f"A board holds at most {WHITEBOARD_MAX_STROKES} strokes.")

    strokes = []
    for index, stroke in enumerate(raw):
        if not isinstance(stroke, dict):
            raise ValueError(f"Stroke {index} is not an object.")
        color = stroke.get("color")
        width = stroke.get("width")
        points = stroke.get("points")
        if not isinstance(color, str) or not color or len(color) > 32:
            raise ValueError(f"Stroke {index} has an invalid colour.")
        if isinstance(width, bool) or not isinstance(width, (int, float)):
            raise ValueError(f"Stroke {index} has an invalid width.")
        if not 0 < float(width) <= 0.1:
            raise ValueError(f"Stroke {index} has an invalid width.")
        if not isinstance(points, list) or len(points) < 2 or len(points) % 2:
            raise ValueError(f"Stroke {index} needs an even, non-empty list of points.")
        if len(points) > WHITEBOARD_MAX_POINTS:
            raise ValueError(f"Stroke {index} has too many points.")
        coords = []
        for value in points:
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError(f"Stroke {index} has a non-numeric point.")
            if not -0.001 <= float(value) <= 1.001:
                raise ValueError(f"Stroke {index} has a point outside the board.")
            coords.append(float(value))
        strokes.append({"color": color, "width": float(width), "points": coords})

    return {
        "open": is_open,
        "strokes": strokes,
        "updatedAt": timezone.now().isoformat(),
    }


class SessionWhiteboardView(APIView):
    """
    The host's board, kept on the session so it outlives one tab.

    GET -> { "document": { "open", "strokes", "updatedAt" } }
    PUT -> same shape, replacing the stored board.

    Reading and writing are host-side. Students watch the live board through
    the room's data channel (a snapshot when they join, a packet per stroke
    after that), so they never need this endpoint; and a durable copy must rest
    on the server's idea of who runs the class, because anyone in the room can
    publish on that channel by hand.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _can_moderate(request.user, session):
            return _forbidden("Only the host can read this session's whiteboard.")
        return Response({"document": _whiteboard_payload(session)})

    def put(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _can_moderate(request.user, session):
            return _forbidden("Only the host can save this session's whiteboard.")
        try:
            board = _read_whiteboard(request.data)
        except ValueError as exc:
            return _bad(str(exc))

        session.whiteboard = board
        session.save(update_fields=["whiteboard"])
        return Response({"document": _whiteboard_payload(session)})


class EndSessionView(APIView):
    """
    End an active classroom session.
    """
    permission_classes = [IsAuthenticated, IsLecturerOrAdmin]

    def post(self, request, session_id):
        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND
            )

        # Only the session's own host (or an admin) may end it.
        if request.user.role != "admin" and session.host_id != request.user.id and session.lecturer_id != request.user.id:
            return Response(
                {"error": {"code": "FORBIDDEN", "message": "Only the session host can end this session."}},
                status=status.HTTP_403_FORBIDDEN
            )

        # A recording still writing when the class ends is finalised first:
        # deleting the room out from under the worker is how a take ends up
        # truncated. This is the "auto save" path -- the host closing the
        # class must produce a watchable file, not a half-written one.
        from apps.storage.lifecycle import finalise_session_recordings
        finalise_session_recordings(session)

        session.is_active = False
        session.status = "ended"
        session.ended_at = timezone.now()
        # The board belongs to the sitting that just finished; the next one
        # starts on a blank canvas rather than last week's drawing.
        session.whiteboard = None
        session.save()

        # Actually clear the room. Marking the session ended only changed our
        # own record: every participant still held a live WebRTC session, so
        # the class carried on without its host. Deleting the room disconnects
        # them all, and (with the join check in LiveKitTokenView) stops them
        # walking back in until the host reopens the class.
        delete_livekit_room(session.id)
        if session.meeting_code and session.meeting_code != session.id:
            delete_livekit_room(session.meeting_code)

        serializer = SessionSerializer(session)
        return Response(serializer.data)

class ChatMessageListCreateView(APIView):
    """
    List and create persistent chat messages for a classroom session.
    Guarantees that chat history persists across refreshes and reconnects.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        messages = ChatMessage.objects.filter(session_id=session_id).order_by("timestamp")
        serializer = ChatMessageSerializer(messages, many=True)
        return Response(serializer.data)

    def post(self, request, session_id):
        try:
            session = Session.objects.get(id=session_id, is_deleted=False)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND
            )

        data = request.data
        message_text = (data.get("message") or "").strip()
        if not message_text:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "Message content cannot be empty."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        msg_id = data.get("id") or f"msg_{uuid.uuid4().hex}"
        sender_name = data.get("sender") or request.user.full_name or "Student"
        sender_role = request.user.role if request.user.role in ("student", "lecturer", "admin") else "student"

        chat_msg = ChatMessage.objects.create(
            id=msg_id,
            session=session,
            user_id=request.user.id,
            user_name=sender_name,
            user_role=sender_role,
            message=message_text,
            timestamp=timezone.now(),
        )
        serializer = ChatMessageSerializer(chat_msg)
        return Response(serializer.data, status=status.HTTP_201_CREATED)


class SessionPermissionsView(APIView):
    """
    Standing permissions for the media a host can lock down.

    GET  -> the caller's own grant (what this participant may use right now):
            { "document": { "participantId", "mic", "camera", "screen", ... } }
    PATCH-> host, lecturer or admin sets a grant for one participant:
            { "participantId": "usr_...", "mic": true }
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        row = ParticipantPermission.objects.filter(
            session=session, participant_id=request.user.id
        ).first()
        payload = permission_grant_payload(row, request.user.id)
        # "Am I one of the people running this room?" is answered here, from
        # the server, at join -- the client must never be able to grant itself
        # moderator powers, and this is the same call it already makes for its
        # media grants.
        payload["cohost"] = _is_cohost(request.user, session)
        return Response(payload)

    def patch(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _can_moderate(request.user, session):
            return _forbidden("Only the host can change participant permissions.")

        participant_id = str(request.data.get("participantId") or "").strip()
        if not participant_id:
            return _bad("participantId is required.")

        fields = {
            "mic": "mic_permission",
            "camera": "camera_permission",
            "screen": "screen_share_permission",
        }
        supplied = [key for key in fields if key in request.data]
        if not supplied:
            return _bad("Provide at least one of: mic, camera, screen.")
        try:
            values = {key: _as_bool(request.data[key], key) for key in supplied}
        except ValueError as exc:
            return _bad(str(exc))

        row, created = ParticipantPermission.objects.get_or_create(
            session=session,
            participant_id=participant_id,
            defaults={"id": f"prm_{uuid.uuid4().hex[:16]}"},
        )
        for key, field in fields.items():
            if key in values:
                setattr(row, field, values[key])
        if any(values.values()):
            row.granted_by = request.user.id
            row.granted_at = timezone.now()
        else:
            row.granted_by = None
            row.granted_at = None
        row.save()
        return Response(permission_grant_payload(row, participant_id))


class SessionPermissionRequestsView(APIView):
    """
    The permission-request inbox.

    GET  -> pending requests (host, lecturer or admin only):
            { "documents": [ { id, participantId, participantName, requestType, status, ... } ] }
    POST -> a participant asks for one medium: { "requestType": "mic" }

    Smart Mic (``Session.auto_approve_mic``) answers microphone asks on the
    server, so a raised hand turns into a live microphone even when the host is
    mid-sentence -- and the answer is identical whoever is holding the phone.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _can_moderate(request.user, session):
            return _forbidden("Only the host can read permission requests.")
        rows = PermissionRequest.objects.filter(
            session=session, status="pending"
        ).order_by("requested_at")
        return Response([permission_request_payload(row) for row in rows])

    def post(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error

        request_type = request.data.get("requestType")
        if request_type not in PERMISSION_TYPES:
            return _bad(f"requestType must be one of: {', '.join(PERMISSION_TYPES)}.")

        # Idempotent: a double-tap, or a retry after a flaky connection, must
        # not stack duplicate rows into the host's inbox.
        pending = PermissionRequest.objects.filter(
            session=session,
            participant_id=request.user.id,
            request_type=request_type,
            status="pending",
        ).first()
        if pending:
            return Response(permission_request_payload(pending))

        auto_approve = bool(session.auto_approve_mic) and request_type == "mic"
        row = PermissionRequest.objects.create(
            id=f"prq_{uuid.uuid4().hex[:16]}",
            session=session,
            participant_id=request.user.id,
            participant_name=request.user.full_name or request.user.email,
            request_type=request_type,
            status="approved" if auto_approve else "pending",
            reviewed_at=timezone.now() if auto_approve else None,
            reviewed_by="smart-mic" if auto_approve else None,
        )
        if auto_approve:
            _grant_permission(session, request.user.id, request_type, granted_by="smart-mic")
        return Response(permission_request_payload(row), status=status.HTTP_201_CREATED)


class SessionPermissionRequestDetailView(APIView):
    """
    PATCH -> { "status": "approved" | "rejected" }  (host, lecturer or admin)

    Approving writes the standing permission, so the participant keeps that
    medium across refreshes instead of having to ask again on every reload.
    """
    permission_classes = [IsAuthenticated]

    def patch(self, request, session_id, request_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _can_moderate(request.user, session):
            return _forbidden("Only the host can review permission requests.")

        try:
            row = PermissionRequest.objects.get(id=request_id, session=session)
        except PermissionRequest.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": "Permission request not found."}},
                status=status.HTTP_404_NOT_FOUND,
            )

        if row.status != "pending":
            return _bad(f"This request was already {row.status}.")
        if row.request_type not in PERMISSION_TYPES:
            return _bad(f"Unknown request type '{row.request_type}'.")

        new_status = request.data.get("status")
        if new_status not in ("approved", "rejected"):
            return _bad("status must be 'approved' or 'rejected'.")

        row.status = new_status
        row.reviewed_at = timezone.now()
        row.reviewed_by = request.user.id
        row.save()
        if new_status == "approved":
            _grant_permission(
                session, row.participant_id, row.request_type, granted_by=request.user.id
            )
        return Response(permission_request_payload(row))


def _cohost_payload(row) -> dict:
    return {
        "id": row.id,
        "userId": row.user_id,
        "userName": row.user_name,
        "assignedBy": row.assigned_by,
        "assignedAt": row.assigned_at.isoformat() if row.assigned_at else None,
    }


class SessionCoHostsView(APIView):
    """
    Co-hosts -- the people a host has handed the room's controls to.

    GET  -> anyone signed in may read the list: it drives the CO-HOST badge on
            everyone's roster, and the promoted participant learns their own
            status through ``GET .../permissions/`` instead.
    POST -> { "userId": "usr_..." }  appoint. Host, lecturer or admin only: a
            co-host cannot hand the role to somebody else, so the chain of
            trust stays with whoever owns the session.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        rows = session.co_hosts.filter(is_active=True).order_by("-assigned_at")
        return Response({"documents": [_cohost_payload(row) for row in rows]})

    def post(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _is_session_authority(request.user, session):
            return _forbidden("Only the host can appoint co-hosts.")

        user_id = str(request.data.get("userId") or "").strip()
        if not user_id:
            return _bad("userId is required.")
        if user_id == session.host_id:
            return _bad("The host is already running the room.")

        try:
            target = User.objects.get(id=user_id)
        except User.DoesNotExist:
            return _bad("That user does not exist.")

        row, created = CoHost.objects.get_or_create(
            session=session,
            user=target,
            defaults={
                "id": f"coh_{uuid.uuid4().hex[:16]}",
                "user_name": target.full_name or target.email,
                "assigned_by": request.user.id,
            },
        )
        if not created and not row.is_active:
            # Re-appointed after a previous removal: revive the same row so the
            # unique constraint holds and history stays in one place.
            row.is_active = True
            row.assigned_by = request.user.id
            row.assigned_at = timezone.now()
            row.user_name = target.full_name or target.email
            row.save()

        # backup_mod_id is the legacy single-slot field; keep it pointing at
        # the newest co-host rather than leave it a lie.
        session.backup_mod_id = row.user_id
        session.save()
        return Response(
            _cohost_payload(row),
            status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )


class SessionCoHostDetailView(APIView):
    """DELETE -> withdraw a co-host. Host, lecturer or admin only."""
    permission_classes = [IsAuthenticated]

    def delete(self, request, session_id, user_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        if not _is_session_authority(request.user, session):
            return _forbidden("Only the host can remove co-hosts.")

        row = CoHost.objects.filter(
            session=session, user_id=user_id, is_active=True
        ).first()
        if not row:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": "That user is not a co-host."}},
                status=status.HTTP_404_NOT_FOUND,
            )

        row.is_active = False
        row.save()
        if session.backup_mod_id == user_id:
            latest = (
                session.co_hosts.filter(is_active=True).order_by("-assigned_at").first()
            )
            session.backup_mod_id = latest.user_id if latest else None
            session.save()
        return Response(_cohost_payload(row))

