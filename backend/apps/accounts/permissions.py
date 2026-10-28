"""
Role-based permission classes shared across the Podium API.

Authority is always read from the database (``request.user.role``), never from
the JWT ``role`` claim alone, so demoting a user takes effect on their next
request instead of when their token expires.
"""

from rest_framework import permissions

ROLE_LECTURER = "lecturer"
ROLE_ADMIN = "admin"
_LECTURER_ROLES = {ROLE_LECTURER, ROLE_ADMIN}


def is_lecturer(user) -> bool:
    """
    True for lecturer or admin, i.e. anyone who may host a class.

    An admin can also grant the ability to one account through
    ``can_create_classes`` without promoting it to lecturer. That grant is
    additive, so revoking it takes effect on the user's next request (the flag
    is read from the database, never from the token).
    """
    return bool(
        user
        and getattr(user, "is_authenticated", False)
        and (
            user.role in _LECTURER_ROLES
            or getattr(user, "can_create_classes", False)
        )
    )


def is_admin(user) -> bool:
    """True for admin only."""
    return bool(user and getattr(user, "is_authenticated", False) and user.role == ROLE_ADMIN)


class IsLecturerOrAdmin(permissions.BasePermission):
    """Allow only authenticated lecturers or admins (class hosts)."""

    message = {"code": "LECTURER_REQUIRED", "message": "Only lecturers or admins may perform this action."}

    def has_permission(self, request, view):
        return bool(request.user and request.user.is_authenticated and is_lecturer(request.user))


class IsAdmin(permissions.BasePermission):
    """Allow only authenticated admins."""

    message = {"code": "ADMIN_REQUIRED", "message": "Only admins may perform this action."}

    def has_permission(self, request, view):
        return bool(request.user and request.user.is_authenticated and is_admin(request.user))


class IsSelfOrLecturer(permissions.BasePermission):
    """
    Allow the request's own user, or any lecturer/admin.

    Used on per-user resources (wallet, attendance) where a student may act on
    themselves but staff may act on anyone. Views must supply ``object_user``
    via ``get_object_user()``; if the view does not, only self-access is
    checked against an explicit ``userId``/``studentId`` parameter.
    """

    message = {"code": "FORBIDDEN", "message": "You may only access your own records."}

    def has_permission(self, request, view):
        return bool(request.user and request.user.is_authenticated)

    def has_object_permission(self, request, view, obj):
        user = request.user
        if is_lecturer(user):
            return True
        # ``obj`` may be a User directly, or a model exposing a user FK.
        target = getattr(obj, "user", None) or getattr(obj, "student", None) or obj
        target_id = getattr(target, "id", None)
        return bool(target_id and target_id == user.id)
