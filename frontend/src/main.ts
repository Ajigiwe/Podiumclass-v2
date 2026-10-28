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
