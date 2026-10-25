from django.urls import path
from .views import CurrentUserView, GoogleAuthView

urlpatterns = [
    path('me/', CurrentUserView.as_view(), name='account-me'),
    path('google/', GoogleAuthView.as_view(), name='account-google'),
    path('login/', GoogleAuthView.as_view(), name='account-login'),
]
