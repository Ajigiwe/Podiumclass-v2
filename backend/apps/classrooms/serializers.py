from django.utils import timezone
from rest_framework import serializers
# The presence and attendance-count rules belong to the attendance records;
# the class list only reads them.
from apps.attendance import counts as attendance_counts
from .models import Session, Participant, ChatMessage, PermissionRequest, ParticipantPermission

# Media a host can require approval for, and the two states it can be in.
# 'free'    -- participants use it whenever they like (the historical default).
# 'approval'-- participants must ask; the host's answer is stored per person.
PERMISSION_TYPES = ("mic", "camera", "screen")
PERMISSION_MODES = ("free", "approval")


def read_permissions(session: Session) -> dict:
    """
    The session's permission policy, always complete and always valid.

    Stored in ``Session.metadata`` so an old row (or a hand-edited one) that is
    missing keys or holding junk still reads as 'free' rather than exploding
    into an unknown mode the client cannot render.
    """
    stored = (session.metadata or {}).get("permissions") or {}
    if not isinstance(stored, dict):
        stored = {}
    return {
        key: stored.get(key) if stored.get(key) in PERMISSION_MODES else "free"
        for key in PERMISSION_TYPES
    }


class SessionSerializer(serializers.ModelSerializer):
    participantCount = serializers.IntegerField(source="participant_count", read_only=True)
    # Real attendance for this class. "participantCount" alone was not enough:
    # every client that wanted to show attendance read that one field, and it
    # was only ever written at 0.
    attendeeCount = serializers.SerializerMethodField()
    presentCount = serializers.SerializerMethodField()

    meetingCode = serializers.CharField(source="meeting_code", required=False)
    lecturerName = serializers.CharField(source="lecturer_name", required=False, allow_blank=True)
    scheduledStartTime = serializers.DateTimeField(source="scheduled_start_time", required=False, allow_null=True)
    durationMinutes = serializers.IntegerField(source="duration_minutes", required=False, allow_null=True)
    createdAt = serializers.DateTimeField(source="created_at", read_only=True)
    startedAt = serializers.DateTimeField(source="started_at", read_only=True)
    endedAt = serializers.DateTimeField(source="ended_at", read_only=True)
    # Exposed so clients can tell "hosted by me" from "enrolled in". Without it
    # the dashboard had to guess, which is where the hardcoded "1" came from.
    hostId = serializers.CharField(source="host_id", read_only=True)
    verificationCount = serializers.IntegerField(source="verification_count", required=False, allow_null=True)
    autoAttendanceSettings = serializers.JSONField(source="auto_attendance_settings", required=False, allow_null=True)
    # Classroom moderation state. Exposed on the session so a participant who
    # joins (or refreshes) five minutes into a lecture still knows the room is
    # muted and which media need approval, instead of inheriting a stale view.
    isMutedAll = serializers.BooleanField(source="is_muted_all", read_only=True)
    autoApproveMic = serializers.BooleanField(source="auto_approve_mic", read_only=True)
    permissions = serializers.SerializerMethodField()
    # How long the class actually ran, as opposed to how long it was booked
    # for. See get_actualDurationMinutes.
    actualDurationMinutes = serializers.SerializerMethodField()

    class Meta:
        model = Session
        fields = [
            'id', 'title', 'meetingCode', 'status', 'is_active', 'is_free',
            'price', 'currency', 'lecturerName', 'program', 'course',
            'scheduledStartTime', 'durationMinutes', 'participantCount',
            'createdAt', 'startedAt', 'endedAt', 'hostId',
            'verificationCount', 'autoAttendanceSettings',
            'isMutedAll', 'autoApproveMic', 'permissions',
            'actualDurationMinutes', 'attendeeCount', 'presentCount',
        ]

    def get_attendeeCount(self, session) -> int:
        return attendance_counts.count_attendees(session)

    def get_presentCount(self, session) -> int:
        return attendance_counts.count_present(session)

    def get_permissions(self, session) -> dict:
        return read_permissions(session)

    def get_actualDurationMinutes(self, session):
        """
        Minutes the class actually ran, measured from its real start and end.

        ``durationMinutes`` is the length a host *books* when creating a class,
        so it says nothing about time attended -- a five-minute test class still
        carried the 60-minute default, and every client then reported an hour.
        A still-running class counts up to now; one that never started reports
        nothing rather than inventing a figure.
        """
        if not session.started_at:
            return None
        end = session.ended_at or timezone.now()
        return max(0, int((end - session.started_at).total_seconds() // 60))

class ParticipantSerializer(serializers.ModelSerializer):
    userName = serializers.CharField(source="user_name")
    joinedAt = serializers.DateTimeField(source="joined_at")
    leftAt = serializers.DateTimeField(source="left_at", allow_null=True)

    class Meta:
        model = Participant
        fields = ['id', 'user_id', 'userName', 'role', 'joinedAt', 'leftAt']

class ChatMessageSerializer(serializers.ModelSerializer):
    userId = serializers.CharField(source="user_id")
    userName = serializers.CharField(source="user_name")
    userRole = serializers.CharField(source="user_role")

    class Meta:
        model = ChatMessage
        fields = ['id', 'userId', 'userName', 'userRole', 'message', 'timestamp']


def permission_grant_payload(row, participant_id: str) -> dict:
    """
    One participant's standing permissions.

    A missing row means "no exceptions", which is what a student joining a
    normal, unlocked room should see.
    """
    return {
        "participantId": participant_id,
        "mic": bool(row and row.mic_permission),
        "camera": bool(row and row.camera_permission),
        "screen": bool(row and row.screen_share_permission),
        "grantedBy": row.granted_by if row else None,
        "grantedAt": row.granted_at.isoformat() if row and row.granted_at else None,
    }


def permission_request_payload(row) -> dict:
    return {
        "id": row.id,
        "participantId": row.participant_id,
        "participantName": row.participant_name,
        "requestType": row.request_type,
        "status": row.status,
        "requestedAt": row.requested_at.isoformat(),
        "reviewedAt": row.reviewed_at.isoformat() if row.reviewed_at else None,
        "reviewedBy": row.reviewed_by,
    }
