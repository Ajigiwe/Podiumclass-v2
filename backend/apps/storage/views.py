"""
Session resources: materials shared with a class, and (later) recordings.

Materials are addressed under the session that owns them --

    GET    /api/v1/classrooms/sessions/<session_id>/materials/
    POST   /api/v1/classrooms/sessions/<session_id>/materials/
    DELETE /api/v1/classrooms/sessions/<session_id>/materials/<material_id>/

-- because that is how a student reaches them, but they are implemented here
next to the `SessionMaterial` model rather than in the classrooms app: a file
shared with a class is a storage object, not a session attribute.

The routes live in ``apps/classrooms/urls.py`` so the whole
``/api/v1/classrooms/`` surface stays readable in one place.
"""
import uuid

from django.core.exceptions import ValidationError as DjangoValidationError
from django.core.validators import URLValidator
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.classrooms.access import can_moderate
from apps.classrooms.models import Session
from .models import SessionMaterial
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
