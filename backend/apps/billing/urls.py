from django.urls import path
from .views import TransactionListView, WalletTopupView, WalletDeductView

urlpatterns = [
    path('transactions/', TransactionListView.as_view(), name='billing-transactions'),
    path('transactions', TransactionListView.as_view(), name='billing-transactions-noslash'),
    path('topup/', WalletTopupView.as_view(), name='billing-topup'),
    path('topup', WalletTopupView.as_view(), name='billing-topup-noslash'),
    path('deduct/', WalletDeductView.as_view(), name='billing-deduct'),
    path('deduct', WalletDeductView.as_view(), name='billing-deduct-noslash'),
]
