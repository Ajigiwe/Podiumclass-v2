"""
Admin user management.

The control panel needs to answer three questions about an account: who is it,
what may it do, and is it still allowed in. Everything here is admin-only and
reads authority from the database, so a demotion lands on the account's next
request instead of whenever its token expires.

Two guards matter more than the happy path:

* an admin cannot demote or disable **themselves**, which would lock the last
  operator out of the panel mid-session;
* the deployment's configured ``admin_email`` cannot be demoted or disabled by
  anybody else, so the superadmin account cannot be quietly taken over or
  stranded by another admin.
"""

from django.db.models import Q
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.models import SystemSetting

from .models import User
from .permissions import IsAdmin
from .serializers import UserSerializer

VALID_ROLES = {"student", "lecturer", "admin"}
VALID_STATUSES = {"active", "disabled"}

# Enough for a class register or an institution list, small enough to stay one
# request. The client searches server-side rather than paging through it.
MAX_USERS = 500


def _configured_admin_email() -> str:
    return (SystemSetting.get_setting("admin_email", "") or "").strip().lower()


class AdminUserListView(APIView):
    """
    GET /api/v1/accounts/users/?q=&role=

    Every account, newest first, with an optional substring search over name,
    email and index number. Answers as a Document Object collection, like every
    other list endpoint.
    """

    permission_classes = [IsAuthenticated, IsAdmin]

    def get(self, request):
        q = (request.query_params.get("q") or "").strip()
        role = (request.query_params.get("role") or "").strip().lower()

        qs = User.objects.all().order_by("-created_at")
        if q:
            qs = qs.filter(
                Q(full_name__icontains=q)
                | Q(email__icontains=q)
                | Q(index_number__icontains=q)
            )
        if role in VALID_ROLES:
            qs = qs.filter(role=role)

        users = qs[:MAX_USERS]
        return Response([UserSerializer(user).data for user in users])


class AdminUserDetailView(APIView):
    """
    PATCH /api/v1/accounts/users/<user_id>/

    Partial update of one account: ``role``, ``canCreateClasses``, ``status``
    and ``isVerified``. Only the fields present in the body are touched, so the
    panel can flip one switch without sending the whole record back.
    """

    permission_classes = [IsAuthenticated, IsAdmin]

    def get(self, request, user_id):
        user = User.objects.filter(pk=user_id).first()
        if user is None:
            return Response(
                {"code": "NOT_FOUND", "message": "No such user."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(UserSerializer(user).data)

    def patch(self, request, user_id):
        target = User.objects.filter(pk=user_id).first()
        if target is None:
            return Response(
                {"code": "NOT_FOUND", "message": "No such user."},
                status=status.HTTP_404_NOT_FOUND,
            )

        data = request.data
        is_self = target.id == request.user.id
        is_protected = bool(_configured_admin_email()) and (
            target.email.lower() == _configured_admin_email()
        )

        # --- guards -------------------------------------------------------
        losing_admin = False
        if "role" in data:
            new_role = str(data.get("role") or "").strip().lower()
            if new_role not in VALID_ROLES:
                return Response(
                    {
                        "code": "INVALID_ROLE",
                        "message": "Role must be one of: student, lecturer, admin.",
                    },
                    status=status.HTTP_400_BAD_REQUEST,
                )
            losing_admin = target.role == "admin" and new_role != "admin"
        if "status" in data:
            new_status = str(data.get("status") or "").strip().lower()
            if new_status not in VALID_STATUSES:
                return Response(
                    {
                        "code": "INVALID_STATUS",
                        "message": "Status must be 'active' or 'disabled'.",
                    },
                    status=status.HTTP_400_BAD_REQUEST,
                )
            if new_status == "disabled" and is_self:
                return Response(
                    {
                        "code": "SELF_DISABLE",
                        "message": "You cannot disable your own account.",
                    },
                    status=status.HTTP_400_BAD_REQUEST,
                )

        if is_self and losing_admin:
            return Response(
                {
                    "code": "SELF_DEMOTE",
                    "message": "You cannot remove your own admin role.",
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        if is_protected and not is_self and (
            losing_admin or str(data.get("status") or "").strip().lower() == "disabled"
        ):
            return Response(
                {
                    "code": "PROTECTED_ACCOUNT",
                    "message": "The configured superadmin account cannot be demoted or disabled.",
                },
                status=status.HTTP_403_FORBIDDEN,
            )

        # --- apply --------------------------------------------------------
        if "role" in data:
            target.role = str(data.get("role")).strip().lower()
            # Staff access tracks the role: an admin is staff, and somebody
            # demoted out of admin is not. Their module-level rights
            # (can_create_classes) are deliberately untouched here, because
            # that is a separate decision made with its own switch.
            target.is_staff = target.role == "admin"
            target.is_superuser = target.role == "admin"

        if "canCreateClasses" in data:
            target.can_create_classes = bool(data.get("canCreateClasses"))

        if "status" in data:
            target.status = str(data.get("status")).strip().lower()
            target.is_active = target.status == "active"

        if "isVerified" in data:
            target.is_verified = bool(data.get("isVerified"))

        target.save()
        return Response(UserSerializer(target).data)
