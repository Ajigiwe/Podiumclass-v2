from django.urls import path
from .views import (
    AttendanceSessionsAPIView,
    AttendanceSessionDetailAPIView,
    AttendanceJoinView,
    VerificationTriggerView,
    VerificationRespondView,
)
from .export_views import StudentHistoryView, AttendanceExportView

urlpatterns = [
    path('sessions', AttendanceSessionsAPIView.as_view(), name='attendance-sessions'),
    path('sessions/', AttendanceSessionsAPIView.as_view(), name='attendance-sessions-slash'),
    path('sessions/<str:session_id>', AttendanceSessionDetailAPIView.as_view(), name='attendance-session-detail'),
    path('sessions/<str:session_id>/', AttendanceSessionDetailAPIView.as_view(), name='attendance-session-detail-slash'),

    # Documented for the external Laravel service. Flat {"data": ...} envelope.
    path('students/<str:student_id>', StudentHistoryView.as_view(), name='attendance-student-history'),
    path('students/<str:student_id>/', StudentHistoryView.as_view(), name='attendance-student-history-slash'),
    path('export', AttendanceExportView.as_view(), name='attendance-export'),
    path('export/', AttendanceExportView.as_view(), name='attendance-export-slash'),

    path('join', AttendanceJoinView.as_view(), name='attendance-join'),
    path('join/', AttendanceJoinView.as_view(), name='attendance-join-slash'),
    path('verification/trigger', VerificationTriggerView.as_view(), name='attendance-verification-trigger'),
    path('verification/trigger/', VerificationTriggerView.as_view(), name='attendance-verification-trigger-slash'),
    path('verification/respond', VerificationRespondView.as_view(), name='attendance-verification-respond'),
    path('verification/respond/', VerificationRespondView.as_view(), name='attendance-verification-respond-slash'),
]
