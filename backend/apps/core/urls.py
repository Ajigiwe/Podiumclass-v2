from django.urls import path
from .views import (
    FeatureFlagsView,
    AdminSettingsView,
    SettingDetailView,
    FeatureToggleView,
)

urlpatterns = [
    # Public: enabled feature flags for SPA surface gating (no auth, no secrets).
    path('features/', FeatureFlagsView.as_view(), name='core-features'),

    # Admin: full modular configuration (settings groups + feature flags).
    path('settings/', AdminSettingsView.as_view(), name='core-settings'),

    # Admin: update a single setting / toggle a single feature.
    path('settings/<str:key>/', SettingDetailView.as_view(), name='core-setting-detail'),
    path('features/<str:key>/', FeatureToggleView.as_view(), name='core-feature-toggle'),
]
