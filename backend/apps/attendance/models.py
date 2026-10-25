from django.db import models
from django.utils import timezone
from apps.accounts.models import User
from apps.classrooms.models import Session

class Attendance(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="attendance_records")
    student = models.ForeignKey(User, on_delete=models.RESTRICT, related_name="attendance_entries")
    student_name = models.CharField(max_length=255)
    student_index_number = models.CharField(max_length=64, blank=True, null=True)
    joined_at = models.DateTimeField(default=timezone.now)
    left_at = models.DateTimeField(null=True, blank=True)
    last_seen_at = models.DateTimeField(null=True, blank=True)
    last_responded_at = models.DateTimeField(null=True, blank=True)
    total_verifications_sent = models.IntegerField(default=0)
    total_verifications_completed = models.IntegerField(default=0)
    verification_percentage = models.IntegerField(default=0)
    is_present = models.BooleanField(default=True)
    metadata = models.JSONField(null=True, blank=True)

    class Meta:
        db_table = "attendance"
        unique_together = (("session", "student"),)
        indexes = [
            models.Index(fields=["session", "is_present"]),
        ]

    def __str__(self):
        return f"{self.student_name} in {self.session_id}"

class AttendanceLog(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="attendance_logs")
    user = models.ForeignKey(User, on_delete=models.RESTRICT, related_name="attendance_logs")
    user_name = models.CharField(max_length=255)
    user_index_number = models.CharField(max_length=64, blank=True, null=True)
    user_email = models.EmailField(blank=True, null=True)
    role = models.CharField(max_length=32, blank=True, null=True)
    joined_at = models.DateTimeField(default=timezone.now)
    last_responded_at = models.DateTimeField(null=True, blank=True)
    lecturer_id = models.CharField(max_length=128, null=True, blank=True)
    session_title = models.CharField(max_length=255, null=True, blank=True)
    total_verifications_sent = models.IntegerField(default=0)
    total_verifications_completed = models.IntegerField(default=0)
    verification_percentage = models.IntegerField(default=0)
    is_present = models.BooleanField(default=False)
    metadata = models.JSONField(null=True, blank=True)

    class Meta:
        db_table = "attendance_logs"
        indexes = [
            models.Index(fields=["session", "user"]),
            models.Index(fields=["user", "joined_at"]),
        ]

class Verification(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="verifications")
    verification_number = models.IntegerField()
    triggered_by = models.CharField(max_length=128)
    triggered_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField()
    time_limit_seconds = models.IntegerField(default=60)

    class Meta:
        db_table = "verifications"
        indexes = [
            models.Index(fields=["session", "verification_number"]),
            models.Index(fields=["session", "triggered_at"]),
        ]

class VerificationResponse(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    verification = models.ForeignKey(Verification, on_delete=models.CASCADE, related_name="responses")
    student = models.ForeignKey(User, on_delete=models.CASCADE, related_name="verification_responses")
    responded_at = models.DateTimeField(default=timezone.now)
    response_time_seconds = models.IntegerField()

    class Meta:
        db_table = "verification_responses"
        unique_together = (("verification", "student"),)
