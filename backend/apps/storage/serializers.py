from rest_framework import serializers

from .models import SessionMaterial

# The resource kinds the classroom materials modal understands. Anything stored
# outside this set (a MIME type from an older write, an upload's content type)
# is normalised on read rather than rejected, so a legacy row still renders.
MATERIAL_TYPES = ("pdf", "video", "slides", "doc", "link")

# Icons the modal shows per kind; the template falls back to a generic file.
DEFAULT_MATERIAL_ICONS = {
    "pdf": "fas fa-file-pdf",
    "video": "fas fa-video",
    "slides": "fas fa-desktop",
    "doc": "fas fa-file-word",
    "link": "fas fa-link",
}


def _normalise_type(raw) -> str:
    """Map a stored value (a kind or a MIME type) onto one of MATERIAL_TYPES."""
    if not raw:
        return "link"
    value = str(raw).strip().lower()
    if value in MATERIAL_TYPES:
        return value
    if value == "pdf" or value.endswith("/pdf") or value == "application/x-pdf":
        return "pdf"
    if value.startswith("video/") or value in ("mp4", "webm", "mkv"):
        return "video"
    if value.startswith(("presentation", "application/vnd.ms-powerpoint",
                         "application/vnd.openxmlformats-officedocument.presentationml")):
        return "slides"
    if value.startswith("text/") or value.startswith("application/msword") or \
            value.startswith("application/vnd.openxmlformats-officedocument"):
        return "doc"
    return "link"


class SessionMaterialSerializer(serializers.ModelSerializer):
    """
    Read shape for the classroom materials modal.

    The columns are named for the file they point at (`file_url`, `file_type`,
    `file_size_bytes`), while the UI reads `url`, `type`, `size`, `icon` -- so
    the translation happens here instead of being duplicated in the template.
    """
    url = serializers.URLField(source="file_url", read_only=True)
    type = serializers.SerializerMethodField()
    size = serializers.SerializerMethodField()
    icon = serializers.SerializerMethodField()
    sharedBy = serializers.CharField(source="uploaded_by", read_only=True)
    sharedAt = serializers.DateTimeField(source="created_at", read_only=True)

    class Meta:
        model = SessionMaterial
        fields = ["id", "title", "url", "type", "size", "icon", "sharedBy", "sharedAt"]

    def get_type(self, obj):
        return _normalise_type(obj.file_type)

    def get_size(self, obj):
        """Human-readable size, or "" when unknown so the template hides it."""
        total = obj.file_size_bytes
        if total is None or total <= 0:
            return ""
        units = ("B", "KB", "MB", "GB", "TB")
        value = float(total)
        for unit in units:
            if value < 1024 or unit == units[-1]:
                return f"{int(value)} {unit}" if unit == "B" else f"{value:.1f} {unit}"
            value /= 1024
        return ""

    def get_icon(self, obj):
        return DEFAULT_MATERIAL_ICONS.get(self.get_type(obj), "fas fa-file")
