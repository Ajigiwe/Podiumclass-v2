from rest_framework import serializers
from .models import User

class UserSerializer(serializers.ModelSerializer):
    fullName = serializers.CharField(source="full_name")
    photoUrl = serializers.CharField(source="photo_url", required=False, allow_null=True)
    indexNumber = serializers.CharField(source="index_number", required=False, allow_null=True)
    walletBalance = serializers.IntegerField(source="wallet_balance", read_only=True)
    isVerified = serializers.BooleanField(source="is_verified", read_only=True)
    # Set by an admin, not by the user: surfaced so the admin panel can show
    # the grant state and the app can decide whether to offer "New class".
    canCreateClasses = serializers.BooleanField(source="can_create_classes", read_only=True)
    createdAt = serializers.DateTimeField(source="created_at", read_only=True)
    phone = serializers.SerializerMethodField()
    year = serializers.SerializerMethodField()

    def get_phone(self, obj):
        return (obj.metadata or {}).get("phone") or ""

    def get_year(self, obj):
        return (obj.metadata or {}).get("year") or ""

    class Meta:
        model = User
        fields = [
            'id', 'email', 'fullName', 'role', 'photoUrl',
            'indexNumber', 'walletBalance', 'isVerified',
            'canCreateClasses', 'createdAt',
            'university', 'status', 'bio', 'phone', 'year'
        ]
