import uuid
from django.contrib.auth.models import AbstractBaseUser, PermissionsMixin, BaseUserManager
from django.db import models
from django.utils import timezone

class UserManager(BaseUserManager):
    def create_user(self, email, full_name="User", password=None, **extra_fields):
        if not email:
            raise ValueError("The Email field must be set")
        email = self.normalize_email(email).lower()
        if 'id' not in extra_fields:
            extra_fields['id'] = f"usr_{uuid.uuid4().hex[:20]}"
        user = self.model(email=email, full_name=full_name, **extra_fields)
        if password:
            user.set_password(password)
        else:
            user.set_unusable_password()
        user.save(using=self._db)
        return user

    def create_superuser(self, email, full_name="Admin", password=None, **extra_fields):
        extra_fields.setdefault('is_staff', True)
        extra_fields.setdefault('is_superuser', True)
        extra_fields.setdefault('role', 'admin')
        return self.create_user(email, full_name, password, **extra_fields)

class User(AbstractBaseUser, PermissionsMixin):
    id = models.CharField(max_length=128, primary_key=True)
    email = models.EmailField(unique=True, db_index=True)
    full_name = models.CharField(max_length=255)
    role = models.CharField(max_length=32, default="student", db_index=True) # student, lecturer, admin
    bio = models.TextField(blank=True, null=True)
    photo_url = models.URLField(max_length=1024, blank=True, null=True)
    display_icon = models.CharField(max_length=64, blank=True, null=True)
    index_number = models.CharField(max_length=64, blank=True, null=True, db_index=True)
    subscription_status = models.CharField(max_length=64, blank=True, null=True)
    subscription_expires_at = models.DateTimeField(blank=True, null=True)
    status = models.CharField(max_length=32, default="active") # active, disabled
    university = models.CharField(max_length=255, blank=True, null=True)
    class_count = models.IntegerField(default=0)
    is_verified = models.BooleanField(default=False, db_index=True)

    # An admin can hand class-creation rights to one account without making it a
    # lecturer: the flag is additive on top of the role, so revoking it returns
    # the user to plain student permissions without touching anything else.
    can_create_classes = models.BooleanField(default=False, db_index=True)

    # Wallet (integer pesewas)
    wallet_balance = models.IntegerField(default=0)
    wallet_updated_at = models.DateTimeField(default=timezone.now)

    metadata = models.JSONField(blank=True, null=True)
    created_at = models.DateTimeField(default=timezone.now, db_index=True)
    updated_at = models.DateTimeField(auto_now=True)

    is_staff = models.BooleanField(default=False)
    is_active = models.BooleanField(default=True)

    objects = UserManager()

    def save(self, *args, **kwargs):
        """
        Guarantee a non-empty primary key on INSERT.

        ``id`` is a CharField primary key, so it defaults to "" rather than
        auto-generating. Only UserManager.create_user filled it, which means
        any User.objects.create()/get_or_create() bypassing the manager
        silently produced a user whose id -- and therefore whose JWT "sub" --
        was "". That user could never be resolved from a token. Generating the
        id here closes that hole for every creation path.

        On UPDATE the pk is already set, so it is left untouched.
        """
        if self._state.adding and not self.id:
            self.id = f"usr_{uuid.uuid4().hex[:20]}"
        super().save(*args, **kwargs)

    USERNAME_FIELD = "email"
    REQUIRED_FIELDS = ["full_name"]

    class Meta:
        db_table = "users"
        indexes = [
            models.Index(fields=["role"]),
            models.Index(fields=["is_verified"]),
            models.Index(fields=["created_at"]),
        ]

    def __str__(self):
        return f"{self.full_name} ({self.email})"
