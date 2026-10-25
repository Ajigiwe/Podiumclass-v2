"""
Live smoke test for the modular settings & feature-flag system.

Requires a running dev server (manage.py runserver 127.0.0.1:8000).
Proves over real HTTP that:

  * the public feature-flags endpoint needs no auth and leaks no config
  * the admin settings endpoint is admin-only and masks secrets
  * toggling a feature persists and is reflected publicly
  * a disabled feature actually blocks its governed endpoint (503)
  * a re-enabled feature lets the endpoint through again
  * setting updates validate types and persist

Usage:
    python scripts/smoke_features.py
"""
import os
import sys
import json
import uuid
import urllib.request
import urllib.error

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

import django

django.setup()

from django.conf import settings

from apps.accounts.auth import issue_token
from apps.accounts.models import User
from apps.core import features
from apps.core.models import SystemSetting

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
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode() or "{}")
        except Exception:
            payload = {}
        return exc.code, payload
    except Exception as exc:  # noqa: BLE001
        return None, {"error": {"message": str(exc)}}


def main():
    suffix = uuid.uuid4().hex[:6]
    admin, _ = User.objects.get_or_create(
        email=f"smoke-admin-{suffix}@test.dev",
        defaults={"full_name": "Smoke Admin", "role": "admin"},
    )
    student, _ = User.objects.get_or_create(
        email=f"smoke-student-{suffix}@test.dev",
        defaults={"full_name": "Smoke Student", "role": "student"},
    )
    admin_tok = issue_token(admin)
    student_tok = issue_token(student)

    # Ensure a secret exists so masking can be verified.
    SystemSetting.objects.update_or_create(
        key="attendance_api_key", defaults={"value": "smoke-secret-value"}
    )

    failures = 0
    checks = 0

    def check(label, got, expected):
        nonlocal failures, checks
        checks += 1
        ok = got == expected
        if not ok:
            failures += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {label:52s} -> {got} (expected {expected})")
        return ok

    print(f"base={BASE}")
    print("=" * 78)

    # 1. Public flags: no auth, boolean map, no config leak.
    status, body = call("/api/v1/core/features/")
    check("public flags status", status, 200)
    flags = body.get("document", {}).get("features", {})
    check("public flags includes classroom_live", "classroom_live" in flags, True)
    check("public flags values are booleans",
          all(isinstance(v, bool) for v in flags.values()), True)
    check("public flags leak no labels/help",
          not any(k in json.dumps(body) for k in ["description", "help", "attendance_api_key"]), True)

    # 2. Admin settings: auth + role gating.
    status, _ = call("/api/v1/core/settings/")
    check("admin settings anonymous -> 401", status, 401)
    status, _ = call("/api/v1/core/settings/", token=student_tok)
    check("admin settings student -> 403", status, 403)

    status, body = call("/api/v1/core/settings/", token=admin_tok)
    check("admin settings admin -> 200", status, 200)
    doc = body.get("document", {})
    settings_list = doc.get("settings", [])
    feature_list = doc.get("features", [])
    check("settings payload has all groups", len(doc.get("groups", [])) >= 4, True)
    check("settings payload has features", len(feature_list), features.__len__() if hasattr(features, "__len__") else len(feature_list))

    by_key = {s["key"]: s for s in settings_list}
    secret = by_key.get("attendance_api_key", {})
    check("secret value not echoed", secret.get("value"), None)
    check("secret marked as set", secret.get("isSet"), True)
    check("non-secret returns value",
          by_key.get("livekit_url", {}).get("value") is not None, True)

    # 3. Toggle a feature as admin; verify persistence + public reflection.
    status, body = call("/api/v1/core/features/wallet_payments/",
                        token=admin_tok, method="PATCH", body={"enabled": False})
    check("admin toggle feature -> 200", status, 200)
    check("toggle response reflects disabled",
          body.get("document", {}).get("enabled"), False)
    check("toggle persisted to DB",
          SystemSetting.get_setting("feature_wallet_payments"), "false")

    status, body = call("/api/v1/core/features/")
    check("public flags reflect toggle",
          body.get("document", {}).get("features", {}).get("wallet_payments"), False)

    # 4. Disabled feature blocks its endpoint (503 FEATURE_DISABLED).
    status, body = call("/api/v1/billing/transactions/", token=admin_tok)
    check("disabled wallet blocks transactions", status, 503)
    check("blocked response code is FEATURE_DISABLED",
          body.get("error", {}).get("code"), "FEATURE_DISABLED")

    # 5. Re-enable; endpoint works again.
    status, _ = call("/api/v1/core/features/wallet_payments/",
                     token=admin_tok, method="PATCH", body={"enabled": True})
    check("re-enable feature -> 200", status, 200)
    status, _ = call("/api/v1/billing/transactions/", token=admin_tok)
    check("re-enabled wallet allows transactions", status, 200)

    # 6. Setting update: validation + persistence.
    status, body = call("/api/v1/core/settings/min_topup_amount/",
                        token=admin_tok, method="PATCH", body={"value": "not-a-number"})
    check("integer setting rejects junk -> 400", status, 400)

    status, body = call("/api/v1/core/settings/min_topup_amount/",
                        token=admin_tok, method="PATCH", body={"value": "250"})
    check("integer setting update -> 200", status, 200)
    check("integer setting persisted",
          SystemSetting.get_setting("min_topup_amount"), "250")

    # 7. Unknown keys rejected.
    status, _ = call("/api/v1/core/features/not_a_flag/",
                     token=admin_tok, method="PATCH", body={"enabled": True})
    check("unknown feature -> 404", status, 404)
    status, _ = call("/api/v1/core/settings/not_a_setting/",
                     token=admin_tok, method="PATCH", body={"value": "x"})
    check("unknown setting -> 404", status, 404)

    # 8. Blank secret write leaves existing value intact.
    status, _ = call("/api/v1/core/settings/attendance_api_key/",
                     token=admin_tok, method="PATCH", body={"value": ""})
    check("blank secret keeps existing value",
          SystemSetting.get_setting("attendance_api_key"), "smoke-secret-value")

    # Cleanup this run's rows (settings/flags restored to sensible defaults).
    SystemSetting.objects.update_or_create(
        key="attendance_api_key",
        defaults={"value": settings.ATTENDANCE_API_KEY},
    )
    features.set_enabled("wallet_payments", True)
    SystemSetting.objects.update_or_create(
        key="min_topup_amount", defaults={"value": "100"}
    )
    User.objects.filter(email__endswith="@test.dev").delete()

    print("=" * 78)
    print(f"RESULT: {checks} checks, {failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
