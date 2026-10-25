from django.db import models
from django.utils import timezone
from apps.classrooms.models import Session

class Recording(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="recordings")
    egress_id = models.CharField(max_length=128, unique=True, null=True, blank=True)
    room_id = models.CharField(max_length=128)
    lecturer_id = models.CharField(max_length=128)
    class_title = models.CharField(max_length=255)
    status = models.CharField(max_length=32, default="active")
    file_path = models.CharField(max_length=1024, null=True, blank=True)
    file_url = models.URLField(max_length=1024, null=True, blank=True)
    size_bytes = models.BigIntegerField(null=True, blank=True)
    duration_seconds = models.IntegerField(null=True, blank=True)
    started_at = models.DateTimeField(null=True, blank=True)
    ended_at = models.DateTimeField(null=True, blank=True)
    metadata = models.JSONField(null=True, blank=True)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "recordings"

class SessionMaterial(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    session = models.ForeignKey(Session, on_delete=models.CASCADE, related_name="materials")
    title = models.CharField(max_length=255)
    file_url = models.URLField(max_length=1024)
    file_type = models.CharField(max_length=64, blank=True, null=True)
    file_size_bytes = models.BigIntegerField(null=True, blank=True)
    uploaded_by = models.CharField(max_length=128)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "session_materials"
