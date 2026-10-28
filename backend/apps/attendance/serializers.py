from django.utils import timezone
from rest_framework import serializers
from . import counts
from .models import Attendance, AttendanceLog
from apps.classrooms.models import Session

class AttendanceRecordSerializer(serializers.ModelSerializer):
    studentId = serializers.CharField(source='student_id')
    studentName = serializers.CharField(source='student_name')
    indexNumber = serializers.CharField(source='student_index_number')
    joined = serializers.BooleanField(source='is_present')
    present = serializers.SerializerMethodField()
    stayMinutes = serializers.SerializerMethodField()
    verificationPercentage = serializers.IntegerField(source='verification_percentage')
    verificationsSent = serializers.IntegerField(source='total_verifications_sent')
    verificationsCompleted = serializers.IntegerField(source='total_verifications_completed')
    joinedAt = serializers.DateTimeField(source='joined_at')
    leftAt = serializers.DateTimeField(source='left_at')

    class Meta:
        model = Attendance
        fields = [
            'studentId', 'studentName', 'indexNumber', 'joined', 'present',
            'stayMinutes', 'verificationPercentage', 'verificationsSent',
            'verificationsCompleted', 'joinedAt', 'leftAt'
        ]

    def get_present(self, obj):
        # The rule itself lives in counts.attended_the_class: answering enough
        # checks counts, and so does a class in which no check was ever fired.
        checks_ran = self.context.get("checks_ran")
        if checks_ran is None:
            checks_ran = counts.checks_ran_for_session_id(obj.session_id)
        return counts.attended_the_class(obj.verification_percentage, checks_ran)

    def get_stayMinutes(self, obj):
        if not obj.joined_at:
            return None
        end_time = obj.left_at or (obj.session.ended_at if obj.session else None)
        if not end_time:
            return None
        diff = (end_time - obj.joined_at).total_seconds() / 60.0
        return max(0, int(round(diff)))

class StudentAttendanceSerializer(serializers.ModelSerializer):
    """
    One student's own attendance in one class: what they answered, whether it
    counted, and how long they were there.

    This is the shape the student-facing views read (the mobile Attendance tab
    and the web history). Without it the only attendance endpoints were the
    lecturer-wide roster and export, so a student's client had nothing truthful
    to show and fell back to guessing.
    """
    sessionId = serializers.CharField(source='session_id')
    title = serializers.CharField(source='session.title')
    course = serializers.CharField(source='session.course')
    lecturerName = serializers.CharField(source='session.lecturer_name')
    status = serializers.CharField(source='session.status')
    isDeleted = serializers.BooleanField(source='session.is_deleted')
    startedAt = serializers.DateTimeField(source='session.started_at')
    endedAt = serializers.DateTimeField(source='session.ended_at')
    createdAt = serializers.DateTimeField(source='session.created_at')
    actualDurationMinutes = serializers.SerializerMethodField()
    joinedAt = serializers.DateTimeField(source='joined_at')
    leftAt = serializers.DateTimeField(source='left_at')
    stayMinutes = serializers.SerializerMethodField()
    present = serializers.SerializerMethodField()
    verificationPercentage = serializers.IntegerField(source='verification_percentage')
    verificationsSent = serializers.IntegerField(source='total_verifications_sent')
    verificationsCompleted = serializers.IntegerField(source='total_verifications_completed')

    class Meta:
        model = Attendance
        fields = [
            'id', 'sessionId', 'title', 'course', 'lecturerName', 'status',
            'isDeleted', 'createdAt', 'startedAt', 'endedAt',
            'actualDurationMinutes', 'joinedAt', 'leftAt', 'stayMinutes',
            'present', 'verificationPercentage', 'verificationsSent',
            'verificationsCompleted',
        ]

    def get_present(self, obj):
        return counts.attended_the_class(
            obj.verification_percentage, counts.session_checks_count(obj) > 0
        )

    def get_actualDurationMinutes(self, obj):
        session = obj.session
        if not session.started_at:
            return None
        end = session.ended_at or timezone.now()
        return max(0, int((end - session.started_at).total_seconds() // 60))

    def get_stayMinutes(self, obj):
        """Minutes this student was in the room, bounded by the class itself."""
        if not obj.joined_at:
            return None
        end = obj.left_at or obj.session.ended_at or timezone.now()
        return max(0, int(round((end - obj.joined_at).total_seconds() / 60.0)))


class SessionAttendanceSerializer(serializers.ModelSerializer):
    sessionId = serializers.CharField(source='id')
    title = serializers.CharField()
    course = serializers.CharField()
    program = serializers.CharField()
    lecturerId = serializers.CharField(source='lecturer_id')
    lecturerName = serializers.CharField(source='lecturer_name')
    status = serializers.CharField()
    isActive = serializers.BooleanField(source='is_active')
    createdAt = serializers.DateTimeField(source='created_at')
    startedAt = serializers.DateTimeField(source='started_at')
    endedAt = serializers.DateTimeField(source='ended_at')
    durationMinutes = serializers.IntegerField(source='duration_minutes')
    participantCount = serializers.IntegerField(source='participant_count')
    # Real attendance, counted from the records rather than from the session's
    # own counter, which nothing ever incremented (so every client showed 0).
    attendeeCount = serializers.SerializerMethodField()
    presentCount = serializers.SerializerMethodField()

    class Meta:
        model = Session
        fields = [
            'sessionId', 'title', 'course', 'program', 'lecturerId',
            'lecturerName', 'status', 'isActive', 'createdAt', 'startedAt',
            'endedAt', 'durationMinutes', 'participantCount',
            'attendeeCount', 'presentCount',
        ]

    def get_attendeeCount(self, obj):
        return counts.count_attendees(obj)

    def get_presentCount(self, obj):
        return counts.count_present(obj)
