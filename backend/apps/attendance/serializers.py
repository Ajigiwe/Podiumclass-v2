from rest_framework import serializers
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
        # Present threshold: verificationPercentage >= 50%
        return obj.verification_percentage >= 50

    def get_stayMinutes(self, obj):
        if not obj.joined_at:
            return None
        end_time = obj.left_at or (obj.session.ended_at if obj.session else None)
        if not end_time:
            return None
        diff = (end_time - obj.joined_at).total_seconds() / 60.0
        return max(0, int(round(diff)))

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

    class Meta:
        model = Session
        fields = [
            'sessionId', 'title', 'course', 'program', 'lecturerId',
            'lecturerName', 'status', 'isActive', 'createdAt', 'startedAt',
            'endedAt', 'durationMinutes', 'participantCount'
        ]
