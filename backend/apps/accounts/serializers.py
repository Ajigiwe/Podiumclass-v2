from rest_framework import serializers
from .models import User

class UserSerializer(serializers.ModelSerializer):
    fullName = serializers.CharField(source="full_name")
    photoUrl = serializers.CharField(source="photo_url", required=False, allow_null=True)
    indexNumber = serializers.CharField(source="index_number", required=False, allow_null=True)
    walletBalance = serializers.IntegerField(source="wallet_balance", read_only=True)
    isVerified = serializers.BooleanField(source="is_verified", read_only=True)

    class Meta:
        model = User
        fields = [
            'id', 'email', 'fullName', 'role', 'photoUrl',
            'indexNumber', 'walletBalance', 'isVerified',
            'university', 'status', 'bio'
        ]
