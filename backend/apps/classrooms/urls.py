from django.urls import path

from .views import (
    SessionListCreateView,
    SessionDetailView,
    LiveKitTokenView,
    EndSessionView,
    ChatMessageListCreateView,
)
# Materials are a storage object (see apps/storage), but they are addressed
# under the session that owns them, so their views are routed from here and the
# whole /api/v1/classrooms/ surface stays readable in one file.
from apps.storage.views import SessionMaterialsView, SessionMaterialDetailView

urlpatterns = [
    path('sessions/', SessionListCreateView.as_view(), name='session-list-create'),
    path('sessions/<str:session_id>/', SessionDetailView.as_view(), name='session-detail'),
    path('sessions/<str:session_id>/token/', LiveKitTokenView.as_view(), name='session-token'),
    path('sessions/<str:session_id>/end/', EndSessionView.as_view(), name='session-end'),
    path('sessions/<str:session_id>/messages/', ChatMessageListCreateView.as_view(), name='session-messages'),
    path('sessions/<str:session_id>/materials/', SessionMaterialsView.as_view(), name='session-materials'),
    path('sessions/<str:session_id>/materials/<str:material_id>/',
         SessionMaterialDetailView.as_view(), name='session-material-detail'),
    path('token/', LiveKitTokenView.as_view(), name='livekit-token'),
]
