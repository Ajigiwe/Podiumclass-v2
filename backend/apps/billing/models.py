from django.db import models
from django.utils import timezone
from apps.accounts.models import User
from apps.classrooms.models import Session

class Transaction(models.Model):
    id = models.CharField(max_length=128, primary_key=True)
    user = models.ForeignKey(User, on_delete=models.RESTRICT, related_name="transactions")
    session = models.ForeignKey(Session, on_delete=models.SET_NULL, null=True, blank=True, related_name="transactions")
    paystack_reference = models.CharField(max_length=128, unique=True, null=True, blank=True)
    amount = models.IntegerField() # Pesewas
    currency = models.CharField(max_length=8, default="GHS")
    payment_channel = models.CharField(max_length=32, default="mobile_money")
    status = models.CharField(max_length=32, default="pending") # pending, succeeded, failed, refunded
    type = models.CharField(max_length=32, default="session_payment") # topup, session_payment, refund
    paid_at = models.DateTimeField(null=True, blank=True)
    is_hidden = models.BooleanField(default=False)
    metadata = models.JSONField(null=True, blank=True)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "transactions"
        indexes = [
            models.Index(fields=["user", "created_at"]),
            models.Index(fields=["paystack_reference"]),
            models.Index(fields=["status"]),
        ]

    def __str__(self):
        return f"Tx {self.id}: {self.amount} {self.currency} ({self.status})"
