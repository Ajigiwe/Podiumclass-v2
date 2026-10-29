<template>
  <div class="landing" :class="{ scrolled }">
    <!-- ---------------------------------------------------------------- nav -->
    <header class="nav">
      <div class="nav-inner">
        <router-link to="/welcome" class="brand" aria-label="Podium home">
          <span class="brand-mark"><i class="fas fa-graduation-cap"></i></span>
          <span class="brand-name">Podium</span>
        </router-link>

        <!--
          These scroll the page instead of navigating. With hash routing a
          plain "#features" href is read by the router as the route /features,
          which bounced visitors to the login screen.
        -->
        <nav class="nav-links" aria-label="Sections">
          <button type="button" class="nav-link" @click="goToSection('features')">Features</button>
          <button type="button" class="nav-link" @click="goToSection('how')">How it works</button>
          <button type="button" class="nav-link" @click="goToSection('mobile')">Android</button>
          <router-link to="/about">About</router-link>
        </nav>

        <div class="nav-actions">
          <button v-if="canInstall" type="button" class="btn btn-quiet" @click="install">
            <i class="fas fa-arrow-down-to-bracket"></i> Install
          </button>
          <router-link to="/login" class="btn btn-dark">Sign in</router-link>
        </div>
      </div>
    </header>

    <!-- --------------------------------------------------------------- hero -->
    <section class="hero">
      <div class="shell hero-grid">
        <div class="hero-copy" data-reveal>
          <h1>
            Run a real classroom<br>
            <span class="accent">from any device.</span>
          </h1>
          <p class="lede">
            Podium brings a whole class together on screen: live video, attendance, materials,
            a whiteboard and recordings — on the web and on the phone. Share a code and everyone
            is in.
          </p>

          <div class="cta-row">
            <router-link to="/login" class="btn btn-primary btn-lg">
              Continue with Google
            </router-link>
            <button type="button" class="btn btn-outline btn-lg" @click="goToSection('how')">
              See how it works
            </button>
          </div>

          <!-- Join by code: the one action a student actually needs. -->
          <form class="join" @submit.prevent="joinByCode">
            <label class="join-label" for="class-code">Have a class code?</label>
            <div class="join-field">
              <input
                id="class-code"
                v-model="code"
                type="text"
                inputmode="text"
                autocomplete="off"
                spellcheck="false"
                placeholder="POD-XXXX"
                maxlength="24"
                @input="codeError = ''"
              >
              <button type="submit" class="btn btn-primary">Join</button>
            </div>
            <p v-if="codeError" class="join-error">{{ codeError }}</p>
          </form>
        </div>

        <!-- A stage drawn in CSS: no screenshot to load, no layout shift. -->
        <div class="stage-wrap" data-reveal>
          <div class="stage">
            <div class="stage-head">
              <span class="live"><span class="live-dot"></span> LIVE</span>
              <span class="stage-title">CS 301 · Distributed Systems</span>
              <span class="rec">REC 04:12</span>
            </div>

            <div class="stage-grid">
              <div class="tile tile-host">
                <span class="avatar">AB</span>
                <span class="tile-name">Ama Boateng <i class="fas fa-crown"></i></span>
                <span class="tile-mic"><i class="fas fa-microphone"></i></span>
              </div>
              <div class="tile tile-b">
                <span class="avatar">KO</span>
                <span class="tile-name">Kofi O.</span>
                <span class="tile-mic muted"><i class="fas fa-microphone-slash"></i></span>
              </div>
              <div class="tile tile-c">
                <span class="avatar">NM</span>
                <span class="tile-name">Naa M.</span>
                <span class="tile-mic muted"><i class="fas fa-microphone-slash"></i></span>
              </div>
              <div class="tile tile-check">
                <span class="check-icon"><i class="fas fa-hand"></i></span>
                <span class="check-title">Attendance check</span>
                <span class="check-sub">42s to confirm</span>
              </div>
            </div>

            <div class="stage-bar" aria-hidden="true">
              <span><i class="fas fa-microphone"></i></span>
              <span><i class="fas fa-video"></i></span>
              <span><i class="fas fa-display"></i></span>
              <span class="on"><i class="fas fa-pen-ruler"></i></span>
              <span class="on"><i class="fas fa-record-vinyl"></i></span>
              <span class="end"><i class="fas fa-phone"></i></span>
            </div>
          </div>

          <div class="stage-aside">
            <i class="fas fa-clipboard-check"></i>
            <div>
              <strong>12 of 14 verified</strong>
              <span>Attendance logged automatically</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ------------------------------------------------------------- facts -->
    <section class="facts" data-reveal>
      <div class="shell facts-inner">
        <span><i class="fas fa-bolt"></i> Crisp, smooth video</span>
        <span><i class="fas fa-clipboard-list"></i> Attendance reports</span>
        <span><i class="fas fa-folder-open"></i> Materials that stay put</span>
        <span><i class="fas fa-mobile-screen"></i> Web and Android</span>
      </div>
    </section>

    <!-- ---------------------------------------------------------- features -->
    <section id="features" class="section">
      <div class="shell">
        <header class="section-head" data-reveal>
          <span class="kicker">Everything in one place</span>
          <h2>Everything a class needs</h2>
          <p>
            Video, attendance, materials and recordings — together, so nothing is
            lost between lessons.
          </p>
        </header>

        <div class="cards">
          <article v-for="feature in features" :key="feature.title" class="card" data-reveal>
            <span class="card-icon" :class="feature.tone"><i :class="feature.icon"></i></span>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- --------------------------------------------------------------- how -->
    <section id="how" class="section section-alt">
      <div class="shell">
        <header class="section-head" data-reveal>
          <span class="kicker">How it works</span>
          <h2>Three steps and you are in</h2>
        </header>

        <ol class="steps">
          <li v-for="(step, index) in steps" :key="step.title" data-reveal>
            <span class="step-number">{{ index + 1 }}</span>
            <h3>{{ step.title }}</h3>
            <p>{{ step.body }}</p>
          </li>
        </ol>
      </div>
    </section>

    <!-- ------------------------------------------------------------- mobile -->
    <section id="mobile" class="section">
      <div class="shell mobile-grid">
        <div class="mobile-copy" data-reveal>
          <span class="kicker">On Android too</span>
          <h2>The same class, in your pocket</h2>
          <p>
            Join from your phone and get everything you would on a laptop — video, chat,
            materials, the whiteboard and recordings. Picture-in-picture keeps the class
            on screen while you take notes somewhere else.
          </p>
          <ul class="checks">
            <li><i class="fas fa-check"></i> Join with a code or a link</li>
            <li><i class="fas fa-check"></i> Chat, reactions and hand raising</li>
            <li><i class="fas fa-check"></i> Screen sharing that just works</li>
            <li><i class="fas fa-check"></i> A notification when class opens</li>
          </ul>
          <p class="note">
            Ask your institution for the release build, or open Podium in the browser
            and install it from there.
          </p>
        </div>
      </div>
    </section>

    <!-- ---------------------------------------------------------------- cta -->
    <section class="closing" data-reveal>
      <div class="shell closing-inner">
        <div>
          <h2>Your next class is one click away</h2>
          <p>Sign in with your Google account and pick up right where you left off.</p>
        </div>
        <router-link to="/login" class="btn btn-primary btn-lg">Continue with Google</router-link>
      </div>
    </section>

    <!-- ------------------------------------------------------------- footer -->
    <footer class="footer">
      <div class="shell footer-inner">
        <div class="brand brand-footer">
          <span class="brand-mark"><i class="fas fa-graduation-cap"></i></span>
          <span class="brand-name">Podium</span>
        </div>
        <nav class="footer-links" aria-label="Footer">
          <router-link to="/login">Sign in</router-link>
          <button type="button" class="footer-link" @click="goToSection('features')">Features</button>
          <button type="button" class="footer-link" @click="goToSection('mobile')">Android</button>
          <router-link to="/about">About</router-link>
        </nav>
        <p class="copyright">&copy; 2026 Podium LMS</p>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const code = ref('');
const codeError = ref('');
const scrolled = ref(false);
const canInstall = ref(false);

interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
}

let deferredPrompt: InstallPromptEvent | null = null;

/** One listener, passive: only the header's hairline changes on scroll. */
function onScroll() {
  scrolled.value = window.scrollY > 12;
}

function onInstallAvailable(event: Event) {
  // Keep the browser's own prompt for the header button instead of letting it
  // appear on its own the moment the page loads.
  event.preventDefault();
  deferredPrompt = event as InstallPromptEvent;
  canInstall.value = true;
}

function onInstalled() {
  canInstall.value = false;
  deferredPrompt = null;
}

async function install() {
  if (!deferredPrompt) return;
  await deferredPrompt.prompt();
  deferredPrompt = null;
  canInstall.value = false;
}

/**
 * Scroll to a section. Anchor hrefs cannot be used here: the app is on hash
 * routing, so "#features" is a route, not a scroll.
 */
function goToSection(id: string) {
  const target = document.getElementById(id);
  if (!target) return;
  target.scrollIntoView({
    behavior: reduceMotion?.matches ? 'auto' : 'smooth',
    block: 'start',
  });
}

function joinByCode() {
  const value = code.value.trim().toUpperCase().replace(/\s+/g, '');
  if (!value) {
    codeError.value = 'Enter the class code your host shared.';
    return;
  }
  // Sign-in is required to enter a room, so keep the target in the redirect and
  // let the login page continue to it.
  router.push({ path: '/login', query: { redirect: `/classroom/${value}` } });
}

// Reveal-on-scroll. Purely decorative, and skipped entirely when the user asked
// for reduced motion: the content is visible without it.
let observer: IntersectionObserver | null = null;
let reduceMotion: MediaQueryList | null = null;

function onMotionPreference() {
  if (!reduceMotion) return;
  if (reduceMotion.matches) {
    document.querySelectorAll('[data-reveal]').forEach((el) => el.classList.add('is-visible'));
  }
}

onMounted(() => {
  onScroll();
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('beforeinstallprompt', onInstallAvailable);
  window.addEventListener('appinstalled', onInstalled);

  reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  reduceMotion.addEventListener('change', onMotionPreference);

  if (reduceMotion.matches || !('IntersectionObserver' in window)) {
    onMotionPreference();
  } else {
    observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible');
            observer?.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: '0px 0px -40px 0px' }
    );
    document.querySelectorAll('[data-reveal]').forEach((el) => observer?.observe(el));
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll);
  window.removeEventListener('beforeinstallprompt', onInstallAvailable);
  window.removeEventListener('appinstalled', onInstalled);
  reduceMotion?.removeEventListener('change', onMotionPreference);
  observer?.disconnect();
});

// Plain descriptions of what Podium does, with no invented numbers or claims.
const features = [
  {
    icon: 'fas fa-video',
    tone: 'blue',
    title: 'Live classes that feel close',
    body: 'See and hear everyone in real time, share your screen, and keep the room clear even when the class is full.',
  },
  {
    icon: 'fas fa-clipboard-check',
    tone: 'amber',
    title: 'Attendance, taken care of',
    body: 'Attendance is marked as the class goes on. See who joined, when they arrived and when they left, and download the report whenever you need it.',
  },
  {
    icon: 'fas fa-folder-open',
    tone: 'emerald',
    title: 'Everything for the lesson',
    body: 'Send documents, slides and links into the class. They stay there afterwards, so nothing is lost between lessons or devices.',
  },
  {
    icon: 'fas fa-pen-ruler',
    tone: 'violet',
    title: 'Draw it out together',
    body: 'A shared whiteboard for working through problems, with chat and reactions running alongside the video.',
  },
  {
    icon: 'fas fa-circle',
    tone: 'rose',
    title: 'Recordings you keep',
    body: 'Record a class and watch it again later. Recordings look after themselves if the connection slips, and are ready to download or share.',
  },
  {
    icon: 'fas fa-layer-group',
    tone: 'slate',
    title: 'One place for every class',
    body: 'Live classes, past sessions, attendance and recordings sit together in one dashboard, on the web and on your phone.',
  },
];

const steps = [
  {
    title: 'Start your class',
    body: 'Create a class in a few seconds. A code and a link are ready to share straight away.',
  },
  {
    title: 'Share the code',
    body: 'Send the code or the link. Everyone gets in with one tap — nothing to install, nothing to set up.',
  },
  {
    title: 'Teach and wrap up',
    body: 'Video, materials, the whiteboard and recording all live on one bar. Close the class when you are done.',
  },
];
</script>

<style scoped>
/* ------------------------------------------------------------------ tokens */
.landing {
  --ink: #0d0d1a;
  --body: #444460;
  --muted: #8888a8;
  --line: #dde0f0;
  --paper: #ffffff;
  --wash: #f8f9fc;
  --brand: #1845d4;
  --brand-dark: #0f2fa8;
  --shadow: 0 24px 60px -28px rgba(13, 13, 26, 0.28);

  min-height: 100vh;
  background: var(--paper);
  color: var(--ink);
  font-family: 'Inter', system-ui, sans-serif;
  overflow-x: hidden;
}

:global(html.dark) .landing {
  --ink: #f1f5f9;
  --body: #cbd5e1;
  --muted: #94a3b8;
  --line: #1e293b;
  --paper: #020617;
  --wash: #0b1220;
}

.shell {
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 24px;
  box-sizing: border-box;
}

/* --------------------------------------------------------------------- nav */
.nav {
  position: fixed;
  inset: 0 0 auto 0;
  z-index: 40;
  transition: background-color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  border-bottom: 1px solid transparent;
  background: color-mix(in srgb, var(--paper) 0%, transparent);
}

.landing.scrolled .nav {
  background: color-mix(in srgb, var(--paper) 88%, transparent);
  backdrop-filter: saturate(160%) blur(12px);
  -webkit-backdrop-filter: saturate(160%) blur(12px);
  border-bottom-color: var(--line);
}

.nav-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 14px 24px;
  display: flex;
  align-items: center;
  gap: 24px;
  box-sizing: border-box;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  flex: none;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--brand);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  box-shadow: 0 8px 20px -8px rgba(24, 69, 212, 0.7);
}

.brand-name {
  font-family: 'Outfit', 'Inter', sans-serif;
  font-weight: 800;
  font-size: 20px;
  letter-spacing: -0.03em;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 26px;
  margin-left: 12px;
}

.nav-links a,
.nav-link {
  color: var(--muted);
  text-decoration: none;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  transition: color 0.15s ease;
  background: none;
  border: 0;
  padding: 0;
  font-family: inherit;
  cursor: pointer;
}

.nav-links a:hover,
.nav-link:hover {
  color: var(--brand);
}

.nav-link:focus-visible,
.footer-link:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 3px;
  border-radius: 4px;
}

.nav-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ------------------------------------------------------------------ buttons */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: 10px;
  padding: 10px 16px;
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.01em;
  cursor: pointer;
  text-decoration: none;
  transition: transform 0.12s ease, background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
  white-space: nowrap;
}

.btn:active {
  transform: scale(0.985);
}

.btn-lg {
  padding: 14px 22px;
  font-size: 13px;
  border-radius: 12px;
}

.btn-primary {
  background: var(--brand);
  color: #fff;
  box-shadow: 0 14px 30px -14px rgba(24, 69, 212, 0.75);
}

.btn-primary:hover {
  background: var(--brand-dark);
}

.btn-dark {
  background: var(--ink);
  color: var(--paper);
}

.btn-outline,
.btn-quiet {
  background: transparent;
  border-color: var(--line);
  color: var(--body);
}

.btn-outline:hover,
.btn-quiet:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.btn-quiet {
  padding: 9px 12px;
}

/* --------------------------------------------------------------------- hero */
.hero {
  position: relative;
  padding: 118px 0 56px;
  background:
    radial-gradient(900px 420px at 78% -10%, rgba(24, 69, 212, 0.12), transparent 70%),
    var(--paper);
}

.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 46px;
  align-items: center;
}

h1 {
  margin: 0;
  font-family: 'Outfit', 'Inter', sans-serif;
  font-size: clamp(34px, 5vw, 58px);
  line-height: 1.02;
  letter-spacing: -0.035em;
  font-weight: 800;
}

.accent {
  color: var(--brand);
}

.lede {
  margin: 18px 0 0;
  max-width: 34em;
  color: var(--body);
  font-size: 15px;
  line-height: 1.65;
}

.cta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 26px;
}

.join {
  margin-top: 26px;
  max-width: 340px;
}

.join-label {
  display: block;
  font-size: 10px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--muted);
  margin-bottom: 8px;
}

.join-field {
  display: flex;
  gap: 8px;
  padding: 6px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--wash);
}

.join-field input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 8px 10px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  outline: none;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.join-field input::placeholder {
  color: var(--muted);
  letter-spacing: 0.06em;
}

.join-field input:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
  border-radius: 8px;
}

.join-error {
  margin: 8px 0 0;
  font-size: 11px;
  font-weight: 600;
  color: #dc2626;
}

/* -------------------------------------------------------------- stage mock */
.stage-wrap {
  position: relative;
}

.stage {
  position: relative;
  border-radius: 20px;
  padding: 12px;
  background: #0b1020;
  border: 1px solid #1b2440;
  box-shadow: var(--shadow);
}

.stage-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 4px 10px;
  font-size: 10px;
  font-weight: 700;
  color: #94a3b8;
}

.live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 7px;
  border-radius: 6px;
  background: rgba(239, 68, 68, 0.16);
  color: #fca5a5;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: 0.1em;
}

.live-dot {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: #ef4444;
  animation: landingPulse 1.6s ease-in-out infinite;
}

.stage-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #cbd5e1;
}

.rec {
  color: #fca5a5;
  font-size: 9px;
  letter-spacing: 0.08em;
}

.stage-grid {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 8px;
  aspect-ratio: 16 / 11;
}

.tile {
  position: relative;
  border-radius: 12px;
  background: linear-gradient(150deg, #1c2542, #131a2e);
  border: 1px solid #223055;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.tile-host {
  grid-row: span 2;
  background: linear-gradient(155deg, #1d3fa8, #14214f 70%);
  border-color: #2a4bb8;
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.02em;
}

.tile-name {
  position: absolute;
  left: 8px;
  bottom: 8px;
  font-size: 9px;
  font-weight: 700;
  color: #e2e8f0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: calc(100% - 30px);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.tile-name i {
  font-size: 8px;
  color: #fbbf24;
}

.tile-mic {
  position: absolute;
  right: 8px;
  bottom: 8px;
  font-size: 8px;
  color: #86efac;
}

.tile-mic.muted {
  color: #94a3b8;
}

.tile-check {
  flex-direction: column;
  gap: 3px;
  background: linear-gradient(160deg, rgba(251, 191, 36, 0.16), rgba(19, 26, 46, 0.9));
  border-color: rgba(251, 191, 36, 0.35);
}

.check-icon {
  color: #fbbf24;
  font-size: 13px;
}

.check-title {
  font-size: 9px;
  font-weight: 800;
  color: #fde68a;
}

.check-sub {
  font-size: 8px;
  color: #fcd34d;
  opacity: 0.85;
}

.stage-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding-top: 10px;
}

.stage-bar span {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: #172138;
  color: #93a4c3;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
}

.stage-bar span.on {
  background: rgba(24, 69, 212, 0.35);
  color: #bfdbfe;
}

.stage-bar span.end {
  background: #dc2626;
  color: #fff;
}

.stage-aside {
  position: absolute;
  right: -10px;
  bottom: -18px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  box-shadow: 0 18px 40px -22px rgba(13, 13, 26, 0.45);
  animation: landingFloat 7s ease-in-out infinite;
}

.stage-aside i {
  color: #10b981;
  font-size: 15px;
}

.stage-aside strong {
  display: block;
  font-size: 11px;
  font-weight: 800;
}

.stage-aside span {
  display: block;
  font-size: 9px;
  color: var(--muted);
}

/* -------------------------------------------------------------------- facts */
.facts {
  border-block: 1px solid var(--line);
  background: var(--wash);
}

.facts-inner {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 30px;
  justify-content: center;
  padding-block: 16px;
  font-size: 11px;
  font-weight: 700;
  color: var(--body);
}

.facts-inner i {
  color: var(--brand);
  margin-right: 6px;
}

/* ----------------------------------------------------------------- section */
.section {
  padding: 76px 0;
  /* Clears the fixed header when a section is scrolled to. */
  scroll-margin-top: 84px;
}

.section-alt {
  background: var(--wash);
  border-block: 1px solid var(--line);
}

.section-head {
  max-width: 620px;
  margin-bottom: 40px;
}

.kicker {
  font-size: 10px;
  font-weight: 900;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  color: var(--brand);
}

h2 {
  margin: 10px 0 0;
  font-family: 'Outfit', 'Inter', sans-serif;
  font-size: clamp(24px, 3.2vw, 36px);
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-weight: 800;
}

.section-head p {
  margin: 12px 0 0;
  color: var(--body);
  font-size: 14px;
  line-height: 1.6;
}

.cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.card {
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--paper);
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.card:hover {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--brand) 45%, var(--line));
  box-shadow: 0 20px 40px -30px rgba(13, 13, 26, 0.5);
}

.card-icon {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  margin-bottom: 14px;
}

.card-icon.blue { background: rgba(24, 69, 212, 0.1); color: #1845d4; }
.card-icon.amber { background: rgba(245, 158, 11, 0.12); color: #b45309; }
.card-icon.emerald { background: rgba(16, 185, 129, 0.12); color: #047857; }
.card-icon.violet { background: rgba(139, 92, 246, 0.12); color: #6d28d9; }
.card-icon.rose { background: rgba(244, 63, 94, 0.12); color: #be123c; }
.card-icon.slate { background: rgba(100, 116, 139, 0.14); color: #475569; }

.card h3 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: -0.01em;
}

.card p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--body);
}

/* ------------------------------------------------------------------- steps */
.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  counter-reset: step;
}

.steps li {
  padding: 22px;
  border-radius: 16px;
  border: 1px solid var(--line);
  background: var(--paper);
}

.step-number {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--brand);
  color: #fff;
  font-size: 11px;
  font-weight: 900;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}

.steps h3 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 800;
}

.steps p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--body);
}

/* ------------------------------------------------------------------ mobile */
.mobile-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  max-width: 720px;
}

.mobile-copy p {
  color: var(--body);
  font-size: 14px;
  line-height: 1.65;
  margin: 14px 0 0;
}

.checks {
  list-style: none;
  margin: 18px 0 0;
  padding: 0;
  display: grid;
  gap: 9px;
}

.checks li {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--body);
}

.checks i {
  color: #10b981;
  font-size: 10px;
  margin-top: 3px;
}

.note {
  font-size: 11px !important;
  color: var(--muted) !important;
  margin-top: 16px !important;
}

/* ----------------------------------------------------------------- closing */
.closing {
  padding: 20px 0 76px;
}

.closing-inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 32px;
  border-radius: 22px;
  background: linear-gradient(120deg, #10225e, #1845d4 70%);
  color: #fff;
}

.closing h2 {
  color: #fff;
  font-size: clamp(20px, 2.6vw, 28px);
}

.closing p {
  margin: 8px 0 0;
  color: rgba(255, 255, 255, 0.78);
  font-size: 12.5px;
  max-width: 42em;
}

.closing .btn-primary {
  background: #fff;
  color: #10225e;
  box-shadow: none;
}

.closing .btn-primary:hover {
  background: #e8eeff;
}

/* ------------------------------------------------------------------ footer */
.footer {
  border-top: 1px solid var(--line);
  background: var(--paper);
}

.footer-inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  padding-block: 22px;
}

.brand-footer .brand-mark {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  font-size: 11px;
  box-shadow: none;
}

.brand-footer .brand-name {
  font-size: 14px;
}

.footer-links {
  display: flex;
  gap: 18px;
  margin-left: auto;
}

.footer-links a,
.footer-link {
  color: var(--muted);
  text-decoration: none;
  font-size: 11px;
  font-weight: 600;
  background: none;
  border: 0;
  padding: 0;
  font-family: inherit;
  cursor: pointer;
}

.footer-links a:hover,
.footer-link:hover {
  color: var(--brand);
}

.copyright {
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  width: 100%;
}

@media (min-width: 720px) {
  .copyright {
    width: auto;
    margin-left: 18px;
  }
}

/* ------------------------------------------------------------------ reveal */
[data-reveal] {
  opacity: 0;
  transform: translateY(18px);
  transition: opacity 0.6s ease, transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

[data-reveal].is-visible {
  opacity: 1;
  transform: none;
}

@keyframes landingPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

@keyframes landingFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-7px); }
}

/* --------------------------------------------------------------- responsive */
@media (max-width: 980px) {
  .hero-grid,
  .mobile-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 34px;
  }

  .cards,
  .steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .hero {
    padding-top: 104px;
  }

  .stage-aside {
    position: static;
    margin-top: 12px;
    animation: none;
  }
}

@media (max-width: 720px) {
  .nav-links {
    display: none;
  }

  .cards,
  .steps {
    grid-template-columns: minmax(0, 1fr);
  }

  .hero {
    padding-top: 96px;
  }

  h1 {
    font-size: clamp(30px, 9vw, 40px);
  }

  .cta-row .btn {
    flex: 1 1 100%;
  }

  .closing-inner {
    padding: 24px;
  }

  .footer-links {
    margin-left: 0;
    width: 100%;
  }
}

/* Motion is decoration here; content stays readable without it. */
@media (prefers-reduced-motion: reduce) {
  [data-reveal] {
    opacity: 1;
    transform: none;
    transition: none;
  }

  .stage-aside,
  .live-dot {
    animation: none;
  }

  .card:hover {
    transform: none;
  }
}
</style>
