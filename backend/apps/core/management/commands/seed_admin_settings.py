import os
import uuid

from django.conf import settings
from django.core.management.base import BaseCommand

from apps.accounts.models import User
from apps.core.models import SystemSetting


def _env(name, default=""):
    return os.getenv(name, default)


def _configured_secret(value):
    """
    Treat the development placeholders as "not configured".

    settings.env_secret() returns an unusable, per-boot placeholder when a
    service credential is absent. Persisting that into the database would make
    the admin UI report a secret as configured when it is not.
    """
    if value and not str(value).startswith("dev-only-"):
        return value
    return ""


class Command(BaseCommand):
    help = (
        "Seeds platform settings and the superadmin account. "
        "Credentials are read from the environment; none are stored in source."
    )

    def handle(self, *args, **options):
        self.stdout.write("Seeding platform settings into database...")

        # Non-secret defaults may live here. Secrets are read from the
        # environment: a credential committed to source control is not a secret,
        # and this file is the worst place for one because it is copied into
        # every deployment.
        default_settings = [
            ("admin_email", _env("ADMIN_EMAIL", "minatoflash82@gmail.com"),
             "Primary Superadmin Email", False),
            ("google_client_id", _env("GOOGLE_CLIENT_ID"),
             "Google OAuth Client ID", False),
            ("google_client_secret", _env("GOOGLE_CLIENT_SECRET"),
             "Google OAuth Client Secret", True),
            ("livekit_url", settings.LIVEKIT_URL,
             "LiveKit WebRTC Cloud Gateway", False),
            ("livekit_api_key", settings.LIVEKIT_API_KEY,
             "LiveKit API Key", False),
            ("livekit_api_secret", _configured_secret(settings.LIVEKIT_API_SECRET),
             "LiveKit API Secret", True),
            ("attendance_api_key", _configured_secret(settings.ATTENDANCE_API_KEY),
             "External Attendance API v1 Key (Laravel integration)", True),
            ("attendance_rate_limit", str(settings.ATTENDANCE_API_RATE_LIMIT),
             "Max attendance API requests per minute", False),
            ("minio_endpoint", _env("MINIO_ENDPOINT"),
             "MinIO / S3 Storage Endpoint", False),
            ("minio_access_key", _env("MINIO_ACCESS_KEY"),
             "MinIO Access Key", False),
            ("minio_secret_key", _env("MINIO_SECRET_KEY"),
             "MinIO Secret Key", True),
            ("min_topup_amount", "100",
             "Minimum wallet top up in pesewas (1.00 GHS)", False),
            ("refund_threshold_minutes", "30",
             "Minimum lecture duration to prevent automatic refund", False),
        ]

        for key, value, desc, is_sec in default_settings:
            if is_sec and not value:
                # Nothing configured: create the row if it is missing, but never
                # overwrite a value an operator already stored.
                _, created = SystemSetting.objects.get_or_create(
                    key=key,
                    defaults={"value": "", "description": desc, "is_secret": True},
                )
                status = "Created (unset)" if created else "Kept existing"
            else:
                _, created = SystemSetting.objects.update_or_create(
                    key=key,
                    defaults={"value": value, "description": desc, "is_secret": is_sec},
                )
                status = "Created" if created else "Updated"
            self.stdout.write(f"  [{status}] {key}")

        # Materialise every registered feature flag at its default. Only rows
        # that are missing are created, so an admin's custom toggles survive
        # a re-run of this command.
        from apps.core import features as feature_registry
        created_flags = feature_registry.seed_all()
        self.stdout.write(
            f"Feature flags: {created_flags} new, "
            f"{len(feature_registry.FEATURES) - created_flags} already present."
        )

        # Seed or promote the admin account.
        admin_email = _env("ADMIN_EMAIL", "minatoflash82@gmail.com")
        admin_user, created = User.objects.get_or_create(
            email=admin_email,
            defaults={
                "id": f"usr_{uuid.uuid4().hex[:16]}",
                "full_name": "Super Admin (Minato)",
                "role": "admin",
                "is_staff": True,
                "is_superuser": True,
                "is_verified": True,
                "wallet_balance": 100000,
            },
        )
        if not created:
            admin_user.role = "admin"
            admin_user.is_staff = True
            admin_user.is_superuser = True
            admin_user.is_verified = True
            admin_user.save()
            self.stdout.write(f"Promoted existing user {admin_email} to Superadmin.")
        else:
            self.stdout.write(f"Created new Superadmin account for {admin_email}.")

        self.stdout.write(self.style.SUCCESS("Admin settings seeded successfully."))
