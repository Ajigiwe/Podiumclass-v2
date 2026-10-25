from rest_framework import serializers
from .models import Transaction

class TransactionSerializer(serializers.ModelSerializer):
    userId = serializers.CharField(source="user_id", read_only=True)
    sessionId = serializers.CharField(source="session_id", read_only=True)
    reference = serializers.CharField(source="paystack_reference", read_only=True)
    channel = serializers.CharField(source="payment_channel", read_only=True)
    paidAt = serializers.DateTimeField(source="paid_at", read_only=True)
    createdAt = serializers.DateTimeField(source="created_at", read_only=True)

    class Meta:
        model = Transaction
        fields = [
            'id', 'userId', 'sessionId', 'reference', 'amount', 'currency',
            'channel', 'status', 'type', 'paidAt', 'metadata', 'createdAt'
        ]
