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
import time
import uuid

from django.conf import settings
from django.core.exceptions import ValidationError as DjangoValidationError
from django.core.validators import URLValidator
from django.http import StreamingHttpResponse
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.classrooms.access import can_moderate
from apps.classrooms.models import Session
from apps.core.guards import require_feature
from . import egress
from .models import Recording, SessionMaterial
from .serializers import MATERIAL_TYPES, SessionMaterialSerializer


def _can_manage_materials(user, session) -> bool:
    """Host, lecturer, admin, or an active co-host -- see classrooms.access."""
    return can_moderate(user, session)


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


def _recording_payload(rec, request=None) -> dict:
    path = _local_file(rec)
    on_disk = bool(path and os.path.exists(path))
    return {
        "id": rec.id,
        "sessionId": rec.session_id,
        "title": rec.class_title,
        "status": rec.status,
        # The UI draws a pulsing dot for these and polls while one is live.
        "isLive": rec.status in egress.LIVE_STATUSES,
        "canPlay": rec.status == "complete" and on_disk,
        "error": (rec.metadata or {}).get("error") or None,
        "startedAt": rec.started_at.isoformat() if rec.started_at else None,
        "endedAt": rec.ended_at.isoformat() if rec.ended_at else None,
        "durationSeconds": rec.duration_seconds,
        "sizeBytes": rec.size_bytes,
        "fileUrl": (
            request.build_absolute_uri(
                f"/api/v1/classrooms/sessions/{rec.session_id}/recordings/{rec.id}/file/"
            )
            if (request is not None and rec.status == "complete" and on_disk) else None
        ),
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
        return Response({"documents": [_recording_payload(r, request) for r in rows]})

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
                return Response(_recording_payload(running, request))

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
        return Response(_recording_payload(recording, request), status=status.HTTP_201_CREATED)


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
        return Response(_recording_payload(recording, request))


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
            return Response(_recording_payload(recording, request))

        try:
            info = egress.stop(recording.egress_id)
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

        _apply_snapshot(recording, info)
        # The worker answers EGRESS_ENDING; the file is finalised afterwards,
        # so the client keeps polling the detail route until it reads complete.
        return Response(_recording_payload(recording, request))


class SessionRecordingFileView(APIView):
    """Stream the MP4 for one recording to anyone signed into the class."""
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
        path = _local_file(recording)
        if not path or not os.path.exists(path):
            return Response(
                {"error": {"code": "NOT_FOUND",
                           "message": "The video file for this recording is not available."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        return _stream_file(path, request)
