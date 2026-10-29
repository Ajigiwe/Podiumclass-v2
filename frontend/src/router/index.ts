import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { useFeaturesStore } from '../stores/features';
import { capturePageview } from '../lib/analytics';

const routes: RouteRecordRaw[] = [
  {
    // The public marketing page. Signed-out visitors to `/` land here instead
    // of on a bare login card; the sign-in button is one click away.
    path: '/welcome',
    name: 'Landing',
    component: () => import('../views/LandingView.vue'),
  },
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('../views/DashboardView.vue'),
  },
  {
    path: '/about',
    name: 'About',
    component: () => import('../views/AboutView.vue'),
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
  // Hash history, deliberately.
  //
  // The production host (fPanel, shared with the hosting control panel) owns
  // the paths it uses for itself: a request to /login is answered with a 302 to
  // the hosting provider's own sign-in page, and every other unregistered path
  // (including /classroom/<code>) returns 204 with an empty body. Static
  // hosting there offers no rewrite-to-index.html, so path-based deep links --
  // and a plain refresh on any route -- cannot be served at all.
  //
  // With hash history the browser only ever requests `/`, and the route lives
  // after the `#`, which no server sees. Invite links therefore keep working
  // across refreshes, and they keep working if the app later moves to a host
  // that does support rewrites.
  history: createWebHashHistory(),
  routes,
});

// One $pageview per navigation -- funnels and retention are read off these.
router.afterEach((to) => {
  capturePageview(to.path);
});

// Routes that require a signed-in user. Everything else (landing, login) is public.
const PUBLIC_ROUTES = new Set(['/login', '/welcome', '/about']);

router.beforeEach(async (to) => {
  if (PUBLIC_ROUTES.has(to.path)) {
    // A signed-in visitor has no use for the marketing page.
    if (to.path === '/welcome' && useAuthStore().token) return '/';
    return true;
  }

  const auth = useAuthStore();

  // No token at all -> the landing page for the root, login (with the intended
  // destination) for a deep link such as an invite or a classroom URL.
  if (!auth.token) {
    if (to.path === '/') return '/welcome';
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
