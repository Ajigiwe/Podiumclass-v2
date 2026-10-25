"""
Who is allowed to do what in a session.

Two deliberately different levels:

``is_session_authority``
    The people who own the session: host, lecturer, admin. Only they may end
    or delete it, change its settings, or appoint and withdraw co-hosts.

``can_moderate``
    Everyone running the room: the authority above *plus* active co-hosts.
    Mute All, Smart Mic, the permission policy, answering permission requests,
    sharing materials and triggering attendance checks all sit at this level.

Keeping the split in one module is the point: ``apps.classrooms``,
``apps.storage`` and ``apps.attendance`` all need the same answer, and a
hand-copied rule in three files is a rule that eventually differs in one of
them -- usually the one guarding something expensive.
"""

from apps.classrooms.models import CoHost, Session


def is_session_authority(user, session: Session) -> bool:
    """
    Host, lecturer or admin -- never a co-host.

    A co-host has full moderation parity but no authority over the session
    itself, so a host can never be locked out of their own room by someone
    they trusted with a mute button.
    """
    return user.role in ("admin", "lecturer") or session.host_id == user.id


def is_cohost(user, session: Session) -> bool:
    """True when this user is an active co-host of this session."""
    return CoHost.objects.filter(session=session, user=user, is_active=True).exists()


def can_moderate(user, session: Session) -> bool:
    """May this user run the room? Authority, or an active co-host."""
    if is_session_authority(user, session):
        return True
    return is_cohost(user, session)
