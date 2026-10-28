from django.urls import path
from .views import (
    AttendanceSessionsAPIView,
    AttendanceSessionDetailAPIView,
    AttendanceJoinView,
    MyAttendanceView,
    VerificationTriggerView,
    VerificationRespondView,
    VerificationActiveView,
    SessionAttendanceExportView,
)
from .export_views import StudentHistoryView, AttendanceExportView

urlpatterns = [
    path('sessions', AttendanceSessionsAPIView.as_view(), name='attendance-sessions'),
    path('sessions/', AttendanceSessionsAPIView.as_view(), name='attendance-sessions-slash'),
    path('sessions/<str:session_id>', AttendanceSessionDetailAPIView.as_view(), name='attendance-session-detail'),
    path('sessions/<str:session_id>/', AttendanceSessionDetailAPIView.as_view(), name='attendance-session-detail-slash'),

    # A student's own records, from their own token (the student-facing views).
    # A host's file download for one class. JWT-capable, unlike the
    # machine-to-machine bulk export below.
    path('sessions/<str:session_id>/export', SessionAttendanceExportView.as_view(),
         name='attendance-session-export'),
    path('sessions/<str:session_id>/export/', SessionAttendanceExportView.as_view(),
         name='attendance-session-export-slash'),

    path('mine', MyAttendanceView.as_view(), name='attendance-mine'),
    path('mine/', MyAttendanceView.as_view(), name='attendance-mine-slash'),

    # Documented for the external Laravel service. Flat {"data": ...} envelope.
    # Service-key only, and for an arbitrary student id.
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

    # Polled by the classroom client: the latest check and the scheduler's
    # next due time. Server-side scheduling is what keeps checks firing when
    # no moderator's browser tab is awake.
    path('verification/active', VerificationActiveView.as_view(), name='attendance-verification-active'),
    path('verification/active/', VerificationActiveView.as_view(), name='attendance-verification-active-slash'),
]
