"""
Wipe all mock/demo data (sessions, attendance, history, transactions, and
non-admin users) while preserving:
  - the admin account (role='admin' or is_superuser=True, or email == the
    system_settings 'admin_email' key)
  - all system_settings rows (functional runtime config, not mock data)
  - Django framework tables (auth_permission, django_content_type, migrations)

Deletion runs in FK-safe order: session-scoped subcollections first, then
sessions, then non-admin users (attendance/transactions reference users with
on_delete=RESTRICT, so they must go before users).
"""
from django.core.management.base import BaseCommand
from django.db import transaction

from apps.accounts.models import User
from apps.classrooms.models import (
    Session, CoHost, Participant, ParticipantPermission, PermissionRequest, ChatMessage,
)
from apps.attendance.models import (
    Attendance, AttendanceLog, Verification, VerificationResponse,
)
from apps.billing.models import Transaction
from apps.core.models import SystemSetting


class Command(BaseCommand):
    help = "Wipe all mock data (sessions, attendance, transactions, non-admin users). Keeps admin + system_settings."

    def add_arguments(self, parser):
        parser.add_argument(
            "--dry-run", action="store_true",
            help="Report what would be deleted without writing.",
        )

    def handle(self, *args, **options):
        dry = options["dry_run"]

        # Resolve the admin identity to preserve.
        admin_email = SystemSetting.get_setting("admin_email", "minatoflash82@gmail.com")
        admin_emails = {admin_email.lower()} if admin_email else set()
        admin_ids = set(
            User.objects.filter(role="admin").values_list("id", flat=True)
        ) | set(
            User.objects.filter(is_superuser=True).values_list("id", flat=True)
        ) | set(
            User.objects.filter(email__in=admin_emails).values_list("id", flat=True)
        )

        users_total = User.objects.count()
        users_keep = User.objects.filter(id__in=admin_ids).count()
        users_delete = users_total - users_keep

        stats = {
            "sessions": Session.objects.count(),
            "attendance": Attendance.objects.count(),
            "attendance_logs": AttendanceLog.objects.count(),
            "verifications": Verification.objects.count(),
            "verification_responses": VerificationResponse.objects.count(),
            "transactions": Transaction.objects.count(),
            "co_hosts": CoHost.objects.count(),
            "participants": Participant.objects.count(),
            "participant_permissions": ParticipantPermission.objects.count(),
            "permission_requests": PermissionRequest.objects.count(),
            "chat_messages": ChatMessage.objects.count(),
            "users_deleted": users_delete,
            "users_kept": users_keep,
        }

        self.stdout.write("Mock data to wipe" + (" (DRY RUN)" if dry else "") + ":")
        for k, v in stats.items():
            self.stdout.write(f"  {k:26s} {v}")
        self.stdout.write(f"  {'system_settings':26s} {SystemSetting.objects.count()} (KEEP)")
        self.stdout.write(f"  preserved admin ids: {sorted(admin_ids)}")

        if dry:
            self.stdout.write(self.style.WARNING("Dry run — no changes written."))
            return

        with transaction.atomic():
            # 1. Session-scoped subcollections & history (CASCADE on session,
            #    but delete explicitly so counts are accurate and idempotent).
            VerificationResponse.objects.all().delete()
            Verification.objects.all().delete()
            Attendance.objects.all().delete()
            AttendanceLog.objects.all().delete()
            # Transactions reference users with RESTRICT -> clear before users.
            Transaction.objects.all().delete()

            # 2. Classroom moderation / presence / chat.
            PermissionRequest.objects.all().delete()
            ParticipantPermission.objects.all().delete()
            CoHost.objects.all().delete()
            Participant.objects.all().delete()
            ChatMessage.objects.all().delete()

            # 3. Sessions (host/lecturer FK is RESTRICT -> gone once users go,
            #    but delete first so the cascade is explicit).
            Session.objects.all().delete()

            # 4. All non-admin users.
            User.objects.exclude(id__in=admin_ids).delete()

        self.stdout.write(self.style.SUCCESS("Wipe complete."))

        # Post-wipe summary.
        self.stdout.write("\nRemaining data:")
        self.stdout.write(f"  users            {User.objects.count()}")
        for u in User.objects.all():
            self.stdout.write(f"      - {u.email} ({u.role})")
        self.stdout.write(f"  sessions         {Session.objects.count()}")
        self.stdout.write(f"  attendance       {Attendance.objects.count()}")
        self.stdout.write(f"  transactions     {Transaction.objects.count()}")
        self.stdout.write(f"  system_settings  {SystemSetting.objects.count()} (kept)")
