import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { useFeaturesStore } from '../stores/features';
import { capturePageview } from '../lib/analytics';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('../views/DashboardView.vue'),
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
  },
  {
    path: '/classroom/:id',
    name: 'Classroom',
    component: () => import('../views/ClassroomView.vue'),
    props: true,
  },
  {
    path: '/attendance',
    name: 'Attendance',
    component: () => import('../views/AttendanceView.vue'),
  },
  {
    path: '/wallet',
    name: 'Wallet',
    component: () => import('../views/WalletView.vue'),
  },
  {
    path: '/history',
    name: 'History',
    component: () => import('../views/HistoryView.vue'),
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/ProfileView.vue'),
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('../views/AdminView.vue'),
  },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

// One $pageview per navigation -- funnels and retention are read off these.
router.afterEach((to) => {
  capturePageview(to.path);
});

// Routes that require a signed-in user. Everything else (login) is public.
const PUBLIC_ROUTES = new Set(['/login']);

router.beforeEach(async (to) => {
  if (PUBLIC_ROUTES.has(to.path)) return true;

  const auth = useAuthStore();

  // No token at all -> straight to login preserving redirect target.
  if (!auth.token) {
    return `/login?redirect=${encodeURIComponent(to.fullPath)}`;
  }

  // We may have a token but not yet know who it belongs to (e.g. a fresh
  // page load). Resolve the user once; the store clears the token on 401.
  if (!auth.user) {
    try {
      await auth.fetchCurrentUser();
    } catch {
      /* token cleared by the store */
    }
  }

  if (!auth.token) {
    return `/login?redirect=${encodeURIComponent(to.fullPath)}`;
  }

  // Admin panel is staff-only regardless of how the link is reached.
  if (to.path === '/admin' && !auth.isAdmin) return '/';

  // A student who has not supplied an index number yet must finish their
  // profile first: the classroom roster and attendance records are keyed on it,
  // and Google sign-in only provides a name and email.
  if (
    auth.user?.role === 'student' &&
    !auth.user.indexNumber &&
    to.path !== '/profile'
  ) {
    return '/profile?setup=1';
  }

  // Feature flags gate whole surfaces. Load once (public endpoint, no auth)
  // and bounce the user off a disabled route (e.g. wallet turned off) so a
  // stale bookmark or manual URL can't reach a switched-off feature.
  const features = useFeaturesStore();
  if (!features.loaded) {
    await features.loadFeatures();
  }
  if (to.path === '/wallet' && !features.walletEnabled) return '/';
  if (to.path.startsWith('/classroom') && !features.liveClassroomEnabled) return '/';

  return true;
});
