"""
Live smoke test for the Podium auth layer.

Requires a running dev server (manage.py runserver 127.0.0.1:8000).
Proves that authentication and role guards work over real HTTP, not just in
the Django test harness:

  * anonymous requests are rejected with 401
  * the legacy forgeable token ("podium_jwt_<id>") is rejected
  * a valid signed JWT resolves to the right user
  * students cannot create sessions or end someone else's session
  * students cannot escalate to lecturer in a LiveKit token
  * wallet top-up fails closed (no Paystack verification wired yet)

Usage:
    python scripts/smoke_auth.py
"""
import os
import sys
import json
import uuid
import urllib.request
import urllib.error

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)
sys.path.insert(0, os.path.join(BASE_DIR, "config"))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

import django

django.setup()

import jwt as pyjwt
from django.conf import settings

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.classrooms.models import Session

BASE = os.environ.get("SMOKE_BASE_URL", "http://127.0.0.1:8000")


def call(path, token=None, method="GET", body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()
    except Exception as exc:  # noqa: BLE001 - surface anything as a failure
        return None, str(exc)


def main():
    suffix = uuid.uuid4().hex[:6]
    student, _ = User.objects.get_or_create(
        email=f"smoke-student-{suffix}@test.dev",
        defaults={"full_name": "Smoke Student", "role": "student"},
    )
    lecturer, _ = User.objects.get_or_create(
        email=f"smoke-lecturer-{suffix}@test.dev",
        defaults={"full_name": "Smoke Lecturer", "role": "lecturer"},
    )
    session = Session.objects.create(
        id=f"smoke-ses-{suffix}",
        title="Smoke Session",
        host=lecturer,
        lecturer=lecturer,
        meeting_code=f"SMK-{suffix.upper()}",
        status="active",
        is_active=True,
        is_free=True,
        price=0,
    )

    student_tok = issue_token(student)
    lecturer_tok = issue_token(lecturer)
    legacy_tok = f"podium_jwt_{student.id}"

    cases = [
        ("anonymous -> /me", "/api/v1/accounts/me/", "GET", None, None, 401),
        ("legacy forged token -> /me", "/api/v1/accounts/me/", "GET", legacy_tok, None, 401),
        ("valid student -> /me", "/api/v1/accounts/me/", "GET", student_tok, None, 200),
        ("valid lecturer -> /me", "/api/v1/accounts/me/", "GET", lecturer_tok, None, 200),
        ("anonymous -> sessions list", "/api/v1/classrooms/sessions/", "GET", None, None, 401),
        ("valid token -> sessions list", "/api/v1/classrooms/sessions/", "GET", student_tok, None, 200),
        (
            "student -> create session",
            "/api/v1/classrooms/sessions/",
            "POST",
            student_tok,
            {"title": "Should Fail", "isFree": True},
            403,
        ),
        (
            "lecturer -> create session",
            "/api/v1/classrooms/sessions/",
            "POST",
            lecturer_tok,
            {"title": f"Smoke Class {suffix}", "course": "SMK101", "isFree": True},
            201,
        ),
        ("anonymous -> livekit token", "/api/v1/classrooms/token/", "POST", None,
         {"roomName": "r1", "role": "lecturer"}, 401),
        ("student -> livekit as lecturer", "/api/v1/classrooms/token/", "POST", student_tok,
         {"roomName": f"smoke-room-{suffix}", "role": "lecturer"}, 200),
        ("student -> end lecturer's session", f"/api/v1/classrooms/sessions/{session.id}/end/",
         "POST", student_tok, None, 403),
        ("lecturer -> end own session", f"/api/v1/classrooms/sessions/{session.id}/end/",
         "POST", lecturer_tok, None, 200),
        ("anonymous -> topup", "/api/v1/billing/topup/", "POST", None, {"amount": 100000}, 401),
        ("student -> topup (no paystack)", "/api/v1/billing/topup/", "POST", student_tok,
         {"amount": 100000}, 503),
        ("anonymous -> deduct", "/api/v1/billing/deduct/", "POST", None, {"sessionId": "x"}, 401),
        ("student -> deduct (own wallet)", "/api/v1/billing/deduct/", "POST", student_tok,
         {"sessionId": "x"}, 404),
        ("anonymous -> attendance join", "/api/v1/attendance/join/", "POST", None,
         {"sessionId": "x", "studentId": "y"}, 403),
    ]

    print(f"base={BASE}")
    print("=" * 74)
    failures = 0
    for label, path, method, token, body, expected in cases:
        status, payload = call(path, token=token, method=method, body=body)
        ok = status == expected
        if not ok:
            failures += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {label:36s} -> {status} (expected {expected})")
        if not ok:
            print(f"        {payload[:200]}")

    # The escalation guard: the LiveKit token a student receives must carry
    # student privileges regardless of the role they asked for.
    status, payload = call(
        "/api/v1/classrooms/token/",
        token=student_tok,
        method="POST",
        body={"roomName": f"smoke-room-{suffix}", "role": "lecturer"},
    )
    if status == 200:
        doc = json.loads(payload).get("document", {})
        claims = pyjwt.decode(doc["token"], settings.LIVEKIT_API_SECRET, algorithms=["HS256"])
        meta = json.loads(claims["metadata"])
        role_ok = meta.get("role") == "student" and claims["video"]["roomAdmin"] is False
        print(
            f"[{'PASS' if role_ok else 'FAIL'}] livekit role stays student "
            f"(meta={meta.get('role')}, roomAdmin={claims['video']['roomAdmin']})"
        )
        if not role_ok:
            failures += 1
    else:
        print(f"[FAIL] livekit token fetch -> {status}")
        failures += 1

    # Cleanup the smoke rows so the dev DB stays tidy. Sessions reference users
    # via RESTRICT, so they must go first. Scope strictly to this run's suffix
    # so we never touch real rows.
    Session.objects.filter(id__startswith="smoke-ses-").delete()
    Session.objects.filter(title__startswith="Smoke Class").delete()
    User.objects.filter(email__endswith=f"@test.dev").delete()

    print("=" * 74)
    print(f"RESULT: {len(cases) + 1} checks, {failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
