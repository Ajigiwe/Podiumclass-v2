import uuid
from django.conf import settings
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.views import APIView
from rest_framework.response import Response
from apps.accounts.permissions import IsLecturerOrAdmin, is_lecturer
from apps.accounts.models import User
from apps.core.guards import require_feature
from apps.core.models import SystemSetting
from .models import Session, Participant, ChatMessage
from .serializers import SessionSerializer, ParticipantSerializer, ChatMessageSerializer
from .livekit import generate_livekit_token

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
        
        qs = Session.objects.filter(is_deleted=False)
        if status_filter:
            qs = qs.filter(status=status_filter)
        if course_filter:
            qs = qs.filter(course__icontains=course_filter)
            
        sessions = qs.order_by("-created_at")
        serializer = SessionSerializer(sessions, many=True)
        return Response(serializer.data)

    def post(self, request):
        # Hosting a class is a lecturer/admin action. Without this check any
        # anonymous caller could mint sessions and provision lecturer accounts.
        if not is_lecturer(request.user):
            return Response(
                {"error": {"code": "LECTURER_REQUIRED", "message": "Only lecturers or admins can create sessions."}},
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

        session = Session.objects.create(
            id=session_id,
            title=title,
            host=host_user,
            lecturer=host_user,
            meeting_code=meeting_code,
            is_active=True,
            status="active",
            is_free=data.get("isFree", True),
            price=data.get("price", 0),
            currency=data.get("currency", "GHS"),
            lecturer_name=data.get("lecturerName", host_user.full_name),
            program=data.get("program", "General"),
            course=data.get("course", "POD101"),
            started_at=timezone.now(),
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
    Get or update a specific classroom session.
    GET -> { "document": { "id": "...", "title": "...", ... } }
    PATCH -> { "document": { ... } }
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

        # Ensure only the host or admin/lecturer can alter session settings
        if request.user.role not in ("admin", "lecturer") and session.host_id != request.user.id:
            return Response(
                {"error": {"code": "FORBIDDEN", "message": "Only the host can modify session settings."}},
                status=status.HTTP_403_FORBIDDEN
            )

        data = request.data
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
        session.save()

        serializer = SessionSerializer(session)
        return Response(serializer.data)

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

        session.is_active = False
        session.status = "ended"
        session.ended_at = timezone.now()
        session.save()
        
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

