import os
import re
import uuid

from django.conf import settings
from django.http import FileResponse
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

        if "phone" in data or "year" in data or "contact" in data:
            meta = dict(user.metadata or {})
            if "phone" in data or "contact" in data:
                meta["phone"] = str(data.get("phone") or data.get("contact") or "").strip()
            if "year" in data:
                meta["year"] = str(data.get("year") or "").strip()
            user.metadata = meta

        user.save()
        return Response(UserSerializer(user).data)

def _avatar_root():
    root = settings.AVATARS_ROOT
    os.makedirs(root, exist_ok=True)
    return root


def _avatar_files(user_id: str):
    """Every stored avatar file for one account (normally zero or one)."""
    root = _avatar_root()
    prefix = f"fs_{user_id}."
    return [
        os.path.join(root, name)
        for name in os.listdir(root)
        if name.startswith(prefix)
    ]


# Recognised by magic bytes rather than by the client's filename or declared
# content type: an upload called "me.png" that is really an HTML document must
# never be served back as an image from this origin.
_AVATAR_SIGNATURES = (
    (b"\xff\xd8\xff", ".jpg", "image/jpeg"),
    (b"\x89PNG\r\n\x1a\n", ".png", "image/png"),
)
_AVATAR_CONTENT_TYPES = {".jpg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
_SAFE_USER_ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


def _image_kind(head: bytes):
    """Return (suffix, content type) for a supported image, else None."""
    for signature, suffix, content_type in _AVATAR_SIGNATURES:
        if head.startswith(signature):
            return suffix, content_type
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return ".webp", "image/webp"
    return None


class ProfilePhotoView(APIView):
    """
    Upload, replace or clear the caller's profile picture.

    POST   multipart ``photo`` -> the updated user document
    DELETE                     -> the updated user document, picture removed

    The image lands on disk (``AVATARS_ROOT``) and the account stores only the
    absolute URL that serves it, so it survives sign-out, restarts and every
    client: the Android app and the web both read the same ``photoUrl``.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        upload = (
            request.FILES.get("photo")
            or request.FILES.get("file")
            or request.FILES.get("avatar")
        )
        if upload is None:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "Choose an image to upload."}},
                status=status.HTTP_400_BAD_REQUEST,
            )
        if upload.size > settings.AVATAR_MAX_BYTES:
            limit_mb = settings.AVATAR_MAX_BYTES // (1024 * 1024)
            return Response(
                {"error": {"code": "VALIDATION_ERROR",
                           "message": f"That image is larger than the {limit_mb} MB limit."}},
                status=status.HTTP_400_BAD_REQUEST,
            )

        head = upload.read(16)
        upload.seek(0)
        kind = _image_kind(head)
        if kind is None:
            return Response(
                {"error": {"code": "UNSUPPORTED_IMAGE",
                           "message": "Use a JPEG, PNG or WebP image."}},
                status=status.HTTP_400_BAD_REQUEST,
            )
        suffix, _content_type = kind

        user = request.user
        for old in _avatar_files(user.id):
            try:
                os.remove(old)
            except OSError:
                pass

        path = os.path.join(_avatar_root(), f"fs_{user.id}{suffix}")
        with open(path, "wb") as destination:
            for chunk in upload.chunks():
                destination.write(chunk)

        # The version query is what makes a replacement visible immediately:
        # the URL is cached for a week, so a new picture needs a new URL.
        user.photo_url = request.build_absolute_uri(
            f"/api/v1/accounts/avatar/{user.id}/?v={uuid.uuid4().hex[:10]}"
        )
        user.save(update_fields=["photo_url"])
        return Response(UserSerializer(user).data)

    def delete(self, request):
        user = request.user
        for old in _avatar_files(user.id):
            try:
                os.remove(old)
            except OSError:
                pass
        user.photo_url = None
        user.save(update_fields=["photo_url"])
        return Response(UserSerializer(user).data)


class AvatarView(APIView):
    """
    Serve a stored profile picture.

    Deliberately unauthenticated: an ``<img src>`` and an Android image loader
    cannot attach a bearer token, and a profile picture is not private data
    (it is shown in every class roster). The only file reachable is the one
    whose name matches a user id exactly, and the id is validated first so a
    crafted path cannot walk out of the avatar directory.
    """
    permission_classes = []
    authentication_classes = []

    def get(self, request, user_id):
        if not _SAFE_USER_ID.match(user_id or ""):
            return Response(
                {"error": {"code": "NOT_FOUND", "message": "No picture for that account."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        matches = _avatar_files(user_id)
        if len(matches) != 1:
            return Response(
                {"error": {"code": "NOT_FOUND", "message": "No picture for that account."}},
                status=status.HTTP_404_NOT_FOUND,
            )
        path = matches[0]
        suffix = os.path.splitext(path)[1].lower()
        response = FileResponse(
            open(path, "rb"), content_type=_AVATAR_CONTENT_TYPES.get(suffix, "image/jpeg")
        )
        # Versioned URLs change on every replacement, so a week is safe.
        response["Cache-Control"] = "public, max-age=604800"
        response["X-Content-Type-Options"] = "nosniff"
        return response


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
        # Local/mobile convenience login. Both switches are required: DEBUG
        # alone is too easy to leave on somewhere public, and this branch mints
        # a fully privileged admin token for a bare POST.
        if (
            settings.DEV_LOGIN_ENABLED
            and settings.DEBUG
            and request.data.get("dev_login")
        ):
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
