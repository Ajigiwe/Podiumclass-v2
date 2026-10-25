(function () {
  if (!('serviceWorker' in navigator)) return;

  let deferredPrompt;
  const installButton = document.getElementById('pwa-install-btn');
  const updateBanner = document.getElementById('pwa-update-banner');

  // Register service worker
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').then(
      function (registration) {
        console.log('SW registered:', registration.scope);

        // Check for updates
        registration.addEventListener('updatefound', function () {
          const newWorker = registration.installing;
          if (!newWorker) return;

          newWorker.addEventListener('statechange', function () {
            if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
              if (updateBanner) {
                updateBanner.classList.remove('hidden');
              }
            }
          });
        });
      },
      function (err) {
        console.log('SW registration failed:', err);
      }
    );

    // Handle SW update on controller change
    navigator.serviceWorker.addEventListener('controllerchange', function () {
      window.location.reload();
    });
  }

  // Track install prompt
  window.addEventListener('beforeinstallprompt', function (event) {
    event.preventDefault();
    deferredPrompt = event;

    if (installButton) {
      installButton.classList.remove('hidden');
    }
  });

  // Install button click
  if (installButton) {
    installButton.addEventListener('click', async function () {
      if (!deferredPrompt) return;
      deferredPrompt.prompt();
      const result = await deferredPrompt.userChoice;
      deferredPrompt = null;

      if (result.outcome === 'accepted') {
        installButton.classList.add('hidden');
      }
    });
  }

  // Hide install button if already in standalone
  if (window.matchMedia('(display-mode: standalone)').matches) {
    if (installButton) {
      installButton.classList.add('hidden');
    }
  }

  // Track installation
  window.addEventListener('appinstalled', function () {
    deferredPrompt = null;
    if (installButton) {
      installButton.classList.add('hidden');
    }
  });
})();
