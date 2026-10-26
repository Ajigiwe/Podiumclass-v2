from django.urls import path

from .views import (
    SessionListCreateView,
    SessionDetailView,
    LiveKitTokenView,
    EndSessionView,
    ChatMessageListCreateView,
    SessionPermissionsView,
    SessionPermissionRequestsView,
    SessionPermissionRequestDetailView,
    SessionCoHostsView,
    SessionCoHostDetailView,
)
# Materials are a storage object (see apps/storage), but they are addressed
# under the session that owns them, so their views are routed from here and the
# whole /api/v1/classrooms/ surface stays readable in one file.
from apps.storage.views import (
    SessionMaterialsView,
    SessionMaterialDetailView,
    SessionRecordingsView,
    SessionRecordingDetailView,
    SessionRecordingStopView,
    SessionRecordingFileView,
)

urlpatterns = [
    path('sessions/', SessionListCreateView.as_view(), name='session-list-create'),
    path('sessions/<str:session_id>/', SessionDetailView.as_view(), name='session-detail'),
    path('sessions/<str:session_id>/token/', LiveKitTokenView.as_view(), name='session-token'),
    path('sessions/<str:session_id>/end/', EndSessionView.as_view(), name='session-end'),
    path('sessions/<str:session_id>/messages/', ChatMessageListCreateView.as_view(), name='session-messages'),
    path('sessions/<str:session_id>/permissions/', SessionPermissionsView.as_view(), name='session-permissions'),
    path('sessions/<str:session_id>/permissions/requests/', SessionPermissionRequestsView.as_view(),
         name='session-permission-requests'),
    path('sessions/<str:session_id>/permissions/requests/<str:request_id>/',
         SessionPermissionRequestDetailView.as_view(), name='session-permission-request-detail'),
    path('sessions/<str:session_id>/cohosts/', SessionCoHostsView.as_view(), name='session-cohosts'),
    path('sessions/<str:session_id>/cohosts/<str:user_id>/',
         SessionCoHostDetailView.as_view(), name='session-cohost-detail'),
    path('sessions/<str:session_id>/materials/', SessionMaterialsView.as_view(), name='session-materials'),
    path('sessions/<str:session_id>/materials/<str:material_id>/',
         SessionMaterialDetailView.as_view(), name='session-material-detail'),
    # Recordings, like materials, are storage objects addressed under their
    # session: list/start, poll one, stop one, stream its MP4.
    path('sessions/<str:session_id>/recordings/', SessionRecordingsView.as_view(),
         name='session-recordings'),
    path('sessions/<str:session_id>/recordings/<str:recording_id>/',
         SessionRecordingDetailView.as_view(), name='session-recording-detail'),
    path('sessions/<str:session_id>/recordings/<str:recording_id>/stop/',
         SessionRecordingStopView.as_view(), name='session-recording-stop'),
    path('sessions/<str:session_id>/recordings/<str:recording_id>/file/',
         SessionRecordingFileView.as_view(), name='session-recording-file'),
    path('token/', LiveKitTokenView.as_view(), name='livekit-token'),
]
