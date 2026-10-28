from django.urls import path
from .admin_views import AdminUserDetailView, AdminUserListView
from .views import CurrentUserView, GoogleAuthView, ProfilePhotoView, AvatarView

urlpatterns = [
    path('me/', CurrentUserView.as_view(), name='account-me'),
    # Profile picture: the upload is self-service, the served file is public
    # (an <img> cannot send a bearer token).
    path('me/photo/', ProfilePhotoView.as_view(), name='account-me-photo'),
    path('avatar/<str:user_id>/', AvatarView.as_view(), name='account-avatar'),
    path('google/', GoogleAuthView.as_view(), name='account-google'),
    path('login/', GoogleAuthView.as_view(), name='account-login'),

    # Admin user management (control panel: roles, class-creation grants,
    # account status). Admin-only; the views enforce it themselves.
    path('users/', AdminUserListView.as_view(), name='account-users'),
    path('users/<str:user_id>/', AdminUserDetailView.as_view(), name='account-user-detail'),
]
