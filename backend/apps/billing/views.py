import uuid
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.views import APIView
from rest_framework.response import Response
from apps.accounts.permissions import IsLecturerOrAdmin, is_lecturer
from apps.accounts.models import User
from apps.classrooms.models import Session
from apps.core.models import SystemSetting
from apps.core.guards import require_feature
from .models import Transaction
from .serializers import TransactionSerializer


def _system_int(key: str, default: int) -> int:
    """Read an integer-valued system setting, falling back to a default."""
    raw = SystemSetting.get_setting(key)
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError):
        return default


class TransactionListView(APIView):
    """
    GET /api/v1/billing/transactions
    Lists transactions for the authenticated user. Lecturers and admins may
    filter to any user via ?userId=; students only ever see their own.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        require_feature("wallet_payments")
        user_id = request.query_params.get("userId")
        user_email = request.query_params.get("email")

        # Staff may query any wallet; everyone else is scoped to themselves.
        # Without this, a student could read anyone's transaction history by
        # passing an arbitrary userId.
        if is_lecturer(request.user):
            if user_id:
                qs = Transaction.objects.filter(user_id=user_id, is_hidden=False)
            elif user_email:
                qs = Transaction.objects.filter(user__email=user_email, is_hidden=False)
            else:
                qs = Transaction.objects.filter(is_hidden=False)
        else:
            qs = Transaction.objects.filter(user=request.user, is_hidden=False)

        qs = qs.order_by("-created_at")
        serializer = TransactionSerializer(qs[:50], many=True)
        return Response(serializer.data)

class WalletTopupView(APIView):
    """
    POST /api/v1/billing/topup
    Initiate a wallet top-up for the AUTHENTICATED user.

    Money must never be credited from a client-supplied amount. The old
    system verified each payment against Paystack's API (or its signed
    webhook) before crediting; that gateway is not wired into this backend
    yet, so this endpoint fails closed with an actionable error instead of
    minting balance out of an unverified request body.

    Body: { "amount": 1000, "channel": "mobile_money" }
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        require_feature("wallet_payments")
        data = request.data
        try:
            amount = int(data.get("amount", 0)) # Pesewas
        except (TypeError, ValueError):
            amount = 0

        if amount <= 0:
            return Response(
                {"error": {"code": "INVALID_AMOUNT", "message": "A positive amount in pesewas is required."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        # Enforce the configured minimum top-up.
        min_topup = _system_int("min_topup_amount", 100)
        if amount < min_topup:
            return Response(
                {"error": {
                    "code": "BELOW_MINIMUM",
                    "message": f"Minimum top-up is GHS {(min_topup / 100):.2f}.",
                }},
                status=status.HTTP_400_BAD_REQUEST
            )

        return Response(
            {"error": {
                "code": "PAYMENTS_NOT_CONFIGURED",
                "message": (
                    "Online payments are not enabled on this deployment yet. "
                    "Wallet top-ups require Paystack verification before any "
                    "balance can be credited."
                ),
            }},
            status=status.HTTP_503_SERVICE_UNAVAILABLE
        )

class WalletDeductView(APIView):
    """
    POST /api/v1/billing/deduct
    Deduct the session entrance fee from the AUTHENTICATED user's wallet.
    Body: { "sessionId": "..." }

    The debited account comes from the verified token, never the request body.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        require_feature("wallet_payments")
        data = request.data
        session_id = data.get("sessionId")

        if not session_id:
            return Response(
                {"error": {"code": "VALIDATION_ERROR", "message": "sessionId is required."}},
                status=status.HTTP_400_BAD_REQUEST
            )

        try:
            session = Session.objects.get(id=session_id)
        except Session.DoesNotExist:
            return Response(
                {"error": {"code": "SESSION_NOT_FOUND", "message": "Session not found."}},
                status=status.HTTP_404_NOT_FOUND
            )

        # Free session check
        if session.is_free or session.price <= 0:
            return Response({
                "free": True,
                "alreadyPaid": True,
                "message": "Classroom session is free of charge."
            })

        user = request.user

        # Lecturer/Host exempt
        if user.role in ["lecturer", "admin"] or session.host_id == user.id or session.lecturer_id == user.id:
            return Response({
                "exempt": True,
                "alreadyPaid": True,
                "message": "Lecturers and hosts are exempt from fees."
            })

        # Already paid check
        prior_tx = Transaction.objects.filter(
            user=user,
            session=session,
            status="succeeded",
            type="session_payment"
        ).first()

        if prior_tx:
            return Response({
                "alreadyPaid": True,
                "message": "Entrance fee previously satisfied."
            })

        # Insufficient balance check
        if user.wallet_balance < session.price:
            return Response({
                "insufficient": True,
                "required": session.price,
                "balance": user.wallet_balance,
                "message": f"Insufficient wallet balance. Required: GHS {(session.price / 100):.2f}, Available: GHS {(user.wallet_balance / 100):.2f}"
            }, status=status.HTTP_402_PAYMENT_REQUIRED)

        # Deduct
        ref = f"SESS-{uuid.uuid4().hex[:10].upper()}"
        tx = Transaction.objects.create(
            id=f"tx_{uuid.uuid4().hex[:16]}",
            user=user,
            session=session,
            amount=session.price,
            currency=session.currency or "GHS",
            payment_channel="wallet",
            status="succeeded",
            type="session_payment",
            paystack_reference=ref,
            paid_at=timezone.now(),
            metadata={"session_id": session.id, "title": session.title}
        )

        user.wallet_balance -= session.price
        user.wallet_updated_at = timezone.now()
        user.save(update_fields=["wallet_balance", "wallet_updated_at"])

        return Response({
            "success": True,
            "deducted": session.price,
            "newBalance": user.wallet_balance,
            "transactionId": tx.id
        })
