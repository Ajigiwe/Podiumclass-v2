from rest_framework import serializers


class SettingWriteSerializer(serializers.Serializer):
    """
    Validates an incoming setting value against the type declared in the
    registry, so the API rejects junk before it reaches the database.
    """

    value = serializers.CharField(allow_blank=True, required=True)

    def validate_value(self, value):
        spec = self.context.get("spec") or {}
        kind = spec.get("type", "text")

        if kind == "integer":
            try:
                int(str(value).strip())
            except (TypeError, ValueError):
                raise serializers.ValidationError("This setting must be a whole number.")
        elif kind == "email":
            if value and ("@" not in value or value.startswith("@")):
                raise serializers.ValidationError("Enter a valid email address.")
        elif kind == "url":
            if value and not str(value).startswith(("http://", "https://", "wss://", "ws://")):
                raise serializers.ValidationError(
                    "Enter a valid URL (http, https, ws, or wss)."
                )
        return value
