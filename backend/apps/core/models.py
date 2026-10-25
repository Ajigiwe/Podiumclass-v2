from django.db import models
from django.utils import timezone

class SystemSetting(models.Model):
    key = models.CharField(max_length=64, primary_key=True)
    value = models.TextField()
    description = models.CharField(max_length=255, null=True, blank=True)
    is_secret = models.BooleanField(default=False)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "system_settings"
        verbose_name = "System Setting"
        verbose_name_plural = "System Settings"

    def __str__(self):
        return f"{self.key}: {self.value if not self.is_secret else '********'}"

    @classmethod
    def get_setting(cls, key: str, default: str = None) -> str:
        try:
            return cls.objects.get(key=key).value
        except cls.DoesNotExist:
            return default
