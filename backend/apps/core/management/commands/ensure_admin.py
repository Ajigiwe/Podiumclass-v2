"""
Create the first administrator from environment variables.

Why this exists: a free Render instance has no Shell tab, so
``manage.py createsuperuser`` -- the usual way to get a first login -- cannot be
run at all. Without this, a deployment is fully working and has nobody who can
sign into it.

It is a bootstrap, not a user-management tool:

* It does nothing unless DJANGO_ADMIN_EMAIL and DJANGO_ADMIN_PASSWORD are both
  set, so it is inert on the Docker stack and in development.
* Running it repeatedly is safe -- the deploy start command calls it on every
  boot -- and an existing account keeps its password, so a password changed in
  the app is not silently reverted on the next restart. Pass --reset-password
  (or set DJANGO_ADMIN_RESET_PASSWORD=true) to overwrite it deliberately.
* Unset both variables once you can sign in. A password sitting in a config var
  is a bootstrap convenience, not a place to keep a real credential.
"""

import os

from django.core.management.base import BaseCommand

from apps.accounts.models import User


class Command(BaseCommand):
    help = "Create or repair the bootstrap administrator from environment variables."

    def add_arguments(self, parser):
        parser.add_argument(
            "--reset-password",
            action="store_true",
            help="Overwrite the existing account's password with the env value.",
        )

    def handle(self, *args, **options):
        email = (os.getenv("DJANGO_ADMIN_EMAIL") or "").strip().lower()
        password = os.getenv("DJANGO_ADMIN_PASSWORD") or ""
        name = (os.getenv("DJANGO_ADMIN_NAME") or "Podium Admin").strip()
        reset = options["reset_password"] or os.getenv(
            "DJANGO_ADMIN_RESET_PASSWORD", ""
        ).lower() in ("true", "1", "yes")

        if not email or not password:
            self.stdout.write(
                "DJANGO_ADMIN_EMAIL / DJANGO_ADMIN_PASSWORD not set; "
                "skipping administrator bootstrap."
            )
            return

        user = User.objects.filter(email=email).first()

        if user is None:
            User.objects.create_superuser(email=email, full_name=name, password=password)
            self.stdout.write(self.style.SUCCESS(f"Created administrator {email}"))
            return

        # Repair rather than recreate: a half-configured account (signed up
        # through Google, so no staff flag) is the common case this has to fix.
        changed = []
        for field, value in (("is_staff", True), ("is_superuser", True), ("role", "admin")):
            if getattr(user, field) != value:
                setattr(user, field, value)
                changed.append(field)
        if reset:
            user.set_password(password)
            changed.append("password")
        if changed:
            user.save(update_fields=changed)
            self.stdout.write(
                self.style.SUCCESS(f"Updated administrator {email}: {', '.join(changed)}")
            )
        else:
            self.stdout.write(f"Administrator {email} already up to date.")
