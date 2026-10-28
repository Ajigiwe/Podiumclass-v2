"""
Session resources: materials shared with a class, and recordings.

Materials are addressed under the session that owns them --

    GET    /api/v1/classrooms/sessions/<session_id>/materials/
    POST   /api/v1/classrooms/sessions/<session_id>/materials/
    DELETE /api/v1/classrooms/sessions/<session_id>/materials/<material_id>/

-- because that is how a student reaches them, but they are implemented here
next to the `SessionMaterial` model rather than in the classrooms app: a file
shared with a class is a storage object, not a session attribute.

Recordings follow the same shape and for the same reason -- an MP4 is a file --

    GET    /api/v1/classrooms/sessions/<session_id>/recordings/
    POST   /api/v1/classrooms/sessions/<session_id>/recordings/
    GET    /api/v1/classrooms/sessions/<session_id>/recordings/<recording_id>/
    POST   /api/v1/classrooms/sessions/<session_id>/recordings/<recording_id>/stop/
    GET    /api/v1/classrooms/sessions/<session_id>/recordings/<recording_id>/file/

The routes live in ``apps/classrooms/urls.py`` so the whole
``/api/v1/classrooms/`` surface stays readable in one place.
"""
import os
import re
import time
import uuid
from datetime import datetime

import jwt
from django.conf import settings
from django.core.exceptions import ValidationError as DjangoValidationError
from django.core.validators import URLValidator
from django.http import StreamingHttpResponse
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from django.db.models import Q, Subquery

from apps.classrooms.access import can_moderate
from apps.classrooms.models import CoHost, Session
from apps.core.guards import require_feature
from . import egress, recording_links
from .models import Recording, SessionMaterial
from .serializers import MATERIAL_TYPES, SessionMaterialSerializer


#: Cap on the dashboard's Records feed. The list is paged by "recent", not by
#: completeness: a host with hundreds of lectures scrolls their classes for the
#: old ones rather than making every dashboard load carry them all.
RECORDINGS_FEED_LIMIT = 100

#: How long a recording may sit in "starting" before it is treated as dead.
#: A composite pipeline is live within a second or two of joining the room; the
#: generous ceiling is for a loaded machine starting Chrome, not for a class
#: that never sends any media.
STARTUP_TIMEOUT_SECONDS = 60

#: How long a room may have nobody in it before its live recording is closed.
#: Long enough to cover a host whose laptop dropped off the network and came
#: back, short enough that a class abandoned on Friday is watchable on Monday.
EMPTY_ROOM_GRACE_SECONDS = 180

#: How often a live recording asks the room who is in it. Every status poll
#: asking would double the traffic of a running lecture for no new answer.
PRESENCE_CHECK_SECONDS = 30


def _can_manage_materials(user, session) -> bool:
    """Host, lecturer, admin, or an active co-host -- see classrooms.access."""
    return can_moderate(user, session)


_SAFE_SUFFIX = re.compile(r"^[A-Za-z0-9]{1,10}$")


def _material_file_token(material_id: str) -> str:
    """
    A JWT authorising anonymous-but-signed download of one material file.

    The modal opens an uploaded file with a plain anchor, and a navigation
    cannot attach an Authorization header -- so instead of dropping auth on
    the file route, each uploaded file gets a self-authorising token bound to
    its material id. It is signed with the shared SECRET_KEY (the exact model
    room-join tokens use) and is worthless for any other material or endpoint.
    """
    return jwt.encode(
        {"mat": material_id, "typ": "material-file"},
        settings.SECRET_KEY,
        algorithm="HS256",
    )


def _store_upload(fh, material_id: str) -> dict:
    """
    Persist one uploaded file under MATERIALS_ROOT.

    The stored name is deterministic -- ``fs_<material id>` plus the original
    file's safe extension -- so the download view finds the file from the row's
    id alone and no stored path can ever be trusted or escaped. Filename parts
    are generated, never taken from the client.
    """
    root = os.path.abspath(str(settings.MATERIALS_ROOT))
    os.makedirs(root, exist_ok=True)

    original = os.path.basename(getattr(fh, "name", "") or "file")
    ext = ""
    if "." in original:
        raw_ext = original.rsplit(".", 1)[1].lower()
        if _SAFE_SUFFIX.match(raw_ext):
            ext = f".{raw_ext}"

    stored_name = f"fs_{material_id}{ext}"
    path = os.path.abspath(os.path.join(root, stored_name))
    if os.path.dirname(path) != root:
        raise ValueError("BAD_PATH")

    size = 0
    with open(path, "wb+") as out:
        for chunk in fh.chunks():
            size += len(chunk)
            if size > settings.MATERIAL_MAX_BYTES:
                raise ValueError("TOO_LARGE")
            out.write(chunk)

    return {"path": path, "stored_name": stored_name, "size": size, "original": original}


def _session_or_404(session_id):
    try:
        return Session.objects.get(id=session_id, is_deleted=False), None
    except Session.DoesNotExist:
        return None, Response(
            {"error": {"code": "NOT_FOUND", "message": f"Session '{session_id}' not found."}},
            status=status.HTTP_404_NOT_FOUND,
        )


def _bad(message):
    return Response(
        {"error": {"code": "VALIDATION_ERROR", "message": message}},
        status=status.HTTP_400_BAD_REQUEST,
    )


class SessionMaterialsView(APIView):
    """
    Read and share the resources attached to a classroom session.

    GET  -> { "documents": [ { id, title, url, type, size, icon, ... } ] }
    POST -> { "document": { ... } }   (host, lecturer or admin only)

    The modal renders `url` straight into an anchor, so the link is validated as
    an absolute http(s) address -- anything else (javascript:, data:, a bare
    relative path) is rejected rather than stored and handed to every student.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error
        materials = SessionMaterial.objects.filter(session=session)
        return Response(SessionMaterialSerializer(materials, many=True).data)

    def post(self, request, session_id):
        session, error = _session_or_404(session_id)
        if error:
            return error

        if not _can_manage_materials(request.user, session):
            return Response(
                {"error": {"code": "FORBIDDEN", "message": "Only the host can share materials."}},
                status=status.HTTP_403_FORBIDDEN,
            )

        data = request.data
        title = (data.get("title") or "").strip()
        url = (data.get("url") or "").strip()
        material_type = (data.get("type") or "link").strip().lower()
        size_bytes = data.get("sizeBytes")

        # --- file upload path -------------------------------------------------
        # The host's file picker posts multipart/form-data; the stored material
        # then points at a signed file download on this API rather than a URL.
        upload = request.FILES.get("file") if hasattr(request, "FILES") else None
        if upload is not None:
            material_id = f"mat_{uuid.uuid4().hex}"
            try:
                stored = _store_upload(upload, material_id)
            except ValueError as exc:
                if str(exc) == "TOO_LARGE":
                    limit_mb = settings.MATERIAL_MAX_BYTES // (1024 * 1024)
                    return _bad(f"That file is larger than the {limit_mb} MB limit.")
                raise

            import mimetypes
            guessed, _ = mimetypes.guess_type(upload.name or "")
            material_type = material_type if material_type != "link" else ("pdf" if (guessed or "").endswith("/pdf") else "doc")
            material = SessionMaterial.objects.create(
                id=material_id,
                session=session,
                title=(title or os.path.basename(stored["original"]))[:255],
                file_url="",  # replaced at once with a signed absolute URL
                file_type=material_type[:64],
                file_size_bytes=stored["size"],
                uploaded_by=request.user.id,
                created_at=timezone.now(),
            )
            signed = _material_file_token(material.id)
            relative = f"/api/v1/classrooms/sessions/{session_id}/materials/{material.id}/file/?t={signed}"
            material.file_url = request.build_absolute_uri(relative)[:1024]
            material.save(update_fields=["file_url"])
            return Response(
                SessionMaterialSerializer(material).data,
                status=status.HTTP_201_CREATED,
            )

        if not title:
            return _bad("Give the resource a title.")
        if not url:
            return _bad("A link is required.")
        if material_type not in MATERIAL_TYPES:
            return _bad(
                f"Unknown material type '{material_type}'. "
                f"Use one of: {', '.join(MATERIAL_TYPES)}."
            )
        try:
            URLValidator(schemes=["http", "https"])(url)
        except DjangoValidationError:
            return _bad("Enter a full http(s) link, e.g. https://example.com/notes.pdf")

        if size_bytes in (None, ""):
            size_bytes = None
        else:
            try:
                size_bytes = int(size_bytes)
            except (TypeError, ValueError):
                return _bad("sizeBytes must be a whole number of bytes.")
            if size_bytes < 0:
                return _bad("sizeBytes cannot be negative.")

        material = SessionMaterial.objects.create(
            id=f"mat_{uuid.uuid4().hex}",
            session=session,
            title=title[:255],
            file_url=url[:1024],
            file_type=material_type[:64],
            file_size_bytes=size_bytes,
            uploaded_by=request.user.id,
            created_at=timezone.now(),
        )
        return Response(
            SessionMaterialSerializer(material).data,
            status=status.HTTP_201_CREATED,
        )


class SessionMaterialFileView(APIView):
    """
    Stream one uploaded material to the person holding its signed link.

    Authentication here is the material token in the query string rather than
    the usual header: the modal opens these files with an anchor, and anchor
    navigation cannot attach an Authorization header. The token is signed with
    SECRET_KEY and scoped to a single material id, so possession of the link is
    what authorises the download -- exactly like a presigned object URL.
    """
    permission_classes = []  # the signed token in ?t= is the credential

    def get(self, request, session_id, material_id):
        raw = request.query_params.get("t", "")
        if not raw:
            return Response(
                {"error": {"code": "UNAUTHORIZED",
                           "message": "Missing material access token."}},
                status=status.HTTP_401_UNAUTHORIZED,
            )
        try:
            claims = jwt.decode(
                raw, settings.SECRET_KEY, algorithms=["HS256"]
            )
        except jwt.InvalidTokenError:
            return Response(
                {"error": {"code": "UNAUTHORIZED",
                           "message": "This file link is no longer valid."}},
                status=status.HTTP_401_UNAUTHORIZED,
            )
        if claims.get("typ") != "material-file" or claims.get("mat") != material_id:
            return Response(
                {"error": {"code": "UNAUTHORIZED",
                           "message": "This file link belongs to a different resource."}},
                status=status.HTTP_401_UNAUTHORIZED,
            )
        try:
            material = SessionMaterial.objects.get(id=material_id, session_id=session_id)
        except SessionMaterial.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": "Material not found."}},
                status=status.HTTP_404_NOT_FOUND,
            )

        root = os.path.abspath(str(settings.MATERIALS_ROOT))
        matches = [
            name for name in os.listdir(root)
            if name == f"fs_{material_id}" or name.startswith(f"fs_{material_id}.")
        ] if os.path.isdir(root) else []
        if len(matches) != 1:
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": "The file is not available."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        path = os.path.abspath(os.path.join(root, matches[0]))


def _stream_file_any(path, request):
    """_stream_file with the file's own content type instead of a fixed MP4."""
    import mimetypes
    guessed, _ = mimetypes.guess_type(path)
    response = _stream_file(path, request)
    response["Content-Type"] = guessed or "application/octet-stream"
    return response


class SessionMaterialDetailView(APIView):
    """DELETE a single resource; the sharer, the host, an admin or a lecturer."""
    permission_classes = [IsAuthenticated]

    def delete(self, request, session_id, material_id):
        session, error = _session_or_404(session_id)
        if error:
            return error

        try:
            material = SessionMaterial.objects.get(id=material_id, session=session)
        except SessionMaterial.DoesNotExist:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": f"Material '{material_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND,
            )

        if not _can_manage_materials(request.user, session) and material.uploaded_by != request.user.id:
            return Response(
                {"error": {"code": "FORBIDDEN", "message": "Only the host can remove materials."}},
                status=status.HTTP_403_FORBIDDEN,
            )

        material.delete()
        return Response({"deleted": True, "id": material_id})


# --- recordings -----------------------------------------------------------


def _local_file(rec):
    """
    Absolute path of this recording's MP4 on this machine, or ``None``.

    Only a basename is ever stored on the row, so a row written by hand cannot
    point at ``../../etc/somewhere`` -- the join is rebuilt from the root every
    time rather than trusted.
    """
    if not rec.file_path:
        return None
    root = os.path.abspath(str(settings.RECORDINGS_ROOT))
    path = os.path.abspath(os.path.join(root, os.path.basename(rec.file_path)))
    if os.path.dirname(path) != root:
        return None
    return path


def _recording_payload(rec, request=None, user=None) -> dict:
    path = _local_file(rec)
    on_disk = bool(path and os.path.exists(path))
    playable = rec.status == "complete" and on_disk

    # Watch links are minted for anyone who may see the shelf; download links
    # only for the people running the class. A media element cannot send an
    # Authorization header, so without a signed link the player has no. See
    # recording_links for why the URL carries the credential.
    play_url = recording_links.file_url(request, rec.session_id, rec.id, recording_links.MODE_PLAY) if playable else None
    can_download = bool(
        playable
        and user is not None
        and getattr(user, "is_authenticated", False)
        and can_moderate(user, rec.session)
    )
    download_url = (
        recording_links.file_url(request, rec.session_id, rec.id, recording_links.MODE_DOWNLOAD)
        if can_download else None
    )

    return {
        "id": rec.id,
        "sessionId": rec.session_id,
        "title": rec.class_title,
        "status": rec.status,
        # The UI draws a pulsing dot for these and polls while one is live.
        "isLive": rec.status in egress.LIVE_STATUSES,
        "canPlay": playable,
        # Normalised on the way out too: a row written before the wording was
        # mapped still holds the worker's own sentence.
        "error": egress.friendly_error((rec.metadata or {}).get("error")) or None,
        "startedAt": rec.started_at.isoformat() if rec.started_at else None,
        "endedAt": rec.ended_at.isoformat() if rec.ended_at else None,
        # Normalised on read as well as on write: rows saved before the unit
        # was handled still carry a raw nanosecond figure.
        "durationSeconds": egress.as_seconds(rec.duration_seconds),
        "sizeBytes": rec.size_bytes,
        "playUrl": play_url,
        "downloadUrl": download_url,
        # Whether *this* caller may save and pass the file on.
        "canDownload": can_download,
        "fileName": os.path.basename(rec.file_path) if rec.file_path else None,
        # Kept for older clients that read fileUrl; it is the watch link.
        "fileUrl": play_url,
    }


def _apply_snapshot(rec, info) -> None:
    """Fold an ``EgressInfo`` into the row: status, timing, size, error."""
    snap = egress.snapshot(info)
    if snap["status"] and snap["status"] != "unknown":
        rec.status = snap["status"]
    if snap["duration"] is not None:
        rec.duration_seconds = snap["duration"]
    if rec.status in ("complete", "failed", "aborted", "limit_reached") and not rec.ended_at:
        rec.ended_at = timezone.now()
    if snap["error"]:
        rec.metadata = {**(rec.metadata or {}), "error": snap["error"]}

    if rec.status == "complete":
        path = _local_file(rec)
        if path and os.path.exists(path):
            # The file on disk is the authority on how big it ended up.
            rec.size_bytes = os.path.getsize(path)
        elif snap["size"] is not None:
            rec.size_bytes = snap["size"]
    elif snap["size"] is not None:
        rec.size_bytes = snap["size"]

    rec.save()


def _startup_expired(rec) -> bool:
    if not rec.started_at:
        return False
    return (timezone.now() - rec.started_at).total_seconds() >= STARTUP_TIMEOUT_SECONDS


def _abandon_start(rec) -> None:
    """
    Give up on a pipeline that never started capturing.

    Room composite recording begins on the first published track, so a class
    where nobody turns on a microphone or camera leaves the worker waiting in
    EGRESS_STARTING indefinitely -- the row that reads to a host as "stuck on
    starting". Nothing will ever be written to that file, so the row is closed
    with the reason, and the job is stopped so it does not sit in the worker's
    queue forever.
    """
    rec.status = "failed"
    rec.ended_at = rec.ended_at or timezone.now()
    rec.metadata = {
        **(rec.metadata or {}),
        "error": egress.NOTHING_TO_RECORD_MESSAGE,
    }
    rec.save()
    if not rec.egress_id:
        return
    try:
        egress.stop(rec.egress_id)
    except (egress.RecordingUnavailable, egress.RecordingRejected):
        # The row already tells the truth; a worker that cannot answer is not
        # allowed to hold the shelf open.
        pass


def _parse_moment(value):
    """Parse an ISO timestamp we wrote into metadata, tolerating junk."""
    if not value:
        return None
    try:
        moment = datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None
    return moment if timezone.is_aware(moment) else timezone.make_aware(moment)


def _autosave_empty_room(rec) -> None:
    """
    Finalise a recording whose class has nobody left in it.

    A room-composite MP4 is written by the worker, not by the host's laptop, so
    nothing about the host closing the lid stops it: the file keeps growing and
    is never finalised into something a person can watch. That is the case this
    covers -- host gone, class never ended.

    The rule is deliberately slow. Losing a connection for a few seconds is
    normal and must not end a lecture, so the room is only asked about once
    every ``PRESENCE_CHECK_SECONDS`` and the take only closes after it has been
    empty for ``EMPTY_ROOM_GRACE_SECONDS``. A reconnect inside that window
    clears the clock and the recording simply carries on.

    When the question cannot be answered (old server, worker restarting), the
    recording is left exactly as it is: a transient failure must never look
    like an empty class.
    """
    if rec.status != "active" or not rec.egress_id:
        return

    now = timezone.now()
    meta = dict(rec.metadata or {})

    checked = _parse_moment(meta.get("presence_checked_at"))
    if checked and (now - checked).total_seconds() < PRESENCE_CHECK_SECONDS:
        return

    media = egress.room_media(rec.session_id)
    if media is None:
        return

    meta["presence_checked_at"] = now.isoformat()

    if media.get("people", 0) > 0:
        meta.pop("empty_since", None)
        rec.metadata = meta
        rec.save(update_fields=["metadata"])
        return

    empty_since = _parse_moment(meta.get("empty_since"))
    if empty_since is None:
        meta["empty_since"] = now.isoformat()
        rec.metadata = meta
        rec.save(update_fields=["metadata"])
        return

    if (now - empty_since).total_seconds() < EMPTY_ROOM_GRACE_SECONDS:
        rec.metadata = meta
        rec.save(update_fields=["metadata"])
        return

    try:
        info = egress.stop(rec.egress_id)
    except (egress.RecordingUnavailable, egress.RecordingRejected):
        # The row keeps counting from the same empty_since; the next read tries
        # again. Nothing is marked saved that was not stopped.
        return

    _apply_snapshot(rec, info)
    meta.pop("empty_since", None)
    meta["auto_saved"] = True
    rec.metadata = meta
    rec.save(update_fields=["metadata"])


def _refresh(rec):
    """
    Pull the worker's view of a recording that is still running.

    Reads never fail because the worker is down: the list still matters to a
    student who just wants to watch yesterday's lecture, and the last known
    state beats an error page. *Starting* and *stopping* are the opposite --
    they fail closed, because a write that did not happen must not be
    remembered as one that did.
    """
    if rec.status not in egress.LIVE_STATUSES or not rec.egress_id:
        return rec
    try:
        info = egress.status(rec.egress_id)
    except egress.RecordingUnavailable:
        return rec  # transient: keep what we know rather than 503 a read

    if info:
        _apply_snapshot(rec, info)
        if rec.status == "starting" and _startup_expired(rec):
            _abandon_start(rec)
        elif rec.status == "active":
            _autosave_empty_room(rec)
        return rec

    # The worker has no memory of this id (fresh Redis, rebuilt container).
    # A file on disk means it finished; nothing on disk means it is gone.
    path = _local_file(rec)
    if path and os.path.exists(path):
        _apply_snapshot(rec, {"status": "EGRESS_COMPLETE"})
    else:
        rec.status = "failed"
        rec.metadata = {
            **(rec.metadata or {}),
            "error": "The recording worker no longer reports this recording.",
        }
        if not rec.ended_at:
            rec.ended_at = timezone.now()
        rec.save()
    return rec


def _stream_file(path, request):
    """
    Serve an MP4, honouring ``Range``.

    Django ships no range support, and a lecture is watched with a seek bar: a
    browser that is never offered a 206 downloads the whole file before it can
    jump to the middle. Only the single-range case matters here -- multi-range
    requests fall back to the whole file, which is always correct.
    """
    size = os.path.getsize(path)
    start, end, code = 0, size - 1, 200
    range_header = request.META.get("HTTP_RANGE", "")

    if range_header.startswith("bytes="):
        spec = range_header.split("=", 1)[1].split(",")[0].strip()
        first, sep, last = spec.partition("-")
        try:
            if not sep:
                raise ValueError("empty range")
            if first:
                start = int(first)
                end = int(last) if last else size - 1
            else:
                start = max(0, size - int(last))  # "bytes=-500": the tail
            if start < 0 or start >= size or end < start:
                return Response(
                    {"error": {"code": "RANGE_NOT_SATISFIABLE",
                               "message": "That byte range lies outside the file."}},
                    status=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE,
                    headers={"Content-Range": f"bytes */{size}"},
                )
            end = min(end, size - 1)
            code = status.HTTP_206_PARTIAL_CONTENT
        except ValueError:
            # An unparseable range is ignored rather than rejected: serving
            # the whole file keeps playback working through a malformed header.
            start, end, code = 0, size - 1, 200

    handle = open(path, "rb")
    handle.seek(start)
    length = end - start + 1

    def chunks():
        try:
            remaining = length
            while remaining > 0:
                block = handle.read(min(64 * 1024, remaining))
                if not block:
                    break
                remaining -= len(block)
                yield block
        finally:
            handle.close()

    response = StreamingHttpResponse(chunks(), content_type="video/mp4", status=code)
    response["Content-Length"] = str(length)
    response["Accept-Ranges"] = "bytes"
    response["Content-Disposition"] = f'inline; filename="{os.path.basename(path)}"'
    if code == status.HTTP_206_PARTIAL_CONTENT:
        response["Content-Range"] = f"bytes {start}-{end}/{size}"
    return response


class SessionRecordingsView(APIView):
    """
    List this session's recordings, or start one.

    GET  -> { "documents": [ ... ] }   anyone signed in: the class watches replays
    POST -> { "document": { ... } }    host, lecturer or co-host only

    Starting is the one call here that touches infrastructure, so it is the one
    that fails closed: feature off -> 503, not a moderator -> 403, session over
    -> 400, worker unreachable -> 503. No path reports a recording that is not
    actually running.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        require_feature("recordings")
        session, error = _session_or_404(session_id)
        if error:
            return error
        rows = list(session.recordings.order_by("-created_at"))
        for row in rows:
            _refresh(row)
        return Response({
            "documents": [_recording_payload(r, request, request.user) for r in rows]
        })

    def post(self, request, session_id):
        require_feature("recordings")
        session, error = _session_or_404(session_id)
        if error:
            return error

        # Same rule as sharing materials: whoever is running the room may
        # record it, which includes a co-host the host appointed.
        if not can_moderate(request.user, session):
            return Response(
                {"error": {"code": "FORBIDDEN",
                           "message": "Only the host can record this session."}},
                status=status.HTTP_403_FORBIDDEN,
            )

        if not session.is_active:
            return _bad("This session is not active, so there is nothing to record.")

        # One recording at a time. Pressing record again returns the one that
        # is already running instead of starting a second file nobody asked for.
        running = session.recordings.filter(status__in=egress.LIVE_STATUSES).first()
        if running is not None:
            _refresh(running)
            if running.status in egress.LIVE_STATUSES:
                return Response(_recording_payload(running, request, request.user))

        # A composite recording begins on the first published track. Starting
        # one over a room where nobody is sending audio or video produces a job
        # that waits in "starting" until someone stops it -- which is exactly
        # what a host experiences as a recording stuck on starting. Say so now,
        # while they are still in the room to switch a microphone on.
        media = egress.room_media(session.id)
        if media is not None and media.get("publishing", 0) == 0:
            return Response(
                {"error": {
                    "code": "NOTHING_TO_RECORD",
                    "message": (
                        "Nobody in this class is sending audio or video yet, so "
                        "there is nothing to record. Turn on your microphone or "
                        "camera, then start the recording."
                    ),
                }},
                status=status.HTTP_400_BAD_REQUEST,
            )

        filename = f"{session.id}-{int(time.time())}.mp4"
        container_path = f"{settings.RECORDINGS_CONTAINER_PATH.rstrip('/')}/{filename}"
        try:
            os.makedirs(settings.RECORDINGS_ROOT, exist_ok=True)
            info = egress.start_room_composite(session.id, container_path)
        except egress.RecordingRejected as exc:
            return Response(
                {"error": {"code": "RECORDING_REJECTED", "message": exc.message}},
                status=status.HTTP_400_BAD_REQUEST,
            )
        except egress.RecordingUnavailable as exc:
            return Response(
                {"error": {"code": "RECORDING_UNAVAILABLE", "message": exc.message}},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )

        egress_id = info.get("egress_id") or ""
        if not egress_id:
            # Accepted at the transport layer but nothing is recording. Fail
            # closed rather than store a row that will never finish.
            return Response(
                {"error": {"code": "RECORDING_UNAVAILABLE",
                           "message": "The recording worker did not accept the request."}},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )

        snap = egress.snapshot(info)
        recording = Recording.objects.create(
            id=f"rec_{uuid.uuid4().hex}",
            session=session,
            egress_id=egress_id,
            room_id=session.id,
            lecturer_id=session.host_id or request.user.id,
            class_title=session.title,
            status=snap["status"] or "starting",
            file_path=filename,
            started_at=timezone.now(),
            created_at=timezone.now(),
        )
        return Response(
            _recording_payload(recording, request, request.user),
            status=status.HTTP_201_CREATED,
        )


class SessionRecordingDetailView(APIView):
    """
    GET one recording's current state; the UI polls this while it runs.

    A read refreshes a live row from the worker but never fails because the
    worker is down -- see ``_refresh``.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id, recording_id):
        require_feature("recordings")
        session, error = _session_or_404(session_id)
        if error:
            return error
        recording = session.recordings.filter(id=recording_id).first()
        if recording is None:
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": f"Recording '{recording_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        _refresh(recording)
        return Response(_recording_payload(recording, request, request.user))


class SessionRecordingStopView(APIView):
    """
    POST to finish a recording; host, lecturer or co-host.

    Stopping something that has already stopped is a no-op, not an error --
    two people reaching for the same button should not produce a red banner.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request, session_id, recording_id):
        require_feature("recordings")
        session, error = _session_or_404(session_id)
        if error:
            return error
        recording = session.recordings.filter(id=recording_id).first()
        if recording is None:
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": f"Recording '{recording_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        if not can_moderate(request.user, session):
            return Response(
                {"error": {"code": "FORBIDDEN",
                           "message": "Only the host can stop a recording."}},
                status=status.HTTP_403_FORBIDDEN,
            )

        if recording.status not in egress.LIVE_STATUSES:
            _refresh(recording)
            return Response(_recording_payload(recording, request, request.user))

        # A stop that lands in the first second or two -- exactly what happens
        # when someone presses record and then stop to see if it worked -- used
        # to reach the worker while its pipeline was still starting, which
        # aborts the take. Wait for capture before asking it to end.
        if recording.status == "starting":
            info = egress.wait_for_capture(recording.egress_id)
            if info:
                _apply_snapshot(recording, info)
                if recording.status not in egress.LIVE_STATUSES:
                    return Response(_recording_payload(recording, request, request.user))

        try:
            info = egress.stop(recording.egress_id)
        except egress.RecordingRejected as exc:
            if egress.is_pipeline_abort(exc.message):
                # Nothing was ever captured, so there is no take to finalise and
                # no point quoting the worker at the host. The row says what
                # happened in plain words; the shelf stays honest either way.
                recording.status = "failed"
                recording.ended_at = recording.ended_at or timezone.now()
                recording.metadata = {
                    **(recording.metadata or {}),
                    "error": egress.PIPELINE_ABORT_MESSAGE,
                }
                recording.save()
                return Response(_recording_payload(recording, request, request.user))
            return Response(
                {"error": {"code": "RECORDING_REJECTED", "message": exc.message}},
                status=status.HTTP_400_BAD_REQUEST,
            )
        except egress.RecordingUnavailable as exc:
            return Response(
                {"error": {"code": "RECORDING_UNAVAILABLE", "message": exc.message}},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )

        _apply_snapshot(recording, info)
        # The worker answers EGRESS_ENDING; the file is finalised afterwards,
        # so the client keeps polling the detail route until it reads complete.
        return Response(_recording_payload(recording, request, request.user))


class MyRecordingsView(APIView):
    """
    GET /api/v1/classrooms/recordings/

    Every recording in the classes this user runs, newest first, wrapped in
    ``documents``.

    The dashboard's Records area needs this in one request. Asking per class
    would be a request per class on every dashboard load, and the whole list
    is one indexed query on a small table (a recording row is a few hundred
    bytes; the MP4 is not in the database).

    Only the people who run a class see its recordings here, which is also what
    makes the download links in the payload host-only: they are minted per
    caller, and a participant never receives one.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        require_feature("recordings")

        hosted = Session.objects.filter(
            Q(host=request.user) | Q(lecturer=request.user)
        ).values("id")
        cohosted = CoHost.objects.filter(
            user=request.user, is_active=True
        ).values("session_id")
        sessions = Session.objects.filter(Q(id__in=Subquery(hosted)) | Q(id__in=Subquery(cohosted)))

        rows = list(
            Recording.objects
            .filter(session__in=sessions)
            .select_related("session")
            .order_by("-created_at")[:RECORDINGS_FEED_LIMIT]
        )
        # Only live rows cost a worker call; a finished one is answered from
        # the row, so opening the dashboard does not fan out to egress.
        for row in rows:
            _refresh(row)

        return Response({
            "documents": [_recording_payload(r, request, request.user) for r in rows]
        })


class SessionRecordingFileView(APIView):
    """
    Stream the MP4 for one recording.

    Authenticated two ways, because the callers are different kinds of client:

    * ``Authorization: Bearer`` -- an API client that can set headers; or
    * ``?t=<signed link>`` -- a media element, a download link or the phone's
      download manager, none of which can send a header. The link names one
      recording and one purpose (see recording_links), so it cannot be edited
      into access to another recording, and a play link cannot be turned into a
      forced download or vice versa.
    """
    # AllowAny, not IsAuthenticated: the credential may be the signed link in
    # the query string, which DRF's authenticators never look at. The view then
    # requires one of the two -- see the check below.
    permission_classes = [AllowAny]

    def get(self, request, session_id, recording_id):
        require_feature("recordings")
        # AllowAny above, so the check happens here: a request needs either a
        # valid link for *this* recording, or a signed-in user. A request with
        # neither is refused -- the file is never served on possession of a URL
        # alone.
        link = recording_links.verify(
            request.query_params.get("t") or "", session_id, recording_id
        )
        signed_in = bool(getattr(request.user, "is_authenticated", False))
        if link is None and not signed_in:
            return Response(
                {
                    "error": {
                        "code": "LINK_REQUIRED",
                        "message": (
                            "This recording needs a fresh link or a signed-in session. "
                            "Open the class to get one."
                        ),
                    }
                },
                status=status.HTTP_401_UNAUTHORIZED,
            )

        session, error = _session_or_404(session_id)
        if error:
            return error
        recording = session.recordings.filter(id=recording_id).first()
        if recording is None:
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": f"Recording '{recording_id}' not found."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        path = _local_file(recording)
        if not path or not os.path.exists(path):
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": "The video file for this recording is not available."}},
                status=status.HTTP_404_NOT_FOUND,
            )

        response = _stream_file(path, request)
        # Inline for watching, attachment for saving. It comes from the link's
        # purpose, so a shared watch link cannot silently become a file drop.
        mode = (link or {}).get("mode") or recording_links.MODE_PLAY
        filename = os.path.basename(recording.file_path or "recording.mp4")
        response["Content-Disposition"] = recording_links.content_disposition(filename, mode)
        return response
