#!/usr/bin/env python3
"""Build the Vue frontend and publish it to the IFNOTUS / fPanel site root.

Why this script exists
----------------------
The fPanel account's document root is the hosting home itself
(``/home3/minatoflash8``), which is owned by ``root``. The SFTP/terminal user
(``ifn_ec779f23``) cannot write there, so ``cp``/``scp``/Git-pull into it fail
with "Permission denied" -- and files dropped into ``public_html`` are only
reachable at ``/public_html/...``, never at ``/``.

fPanel's own File Manager, however, runs with root privileges. Its web UI is a
thin client over a REST API, and that API is what this script drives:

    POST   .../files/upload?path=<dir>            multipart field "file"
    POST   .../files/unzip?path=<zip>&destination=<dir>
    DELETE .../files?path=<dir-or-file>

So a deploy is: build, zip, upload, unzip into ".", clean up. No manual file
moving, and no root shell needed.

Usage
-----
    # once, in the shell (never commit these):
    export FPANEL_EMAIL="you@example.com"
    export FPANEL_PASSWORD="..."

    python deploy/publish_frontend.py

Auth, in order of preference:

    FPANEL_TOKEN       a short-lived bearer token (DevTools -> Application ->
                       Local Storage -> access_token). Skips the login call.
    .fpanel-token      the same token in a git-ignored file at the repository
                       root (override the path with FPANEL_TOKEN_FILE). Prefer
                       this over the variable: a value exported in a shell ends
                       up in history and in any transcript of the session,
                       whereas a file is read and never echoed. Deleting the
                       file revokes the handoff.
    FPANEL_EMAIL       + FPANEL_PASSWORD, exchanged for a token via
                       POST /api/v1/auth/login.

    Tokens live 45 minutes. If the token being used has expired, the script
    signs in instead when FPANEL_EMAIL/FPANEL_PASSWORD are set, and otherwise
    says so before building anything. Set the credentials for a deploy you can
    repeat without hunting for a fresh token each time.

Configuration overrides (all optional):

    FPANEL_BASE      site base URL, default https://minatoflash82.ifnotus.space
    FPANEL_ENV_ID    hosting environment id, default ec779f23-d563-4098-90c7-544146b30e4a

Flags:

    --no-build       publish the existing frontend/dist instead of rebuilding
    --dist PATH      publish a specific build directory
    --prune          delete the remote assets/ and css/ before extracting.
                     Not the default: keeping old hashed bundles in place means
                     a browser holding a stale index.html still finds its JS.
                     Old files cost a few hundred KB, which the 8 GB plan can
                     absorb; run --prune occasionally if that matters.
    --dry-run        build and zip, then stop before touching the server
    --skip-verify    skip the post-deploy HTTP checks

Exit status is 0 only when every step (and the verification) passed.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FRONTEND_DIR = REPO_ROOT / "frontend"
DEFAULT_BASE = "https://minatoflash82.ifnotus.space"
DEFAULT_ENV_ID = "ec779f23-d563-4098-90c7-544146b30e4a"
ARCHIVE_NAME = "podium-web.zip"
# Where the archive is staged on the server before extraction. "tmp" already
# exists in the account layout and is not served.
STAGING_DIR = "tmp"
# The document root, as the panel's file API addresses it.
DOCUMENT_ROOT = "."


class DeployError(RuntimeError):
    """Any step that means the deploy did not happen."""


#: Default location for a token handed over out-of-band. Git-ignored.
TOKEN_FILE = REPO_ROOT / ".fpanel-token"

#: A token with less than this left is treated as unusable. Panel tokens live
#: 45 minutes, so one pasted through a chat window routinely expires between
#: being copied and being used; uploading with it wastes the whole build.
EXPIRY_MARGIN = 120


def token_from_file() -> str:
    """Read a token from FPANEL_TOKEN_FILE, or the default .fpanel-token.

    Never logged, never printed: only whether one was found.
    """
    path = Path(os.environ.get("FPANEL_TOKEN_FILE", str(TOKEN_FILE)))
    if not path.is_file():
        return ""
    try:
        value = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise DeployError(f"Could not read the token file {path}: {error}") from error
    # A token copied out of DevTools sometimes arrives wrapped in quotes, and a
    # trailing newline is normal in a file written by an editor.
    return value.strip().strip("\"'").strip()


def token_seconds_left(token: str) -> int | None:
    """Seconds until the token's ``exp`` claim, or None if it cannot be read.

    Only the unverified payload is decoded: this decides whether to bother
    calling the API, not whether the token is trustworthy -- the server still
    validates the signature on every request.
    """
    try:
        payload = token.split(".")[1]
    except IndexError:
        return None
    payload += "=" * (-len(payload) % 4)
    try:
        claims = json.loads(base64.urlsafe_b64decode(payload))
        return int(claims["exp"]) - int(time.time())
    except (ValueError, KeyError, TypeError):
        return None


def log(message: str) -> None:
    print(message, flush=True)


def site_base() -> str:
    return os.environ.get("FPANEL_BASE", DEFAULT_BASE).rstrip("/")


def files_api() -> str:
    env_id = os.environ.get("FPANEL_ENV_ID", DEFAULT_ENV_ID)
    return f"{site_base()}/api/v1/customers/environments/{env_id}/files"


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------


def call(method: str, url: str, token: str | None = None, body: bytes | None = None,
         headers: dict[str, str] | None = None, timeout: int = 180) -> tuple[int, str]:
    """Perform one request and return (status, text). HTTP errors are returned, not raised."""
    request_headers = dict(headers or {})
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def encode_multipart(field: str, filename: str, content: bytes,
                     content_type: str = "application/zip") -> tuple[bytes, str]:
    """Build a multipart/form-data body with a single file part.

    Written by hand rather than pulled from a library: this script runs with the
    system Python on a machine that may not have requests installed.
    """
    boundary = "----podium" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return head + content + tail, f"multipart/form-data; boundary={boundary}"


def login() -> str:
    """Return a bearer token from the environment, the token file, or sign in."""
    email = os.environ.get("FPANEL_EMAIL", "").strip()
    password = os.environ.get("FPANEL_PASSWORD", "")
    can_sign_in = bool(email and password)

    stale = ""
    for source, token in (
        ("FPANEL_TOKEN", os.environ.get("FPANEL_TOKEN", "").strip()),
        (f"the token file ({TOKEN_FILE.name})", token_from_file()),
    ):
        if not token:
            continue
        remaining = token_seconds_left(token)
        if remaining is None or remaining > EXPIRY_MARGIN:
            note = f" Valid for {remaining // 60} more min." if remaining is not None else ""
            log(f"Using a token from {source}.{note}")
            return token
        stale = f"The token from {source} expired {abs(remaining) // 60} min ago."

    if stale and not can_sign_in:
        raise DeployError(
            f"{stale} Panel tokens last 45 minutes. Copy a fresh one into "
            f"{TOKEN_FILE.name}, or set FPANEL_EMAIL and FPANEL_PASSWORD so "
            "every deploy mints its own."
        )
    if stale:
        # Credentials are present, so a dead token costs a login call, not the
        # deploy. Fall through and mint a fresh one.
        log(f"{stale} Signing in for a fresh one.")

    if not can_sign_in:
        raise DeployError(
            "No credentials. Put a panel bearer token in "
            f"{TOKEN_FILE.name} (or point FPANEL_TOKEN_FILE at it), set "
            "FPANEL_TOKEN, or set both FPANEL_EMAIL and FPANEL_PASSWORD."
        )

    log(f"Signing in as {email}...")
    status, text = call(
        "POST",
        f"{site_base()}/api/v1/auth/login",
        body=json.dumps({"email": email, "password": password}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    if status != 200:
        raise DeployError(f"Login failed ({status}): {text[:300]}")
    try:
        return json.loads(text)["access_token"]
    except (ValueError, KeyError) as error:
        raise DeployError(f"Login response had no access_token: {text[:300]}") from error


# --------------------------------------------------------------------------
# Build and package
# --------------------------------------------------------------------------


# Vite inlines VITE_* variables at build time, so a plain `npm run build`
# produces a bundle that talks to http://<host>:8000 and trusts only
# localhost as a Google origin -- both wrong in production, and both fail
# silently: the browser blocks the API call as mixed content and the sign-in
# button refuses to open. The project has no checked-in .env files (they are
# git-ignored), so the defaults live here and the build is given them
# explicitly. Override either by exporting the variable before running.
BUILD_ENV_DEFAULTS = {
    "VITE_API_URL": "https://podium-api-r6sz.onrender.com",
    "VITE_GOOGLE_ALLOWED_ORIGINS": DEFAULT_BASE,
}


def build_frontend() -> Path:
    npm = shutil.which("npm")
    if not npm:
        raise DeployError("npm was not found on PATH; install Node before deploying.")

    build_env = dict(os.environ)
    for key, default in BUILD_ENV_DEFAULTS.items():
        # Lets a staging build point somewhere else without editing this file.
        build_env.setdefault(key, default)
    log("Building frontend (npm run build) with "
        + ", ".join(f"{k}={build_env[k]}" for k in BUILD_ENV_DEFAULTS) + "...")

    # shell=False with the resolved path keeps this working on Windows, where
    # npm is a .cmd shim and bare "npm" is not directly executable.
    result = subprocess.run([npm, "run", "build"], cwd=FRONTEND_DIR, env=build_env)
    if result.returncode != 0:
        raise DeployError(f"Build failed with exit code {result.returncode}.")
    dist = FRONTEND_DIR / "dist"
    if not (dist / "index.html").is_file():
        raise DeployError(f"Build produced no index.html at {dist}.")

    # Prove the variables were actually inlined. Vite replaces
    # `import.meta.env.VITE_API_URL` at build time, so if the string is absent
    # from the output the client will fall back to `${host}:8000` at runtime
    # and every API call fails as mixed content -- with no build error at all.
    api_url = build_env["VITE_API_URL"]
    haystack = "".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for path in (dist / "assets").glob("*.js")
    )
    if api_url not in haystack:
        raise DeployError(
            f"The built bundle does not reference VITE_API_URL ({api_url}), so it "
            "would call the API on the page's own host instead. Aborting before publish."
        )
    log(f"Verified VITE_API_URL ({api_url}) is inlined in the bundle.")
    return dist


def package(dist: Path, destination: Path) -> Path:
    """Zip a build directory with index.html at the archive root."""
    if not (dist / "index.html").is_file():
        raise DeployError(f"{dist} has no index.html; refusing to package it.")

    files = [path for path in sorted(dist.rglob("*")) if path.is_file()]
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            archive.write(path, path.relative_to(dist).as_posix())

    with zipfile.ZipFile(destination) as archive:
        names = archive.namelist()
    if "index.html" not in names:
        raise DeployError("Archive is missing index.html at its root.")

    log(f"Packaged {len(files)} files into {destination.name} "
        f"({destination.stat().st_size / 1_048_576:.1f} MiB).")
    return destination


# --------------------------------------------------------------------------
# Publish
# --------------------------------------------------------------------------


def upload(token: str, archive: Path) -> str:
    """Upload the archive into the staging directory; return its server path."""
    log(f"Uploading to {STAGING_DIR}/{ARCHIVE_NAME}...")
    body, content_type = encode_multipart("file", ARCHIVE_NAME, archive.read_bytes())
    status, text = call(
        "POST",
        f"{files_api()}/upload?path={urllib.parse.quote(STAGING_DIR)}",
        token=token,
        body=body,
        headers={"Content-Type": content_type},
    )
    if status != 200:
        raise DeployError(f"Upload failed ({status}): {text[:300]}")
    return f"{STAGING_DIR}/{ARCHIVE_NAME}"


def remove(token: str, path: str, required: bool = False) -> None:
    status, text = call("DELETE", f"{files_api()}?path={urllib.parse.quote(path)}", token=token)
    if status not in (200, 204, 404) and required:
        raise DeployError(f"Could not remove {path} ({status}): {text[:200]}")


def publish(token: str, remote_archive: str, prune: bool) -> None:
    if prune:
        # Only the directories the build owns. index.html and the root-level
        # images are overwritten by the extraction itself.
        for directory in ("assets", "css"):
            log(f"Pruning remote {directory}/ ...")
            remove(token, directory)

    log(f"Extracting into the document root ({DOCUMENT_ROOT})...")
    query = urllib.parse.urlencode({"path": remote_archive, "destination": DOCUMENT_ROOT})
    status, text = call("POST", f"{files_api()}/unzip?{query}", token=token)
    if status != 200:
        raise DeployError(f"Extraction failed ({status}): {text[:300]}")

    log("Removing the staged archive...")
    remove(token, remote_archive)


# --------------------------------------------------------------------------
# Verify
# --------------------------------------------------------------------------


def verify() -> None:
    """Confirm the live site serves the build we just published."""
    base = site_base()
    log(f"Verifying {base}/ ...")

    status, html = call("GET", f"{base}/")
    if status != 200:
        raise DeployError(f"Site root returned {status} (expected 200).")
    if "<title>Podium" not in html:
        raise DeployError("Site root did not return the Podium index.html.")

    # The bundle is content-hashed, so fetching the one index.html references
    # proves the extraction landed rather than an old copy being served.
    match = re.search(r'src="(/assets/[^"]+\.js)"', html)
    if not match:
        raise DeployError("index.html references no /assets/*.js bundle.")
    bundle = match.group(1)
    bundle_status, _ = call("GET", f"{base}{bundle}")
    if bundle_status != 200:
        raise DeployError(f"{bundle} returned {bundle_status} (expected 200).")

    log(f"OK: {base}/ serves index.html and {bundle}.")


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build and publish the frontend to the fPanel hosting root.",
    )
    parser.add_argument("--no-build", action="store_true",
                        help="publish the existing frontend/dist instead of rebuilding")
    parser.add_argument("--dist", type=Path, default=None,
                        help="publish a specific build directory")
    parser.add_argument("--prune", action="store_true",
                        help="delete remote assets/ and css/ before extracting")
    parser.add_argument("--dry-run", action="store_true",
                        help="build and package, then stop without touching the server")
    parser.add_argument("--skip-verify", action="store_true",
                        help="skip the post-deploy HTTP checks")
    arguments = parser.parse_args()

    try:
        # Authenticate before building. A token pasted minutes ago may already
        # be dead, and it is better to say so in a second than after a
        # minute-long build plus a half-megabyte upload that gets a 401.
        token = "" if arguments.dry_run else login()

        dist = arguments.dist or (FRONTEND_DIR / "dist" if arguments.no_build else build_frontend())
        if not dist.is_dir():
            raise DeployError(f"{dist} is not a directory; run without --no-build first.")

        with tempfile.TemporaryDirectory() as workspace:
            archive = package(dist, Path(workspace) / ARCHIVE_NAME)
            if arguments.dry_run:
                log("Dry run: packaged, nothing uploaded.")
                return 0

            remote_archive = upload(token, archive)
            publish(token, remote_archive, arguments.prune)

        if not arguments.skip_verify:
            verify()
        log("Deploy complete.")
        return 0
    except DeployError as error:
        print(f"Deploy failed: {error}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
