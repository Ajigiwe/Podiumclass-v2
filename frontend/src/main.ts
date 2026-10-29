import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import { initAnalytics } from './lib/analytics';
import './style.css';

// Before the first render, so the first $pageview is not lost. A missing key or
// a stored opt-out makes every call below a no-op.
initAnalytics();

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);

app.mount('#app');

/**
 * Hand the screen over from the boot splash (drawn by index.html before any JS
 * runs) to the mounted app.
 *
 * The splash is what makes a standalone launch feel like an app rather than a
 * white flash. It is only removed once Vue has actually mounted, so it never
 * covers nothing.
 */
function dismissBootSplash() {
  const splash = document.getElementById('boot-splash');
  if (!splash) return;
  splash.classList.add('is-done');
  const remove = () => splash.remove();
  splash.addEventListener('transitionend', remove, { once: true });
  // Belt and braces: transitionend does not fire if the element is hidden or
  // the transition is disabled by a reduced-motion preference.
  window.setTimeout(remove, 500);
}

dismissBootSplash();

/**
 * The installable shell, in production only.
 *
 * Registering in development would let a cached shell shadow the dev server and
 * produce the "my changes aren't showing up" failure this project has hit
 * before. Failures are logged, never surfaced: a browser without service
 * worker support runs the app normally.
 */
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((error) => {
      console.warn('Podium: offline shell unavailable.', error);
    });
  });
}
