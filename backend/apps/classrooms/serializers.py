from rest_framework import serializers
from .models import Session, Participant, ChatMessage, PermissionRequest

class SessionSerializer(serializers.ModelSerializer):
    participantCount = serializers.IntegerField(source="participant_count", read_only=True)
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

    class Meta:
        model = Session
        fields = [
            'id', 'title', 'meetingCode', 'status', 'is_active', 'is_free',
            'price', 'currency', 'lecturerName', 'program', 'course',
            'scheduledStartTime', 'durationMinutes', 'participantCount',
            'createdAt', 'startedAt', 'endedAt', 'hostId',
            'verificationCount', 'autoAttendanceSettings'
        ]

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
