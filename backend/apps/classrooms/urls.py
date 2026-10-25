from django.urls import path
from .views import SessionListCreateView, SessionDetailView, LiveKitTokenView, EndSessionView, ChatMessageListCreateView

urlpatterns = [
    path('sessions/', SessionListCreateView.as_view(), name='session-list-create'),
    path('sessions/<str:session_id>/', SessionDetailView.as_view(), name='session-detail'),
    path('sessions/<str:session_id>/token/', LiveKitTokenView.as_view(), name='session-token'),
    path('sessions/<str:session_id>/end/', EndSessionView.as_view(), name='session-end'),
    path('sessions/<str:session_id>/messages/', ChatMessageListCreateView.as_view(), name='session-messages'),
    path('token/', LiveKitTokenView.as_view(), name='livekit-token'),
]
