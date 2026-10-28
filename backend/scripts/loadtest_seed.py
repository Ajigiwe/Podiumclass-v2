"""
Seed one class with one host and 200 students, then write everything the load
harness needs to a JSON manifest.

Run against the throwaway Postgres container (never the dev SQLite file):

    set -a; USE_POSTGRES=true \
      DATABASE_URL=postgresql://podium:loadtest@localhost:5433/podium \
      python manage.py migrate
    USE_POSTGRES=true DATABASE_URL=... python scripts/loadtest_seed.py --students 200

The students are synthetic (``loadtest+NNN@podium.test``) and the whole database
is disposable, so there is nothing to clean up beyond dropping the container.
"""

import argparse
import json
import os
import pathlib
import sys

import django

# Run from anywhere: put the backend package root on the path so
# DJANGO_SETTINGS_MODULE=config.settings resolves.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--students", type=int, default=200)
    parser.add_argument(
        "--out",
        default=str(pathlib.Path(__file__).resolve().parents[2] / "loadtest" / "manifest.json"),
    )
    args = parser.parse_args()

    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")
    django.setup()

    from django.utils import timezone

    from apps.accounts.auth import issue_token
    from apps.accounts.models import User
    from apps.classrooms.models import Session

    def token_for(user) -> str:
        # The same signer the sign-in flow uses, so the harness carries real
        # tokens rather than something the auth layer has never seen.
        return issue_token(user)

    host, _ = User.objects.get_or_create(
        email="loadtest-host@podium.test",
        defaults={
            "full_name": "Load Test Host",
            "role": "lecturer",
            "is_active": True,
        },
    )
    host.role = "lecturer"
    host.full_name = host.full_name or "Load Test Host"
    host.save()

    students = []
    end = args.students
    for i in range(1, end + 1):
        email = f"loadtest+{i:03d}@podium.test"
        student, created = User.objects.get_or_create(
            email=email,
            defaults={
                "full_name": f"Load Test Student {i:03d}",
                "role": "student",
                "index_number": f"LT{i:03d}",
                "is_active": True,
            },
        )
        if created:
            # Deliberately unusable: nobody signs in with these accounts, the
            # harness presents a minted token instead.
            student.set_unusable_password()
            student.save()
        students.append({"email": email, "index": i, "token": token_for(student)})

    # One prepared class, opened the way a host opening it looks: active, clock
    # started, so students' token requests pass the "not started" gate.
    session = Session.objects.filter(title="Load Test 200", host=host).first() or Session(
        id="loadtest-200",
        title="Load Test 200",
        host=host,
        meeting_code="POD-LT200",
    )
    session.status = "active"
    session.is_active = True
    session.started_at = session.started_at or timezone.now()
    session.ended_at = None
    session.save()

    manifest = {
        "session_id": str(session.id),
        "meeting_code": getattr(session, "meeting_code", None),
        "host": {"email": host.email, "token": token_for(host)},
        "student_count": len(students),
        "students": students,
    }
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"seeded {len(students)} students into session {session.id}")
    print(f"manifest -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
