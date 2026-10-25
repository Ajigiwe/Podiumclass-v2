<template>
  <div class="flex-1 p-8 max-w-4xl mx-auto w-full space-y-6 animate-fade-in">
    <!-- Balance Card -->
    <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl p-6 flex items-center justify-between shadow-sm">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 bg-[#E8EEFF] dark:bg-blue-900/20 rounded-lg flex items-center justify-center text-[#1845D4]">
          <i class="fas fa-wallet text-sm"></i>
        </div>
        <div>
          <div class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest">
            Wallet Balance
          </div>
          <div class="text-2xl font-black text-[#0D0D1A] dark:text-white">
            {{ authStore.formattedBalance }}
          </div>
        </div>
      </div>

      <button
        @click="showTopupModal = true"
        class="px-4 py-2 bg-[#1845D4] text-white text-[11px] font-bold uppercase tracking-widest rounded-lg hover:bg-[#0F2FA8] transition-all cursor-pointer shadow-md shadow-blue-600/10"
      >
        Top Up
      </button>
    </div>

    <!-- Transaction History Card -->
    <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl overflow-hidden shadow-sm">
      <div class="px-6 py-4 border-b border-[#DDE0F0] dark:border-slate-800 font-bold text-[13px] text-[#0D0D1A] dark:text-white">
        Transaction History (Credits & Debits)
      </div>

      <div class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
        <div
          v-for="tx in transactions"
          :key="tx.id"
          class="px-6 py-4 flex items-center justify-between"
        >
          <div>
            <div class="text-[13px] font-bold text-[#0D0D1A] dark:text-white">
              {{ tx.type === 'deposit' ? 'Top-up' : 'Class payment' }}
              <span class="text-[10px] text-[#8888A8] ml-1">{{ tx.id }}</span>
            </div>
            <div class="text-[11px] text-[#8888A8] mt-0.5">
              {{ tx.date }} · {{ tx.channel }}
            </div>
          </div>

          <div
            class="text-[13px] font-black"
            :class="tx.type === 'deposit' ? 'text-emerald-600' : 'text-[#0D0D1A] dark:text-white'"
          >
            {{ tx.type === 'deposit' ? '+' : '-' }}GHS {{ (tx.amount / 100).toFixed(2) }}
          </div>
        </div>
      </div>
    </div>

    <!-- Topup Modal -->
    <div
      v-if="showTopupModal"
      class="fixed inset-0 z-[100] flex items-center justify-center p-6 bg-[#0D0D1A]/40 backdrop-blur-sm"
      @click.self="showTopupModal = false"
    >
      <div class="bg-white dark:bg-slate-900 w-full max-w-sm rounded-2xl p-8 relative border border-[#DDE0F0] dark:border-slate-800 shadow-2xl">
        <div class="flex items-center justify-between mb-2">
          <h3 class="font-bold text-[#0D0D1A] dark:text-white">Top Up Wallet</h3>
          <button @click="showTopupModal = false" class="text-[#8888A8] hover:text-[#0D0D1A]">
            <i class="fas fa-times"></i>
          </button>
        </div>
        <p class="text-[11px] text-[#8888A8] mb-4">GHS only, min GHS 1.00</p>

        <form @submit.prevent="handleTopup" class="space-y-4">
          <input
            v-model.number="topupAmount"
            type="number"
            step="1"
            min="1"
            required
            placeholder="5.00"
            class="w-full px-4 py-3 border-2 border-[#DDE0F0] dark:border-slate-700 dark:bg-slate-800 rounded-xl text-sm outline-none focus:border-[#1845D4]"
          />

          <button
            type="submit"
            :disabled="isProcessing"
            class="w-full py-3 bg-[#1845D4] text-white rounded-xl text-[11px] font-bold uppercase tracking-widest hover:bg-[#0F2FA8] transition-all cursor-pointer shadow-md shadow-blue-600/10"
          >
            {{ isProcessing ? 'Processing with Paystack...' : 'Pay with Paystack' }}
          </button>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useAuthStore } from '../stores/auth';
import { api } from '../api/client';

const authStore = useAuthStore();
const showTopupModal = ref(false);
const isProcessing = ref(false);
const topupAmount = ref(10);
const transactions = ref<any[]>([]);

async function loadTransactions() {
  try {
    const res = await api.getDocuments<any>('/api/v1/billing/transactions/');
    transactions.value = (res.documents || []).map((t: any) => ({
      id: t.id,
      type: t.type === 'topup' ? 'deposit' : 'session_payment',
      amount: t.amount,
      date: new Date(t.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }),
      channel: t.channel || '—',
    }));
  } catch (err) {
    console.error('Failed to load transactions', err);
  }
}

async function handleTopup() {
  isProcessing.value = true;
  try {
    const pesewas = Math.round(topupAmount.value * 100);
    const res = await api.postDocument<any>('/api/v1/billing/topup/', {
      amount: pesewas,
      channel: 'mobile_money',
      email: authStore.user?.email,
    });

    if (authStore.user && res.newBalance !== undefined) {
      authStore.user.walletBalance = res.newBalance;
    }
    showTopupModal.value = false;
    await loadTransactions();
  } catch (err: any) {
    alert(err?.message || 'Top up failed');
  } finally {
    isProcessing.value = false;
  }
}

onMounted(loadTransactions);
</script>
