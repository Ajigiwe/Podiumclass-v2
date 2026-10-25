import uuid
from django.db import models
from django.utils import timezone
from apps.accounts.models import User

class Session(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    title = models.CharField(max_length=255)
    host = models.ForeignKey(User, on_delete=models.RESTRICT, related_name="hosted_sessions")
    lecturer = models.ForeignKey(User, on_delete=models.RESTRICT, related_name="lectured_sessions", null=True, blank=True)
    backup_mod_id = models.CharField(max_length=128, null=True, blank=True)
    meeting_code = models.CharField(max_length=64, unique=True)
    youtube_video_id = models.CharField(max_length=64, null=True, blank=True)
    is_active = models.BooleanField(default=False, db_index=True)
    price = models.IntegerField(default=0)
    currency = models.CharField(max_length=8, default="GHS")
    is_free = models.BooleanField(default=False)
    lecturer_name = models.CharField(max_length=255, null=True, blank=True)
    program = models.CharField(max_length=128, null=True, blank=True)
    course = models.CharField(max_length=128, null=True, blank=True, db_index=True)
    is_deleted = models.BooleanField(default=False)
    scheduled_start_time = models.DateTimeField(null=True, blank=True)
    duration_minutes = models.IntegerField(null=True, blank=True)
    verification_count = models.IntegerField(null=True, blank=True)
    auto_attendance_settings = models.JSONField(null=True, blank=True)
    auto_approve_mic = models.BooleanField(default=False)
    is_muted_all = models.BooleanField(default=False)
    require_guest_details = models.BooleanField(default=False)
    status = models.CharField(max_length=32, default="active", db_index=True) # scheduled, active, ended, deleted
    participant_count = models.IntegerField(default=0)
    host_last_seen = models.DateTimeField(null=True, blank=True)
    mod_last_seen = models.DateTimeField(null=True, blank=True)
    host_absence_minutes = models.IntegerField(null=True, blank=True)
    auto_alert_triggered = models.BooleanField(default=False)
    auto_alert_triggered_at = models.DateTimeField(null=True, blank=True)
    refund_processed = models.BooleanField(default=False)
    scheduled_notified_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(default=timezone.now, db_index=True)
    started_at = models.DateTimeField(null=True, blank=True)
    ended_at = models.DateTimeField(null=True, blank=True)
    group_id = models.CharField(max_length=128, null=True, blank=True)
    metadata = models.JSONField(null=True, blank=True)

    class Meta:
        db_table = "sessions"
        indexes = [
            models.Index(fields=["is_active"]),
            models.Index(fields=["status"]),
            models.Index(fields=["created_at"]),
        ]

    def __str__(self):
        return f"{self.title} ({self.meeting_code})"

class CoHost(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="co_hosts")
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="co_host_assignments")
    user_name = models.CharField(max_length=255)
    assigned_by = models.CharField(max_length=128)
    assigned_at = models.DateTimeField(default=timezone.now)
    is_active = models.BooleanField(default=True)

    class Meta:
        db_table = "co_hosts"
        unique_together = (("session", "user"),)

class Participant(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="participants")
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="session_participations")
    user_name = models.CharField(max_length=255)
    role = models.CharField(max_length=32, default="student")
    joined_at = models.DateTimeField(default=timezone.now)
    left_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "participants"
        unique_together = (("session", "user"),)

class ParticipantPermission(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="permissions")
    participant_id = models.CharField(max_length=128)
    mic_permission = models.BooleanField(default=False)
    camera_permission = models.BooleanField(default=False)
    screen_share_permission = models.BooleanField(default=False)
    granted_by = models.CharField(max_length=128, null=True, blank=True)
    granted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "participant_permissions"
        unique_together = (("session", "participant_id"),)

class PermissionRequest(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="permission_requests")
    participant_id = models.CharField(max_length=128)
    participant_name = models.CharField(max_length=255)
    request_type = models.CharField(max_length=32) # mic, camera, screen
    status = models.CharField(max_length=32, default="pending") # pending, approved, rejected
    requested_at = models.DateTimeField(default=timezone.now)
    reviewed_at = models.DateTimeField(null=True, blank=True)
    reviewed_by = models.CharField(max_length=128, null=True, blank=True)

    class Meta:
        db_table = "permission_requests"

class ChatMessage(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="chat_messages")
    user_id = models.CharField(max_length=128)
    user_name = models.CharField(max_length=255)
    user_role = models.CharField(max_length=32, default="student")
    message = models.TextField()
    timestamp = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "chat_messages"
