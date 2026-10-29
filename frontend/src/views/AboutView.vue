<template>
  <div class="about">
    <header class="nav">
      <div class="shell nav-inner">
        <router-link to="/welcome" class="brand" aria-label="Podium home">
          <span class="brand-mark"><i class="fas fa-graduation-cap"></i></span>
          <span class="brand-name">Podium</span>
        </router-link>
        <router-link to="/welcome" class="btn btn-outline">
          <i class="fas fa-arrow-left"></i> Back
        </router-link>
      </div>
    </header>

    <main class="shell main">
      <span class="kicker">About</span>
      <h1>Classrooms, without the friction.</h1>
      <p class="lede">
        Podium is a virtual classroom for schools, colleges and universities. Live classes,
        attendance, materials and recordings live in one place, so a lesson is easy to run
        and easy to look back on afterwards.
      </p>

      <section class="panel">
        <h2>What you can do</h2>
        <ul class="list">
          <li><i class="fas fa-video"></i> Teach live with video, audio and screen sharing</li>
          <li><i class="fas fa-clipboard-check"></i> Track attendance and download the report</li>
          <li><i class="fas fa-folder-open"></i> Share documents, slides and links in class</li>
          <li><i class="fas fa-pen-ruler"></i> Work through problems on a shared whiteboard</li>
          <li><i class="fas fa-circle"></i> Record a class and keep it for later</li>
          <li><i class="fas fa-mobile-screen"></i> Use it on the web or on Android</li>
        </ul>
      </section>

      <section class="contact">
        <div class="contact-copy">
          <span class="kicker light">Contact</span>
          <h2>Talk to us</h2>
          <p>
            Questions about setting up a class, or need a hand during one?
            Message or call and we will help.
          </p>
        </div>

        <div class="contact-actions">
          <a class="contact-number" href="tel:+233550599755">
            <span class="label">Phone &amp; WhatsApp</span>
            <span class="number">0550 599 755</span>
          </a>
          <div class="buttons">
            <a class="btn btn-light" href="https://wa.me/233550599755" target="_blank" rel="noopener">
              <i class="fab fa-whatsapp"></i> WhatsApp
            </a>
            <a class="btn btn-ghost" href="tel:+233550599755">
              <i class="fas fa-phone"></i> Call
            </a>
          </div>
        </div>
      </section>

      <section class="panel">
        <h2>Web app</h2>
        <p class="muted">Open Podium in a browser, or install it to your home screen.</p>
        <a class="btn btn-primary" href="https://minatoflash82.ifnotus.space" target="_blank" rel="noopener">
          <i class="fas fa-arrow-up-right-from-square"></i> minatoflash82.ifnotus.space
        </a>
      </section>
    </main>

    <footer class="footer">
      <div class="shell footer-inner">
        <span class="brand-mini"><i class="fas fa-graduation-cap"></i> Podium</span>
        <nav class="footer-links" aria-label="Footer">
          <router-link to="/welcome">Home</router-link>
          <router-link to="/login">Sign in</router-link>
        </nav>
        <p class="copyright">&copy; 2026 Podium LMS</p>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts"></script>

<style scoped>
.about {
  --ink: #0d0d1a;
  --body: #444460;
  --muted: #8888a8;
  --line: #dde0f0;
  --paper: #ffffff;
  --wash: #f8f9fc;
  --brand: #1845d4;
  --brand-dark: #0f2fa8;

  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--paper);
  color: var(--ink);
  font-family: 'Inter', system-ui, sans-serif;
}

:global(html.dark) .about {
  --ink: #f1f5f9;
  --body: #cbd5e1;
  --muted: #94a3b8;
  --line: #1e293b;
  --paper: #020617;
  --wash: #0b1220;
}

.shell {
  width: 100%;
  max-width: 880px;
  margin: 0 auto;
  padding: 0 24px;
  box-sizing: border-box;
}

/* --------------------------------------------------------------------- nav */
.nav {
  border-bottom: 1px solid var(--line);
  background: var(--paper);
}

.nav-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-block: 14px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--brand);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.brand-name {
  font-family: 'Outfit', 'Inter', sans-serif;
  font-weight: 800;
  font-size: 19px;
  letter-spacing: -0.03em;
}

/* -------------------------------------------------------------------- body */
.main {
  flex: 1;
  padding-block: 48px 64px;
}

.kicker {
  font-size: 10px;
  font-weight: 900;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  color: var(--brand);
}

.kicker.light {
  color: #bfdbfe;
}

h1 {
  margin: 12px 0 0;
  font-family: 'Outfit', 'Inter', sans-serif;
  font-size: clamp(30px, 5vw, 44px);
  line-height: 1.05;
  letter-spacing: -0.035em;
  font-weight: 800;
}

h2 {
  margin: 0 0 10px;
  font-family: 'Outfit', 'Inter', sans-serif;
  font-size: 18px;
  letter-spacing: -0.02em;
  font-weight: 800;
}

.lede {
  margin: 16px 0 0;
  max-width: 40em;
  color: var(--body);
  font-size: 15px;
  line-height: 1.7;
}

.panel {
  margin-top: 28px;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--wash);
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
}

.list li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--body);
  font-weight: 600;
}

.list i {
  color: var(--brand);
  font-size: 11px;
  margin-top: 3px;
}

.muted {
  margin: 0 0 14px;
  color: var(--muted);
  font-size: 12.5px;
}

/* ----------------------------------------------------------------- contact */
.contact {
  margin-top: 28px;
  padding: 28px;
  border-radius: 20px;
  background: linear-gradient(120deg, #10225e, #1845d4 72%);
  color: #fff;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.9fr);
  gap: 24px;
  align-items: center;
}

.contact-copy p {
  margin: 10px 0 0;
  color: rgba(255, 255, 255, 0.82);
  font-size: 13px;
  line-height: 1.65;
  max-width: 28em;
}

.contact-actions {
  display: grid;
  gap: 14px;
}

.contact-number {
  display: block;
  text-decoration: none;
  color: #fff;
}

.contact-number .label {
  display: block;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.66);
  margin-bottom: 4px;
}

.contact-number .number {
  font-family: 'Outfit', 'Inter', sans-serif;
  font-size: 24px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* ----------------------------------------------------------------- buttons */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: 10px;
  padding: 11px 16px;
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
  white-space: nowrap;
  width: fit-content;
}

.btn-outline {
  border-color: var(--line);
  color: var(--body);
}

.btn-outline:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.btn-primary {
  background: var(--brand);
  color: #fff;
}

.btn-primary:hover {
  background: var(--brand-dark);
}

.btn-light {
  background: #fff;
  color: #10225e;
}

.btn-light:hover {
  background: #e8eeff;
}

.btn-ghost {
  border-color: rgba(255, 255, 255, 0.4);
  color: #fff;
}

.btn-ghost:hover {
  border-color: #fff;
  background: rgba(255, 255, 255, 0.12);
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
  gap: 14px;
  padding-block: 20px;
}

.brand-mini {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: 'Outfit', 'Inter', sans-serif;
  font-weight: 800;
  font-size: 14px;
}

.brand-mini i {
  color: var(--brand);
  font-size: 12px;
}

.footer-links {
  display: flex;
  gap: 16px;
  margin-left: auto;
}

.footer-links a {
  color: var(--muted);
  text-decoration: none;
  font-size: 11px;
  font-weight: 600;
}

.footer-links a:hover {
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
    margin-left: 16px;
  }
}

@media (max-width: 760px) {
  .list {
    grid-template-columns: minmax(0, 1fr);
  }

  .contact {
    grid-template-columns: minmax(0, 1fr);
    padding: 22px;
  }

  .main {
    padding-block: 32px 48px;
  }

  .footer-links {
    margin-left: 0;
    width: 100%;
  }
}
</style>
