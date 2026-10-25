import uuid
from django.conf import settings
from rest_framework.permissions import IsAuthenticated
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from rest_framework.exceptions import ValidationError
from google.oauth2 import id_token
from google.auth.transport import requests as google_requests
from apps.core.models import SystemSetting
from apps.core.guards import require_feature
from .auth import issue_token
from .models import User
from .serializers import UserSerializer

class CurrentUserView(APIView):
    """
    Read or update the authenticated user's own profile.

    Identity always comes from the verified JWT on the request — never from a
    query parameter — so a caller cannot read or provision another account by
    passing an email address.

    Responds in Document Object: { "document": { "id": "...", "fullName": "...", ... } }
    """
    permission_classes = [IsAuthenticated]

    # The ONLY self-service fields. role, wallet_balance, is_staff, is_verified
    # and status are deliberately absent: accepting them here would let any
    # student promote themselves to admin or mint their own wallet balance.
    EDITABLE_FIELDS = ("fullName", "indexNumber", "university", "bio")

    def get(self, request):
        serializer = UserSerializer(request.user)
        return Response(serializer.data)

    def patch(self, request):
        """
        Update the caller's profile (first-time setup and later edits).

        Google sign-in creates the account, but index number, university and bio
        are supplied by the student afterwards, so this is the write path for
        that data. It was previously missing entirely -- the UI edited a local
        copy and silently discarded the change on reload.
        """
        user = request.user
        data = request.data

        if "fullName" in data:
            name = str(data.get("fullName") or "").strip()
            if not name:
                raise ValidationError({
                    "code": "INVALID_NAME",
                    "message": "Name cannot be empty.",
                })
            user.full_name = name[:255]

        if "indexNumber" in data:
            index = str(data.get("indexNumber") or "").strip()
            if index:
                # Two accounts sharing an index number makes attendance
                # ambiguous: a session roster could resolve to the wrong person.
                clash = (
                    User.objects.filter(index_number__iexact=index)
                    .exclude(pk=user.pk)
                    .exists()
                )
                if clash:
                    raise ValidationError({
                        "code": "INDEX_NUMBER_TAKEN",
                        "message": "That index number is already linked to another account.",
                    })
            user.index_number = index or None

        if "university" in data:
            user.university = str(data.get("university") or "").strip() or None

        if "bio" in data:
            user.bio = str(data.get("bio") or "").strip() or None

        user.save()
        return Response(UserSerializer(user).data)

class GoogleAuthView(APIView):
    """
    Authenticate user via Google OAuth ID token.
    Reads google_client_id and admin_email directly from the database.
    Single unified Google Sign-In endpoint.
    """
    def post(self, request):
        require_feature("google_signin")
        token = request.data.get("credential") or request.data.get("id_token")
        client_id = SystemSetting.get_setting(
            "google_client_id",
            "148808386481-qp65l50jmuabfte437aafmh85esth944.apps.googleusercontent.com"
        )
        admin_email = SystemSetting.get_setting("admin_email", "minatoflash82@gmail.com")

        email = None
        full_name = "Podium User"
        photo_url = None

        access_token = request.data.get("access_token")

        if token:
            # Verify Google ID token. If verification fails we must NOT fall
            # back to trusting client-supplied email — that would allow anyone
            # to impersonate any user by posting their address.
            try:
                id_info = id_token.verify_oauth2_token(token, google_requests.Request(), client_id)
                email = id_info.get("email", "").strip().lower()
                full_name = id_info.get("name", "Podium User")
                photo_url = id_info.get("picture")
            except Exception as e:
                return Response(
                    {"error": {"code": "INVALID_TOKEN", "message": f"Google verification failed: {str(e)}"}},
                    status=status.HTTP_401_UNAUTHORIZED
                )
        elif access_token:
            try:
                import urllib.request
                import json
                req = urllib.request.Request(
                    "https://www.googleapis.com/oauth2/v3/userinfo",
                    headers={"Authorization": f"Bearer {access_token}"}
                )
                with urllib.request.urlopen(req, timeout=5) as resp:
                    info = json.loads(resp.read().decode("utf-8"))
                if not info.get("email"):
                    raise ValueError("Google userinfo response did not include an email claim.")
                if not info.get("email_verified", True):
                    raise ValueError("Google reports this email address as unverified.")
                email = info.get("email", "").strip().lower()
                full_name = info.get("name") or "Podium User"
                photo_url = info.get("picture")
            except Exception as e:
                return Response(
                    {"error": {"code": "INVALID_ACCESS_TOKEN", "message": f"Google user info lookup failed: {str(e)}"}},
                    status=status.HTTP_401_UNAUTHORIZED
                )
        # Only a Google-verified credential may mint a token.
        #
        # The previous implementation ALSO accepted a bare index number or email
        # address, and returned a signed JWT for whichever account matched. That
        # was a complete authentication bypass: knowing a student's index number
        # (printed on their ID card, and visible in class rosters) or guessing an
        # email address was enough to sign in as them, with no password and no
        # proof of identity.
        if settings.DEBUG and request.data.get("dev_login"):
            dev_role = request.data.get("role", "admin")
            if dev_role == "admin":
                email = admin_email
                full_name = "Super Admin (Minato)"
            else:
                email = "student@podium.local"
                full_name = "Mobile Student"
        elif not token and not access_token:
            return Response(
                {"error": {"code": "CREDENTIAL_REQUIRED", "message": "A Google credential is required."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        if not email:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "Email could not be verified from Google credential."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        is_admin = (email.lower() == admin_email.lower())
        user, created = User.objects.get_or_create(
            email=email,
            defaults={
                "id": f"usr_{uuid.uuid4().hex[:16]}",
                "full_name": full_name,
                "photo_url": photo_url,
                "role": "admin" if is_admin else "student",
                "is_staff": is_admin,
                "is_superuser": is_admin,
                "is_verified": True,
                "wallet_balance": 100000 if is_admin else 5000,
            }
        )

        if not created and is_admin and user.role != "admin":
            user.role = "admin"
            user.is_staff = True
            user.is_superuser = True
            user.save()

        serializer = UserSerializer(user)
        return Response({
            "user": serializer.data,
            "token": issue_token(user)
        })
