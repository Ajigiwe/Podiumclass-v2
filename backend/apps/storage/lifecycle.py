"""
Closing recordings cleanly when the thing that was being recorded goes away.

The classroom owns the session; this module owns the MP4 rows attached to it.
They are split so the classroom can end or empty a class without importing the
recording views, and so the "finish every live take" rule exists in one place.

Why it matters: Egress writes the file from inside the room. If the room is
deleted while the worker is still recording, the pipeline is killed mid-write
and the take is lost -- the host ended the class, and there is no video. Asking
the worker to stop *first* makes it finalise the container, which is the same
path the Stop button takes.
"""
from django.utils import timezone

from . import egress
from .models import Recording


def finalise_room_recordings(room_id: str) -> int:
    """
    Finish every live recording for one room and return how many were asked.

    Failures are tolerated per row: the class is ending either way, and a worker
    that cannot be reached must not stop the host from ending it. The row is
    left as it was in that case, so the next status read can still try.
    """
    rows = list(
        Recording.objects.filter(room_id=room_id, status__in=egress.LIVE_STATUSES)
    )
    finished = 0
    for rec in rows:
        if not rec.egress_id:
            continue
        try:
            info = egress.stop(rec.egress_id)
        except (egress.RecordingUnavailable, egress.RecordingRejected):
            continue

        snap = egress.snapshot(info)
        if snap["status"] and snap["status"] != "unknown":
            rec.status = snap["status"]
        if snap["duration"] is not None:
            rec.duration_seconds = snap["duration"]
        if rec.status not in egress.LIVE_STATUSES and not rec.ended_at:
            rec.ended_at = timezone.now()
        rec.save()
        finished += 1
    return finished


def finalise_session_recordings(session) -> int:
    """Finish every live recording attached to this session."""
    return finalise_room_recordings(session.id)
