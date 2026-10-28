"""
How many students attended a class, and how many of them count as present.

Three surfaces answer that question: the class list, the attendance API's
session rows, and the in-class roster. They read it from the same place so a
lecturer's badge cannot disagree with the summary it sits next to.

The rule has two halves, and the second one is the reason this module exists:

* when checks were fired, answering at least ``PRESENT_THRESHOLD`` of them is
  presence, and ignoring them is not;
* when no check was ever fired, there was nothing to answer, so everyone who
  joined the class was in it.

Without the second half a short class counted nobody present. The automated
checker's first check is due one interval after the start (fifteen minutes by
default), so a five-minute class fired none at all -- and a full room reported
zero attendance for a class that had just happened.
"""

from django.db.models import Count, Q

from .models import PRESENT_THRESHOLD, Attendance, Verification

# Names of the annotations added by ``session_count_annotations``. Looked up by
# name so a caller that forgot to annotate still gets a correct answer (from a
# query) instead of an AttributeError.
ATTENDEE_COUNT = "attendee_count"
VERIFIED_COUNT = "verified_count"
CHECKS_RAN_COUNT = "checks_ran_count"
SESSION_CHECKS = "session_checks"


def session_count_annotations() -> dict:
    """``.annotate(**session_count_annotations())`` for a Session queryset."""
    return {
        ATTENDEE_COUNT: Count("attendance_records", distinct=True),
        VERIFIED_COUNT: Count(
            "attendance_records",
            filter=Q(attendance_records__verification_percentage__gte=PRESENT_THRESHOLD),
            distinct=True,
        ),
        CHECKS_RAN_COUNT: Count("verifications", distinct=True),
    }


def checks_ran_for(session) -> bool:
    """Whether any verification check was ever fired in this class."""
    return Verification.objects.filter(session=session).exists()


def attended_the_class(verification_percentage: int, checks_ran: bool) -> bool:
    """The rule itself, for a single record already in hand."""
    if not checks_ran:
        return True
    return verification_percentage >= PRESENT_THRESHOLD


def count_attendees(session) -> int:
    """Students with an attendance record in this class."""
    annotation = getattr(session, ATTENDEE_COUNT, None)
    if annotation is not None:
        return annotation
    return session.attendance_records.count()


def count_present(session) -> int:
    """Students who count as present in this class."""
    checks_ran = getattr(session, CHECKS_RAN_COUNT, None)
    if checks_ran is None:
        checks_ran = 1 if checks_ran_for(session) else 0
    if not checks_ran:
        return count_attendees(session)

    verified = getattr(session, VERIFIED_COUNT, None)
    if verified is not None:
        return verified
    return session.attendance_records.filter(
        verification_percentage__gte=PRESENT_THRESHOLD
    ).count()


def checks_ran_for_session_id(session_id) -> bool:
    """``checks_ran_for`` for a record whose session has not been loaded."""
    return Verification.objects.filter(session_id=session_id).exists()


def session_checks_count(obj) -> int:
    """
    How many checks a student's own attendance row could have answered.

    Uses the ``session_checks`` annotation when the queryset carries it, and
    falls back to a query otherwise.
    """
    annotation = getattr(obj, SESSION_CHECKS, None)
    if annotation is not None:
        return annotation
    return Verification.objects.filter(session_id=obj.session_id).count()


def my_records_annotations() -> dict:
    """``.annotate(**my_records_annotations())`` on a student's own Attendance rows."""
    return {SESSION_CHECKS: Count("session__verifications")}
