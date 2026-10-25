'use client';

import { useEffect, useState } from 'react';
import { X, Download, RefreshCw } from 'lucide-react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export default function PwaInstallPrompt() {
  const [showInstall, setShowInstall] = useState(false);
  const [showUpdate, setShowUpdate] = useState(false);
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  // Read the display mode in a lazy initializer instead of syncing it from an effect.
  // Guarded for SSR, where matchMedia is unavailable.
  const [isStandalone] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(display-mode: standalone)').matches
  );

  useEffect(() => {
    if (typeof window === 'undefined' || isStandalone) return;

    const handleBeforeInstall = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
      setShowInstall(true);
    };

    const handleAppInstalled = () => {
      setShowInstall(false);
      setDeferredPrompt(null);
    };

    window.addEventListener('beforeinstallprompt', handleBeforeInstall);
    window.addEventListener('appinstalled', handleAppInstalled);

    return () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstall);
      window.removeEventListener('appinstalled', handleAppInstalled);
    };
  }, [isStandalone]);

  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;

    navigator.serviceWorker.ready.then((registration) => {
      registration.addEventListener('updatefound', () => {
        const newWorker = registration.installing;
        if (!newWorker) return;

        newWorker.addEventListener('statechange', () => {
          if (
            newWorker.state === 'installed' &&
            navigator.serviceWorker.controller
          ) {
            setShowUpdate(true);
          }
        });
      });
    });
  }, []);

  const handleInstall = async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const result = await deferredPrompt.userChoice;
    if (result.outcome === 'accepted') {
      setShowInstall(false);
    }
    setDeferredPrompt(null);
  };

  const handleUpdate = () => {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.ready.then((registration) => {
      if (registration.waiting) {
        registration.waiting.postMessage({ type: 'SKIP_WAITING' });
      }
    });
    window.location.reload();
  };

  if (isStandalone && !showUpdate) return null;

  return (
    <>
      {showInstall && (
        <div className="fixed bottom-4 left-4 right-4 z-50 bg-white rounded-xl border border-gray-200 shadow-lg p-4 flex items-center gap-3 animate-in slide-in-from-bottom duration-300">
          <div className="w-10 h-10 bg-blue-600 rounded-xl flex items-center justify-center flex-shrink-0">
            <Download className="w-5 h-5 text-white" />
          </div>
          <div className="flex-1 min-w-0">
            <h4 className="text-sm font-semibold text-gray-900">Install Podium</h4>
            <p className="text-xs text-gray-500">Add to your home screen for quick access</p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              onClick={() => setShowInstall(false)}
              className="p-2 text-gray-400 hover:text-gray-600 transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
            <button
              onClick={handleInstall}
              className="px-4 py-2 bg-blue-600 text-white text-xs font-semibold rounded-lg active:scale-95 transition-transform"
            >
              Install
            </button>
          </div>
        </div>
      )}

      {showUpdate && (
        <div className="fixed bottom-4 left-4 right-4 z-50 bg-amber-50 border border-amber-200 rounded-xl shadow-lg p-4 flex items-center gap-3 animate-in slide-in-from-bottom duration-300">
          <div className="w-10 h-10 bg-amber-100 rounded-xl flex items-center justify-center flex-shrink-0">
            <RefreshCw className="w-5 h-5 text-amber-600" />
          </div>
          <div className="flex-1 min-w-0">
            <h4 className="text-sm font-semibold text-gray-900">Update available</h4>
            <p className="text-xs text-gray-500">A new version of Podium is ready</p>
          </div>
          <button
            onClick={handleUpdate}
            className="px-4 py-2 bg-amber-600 text-white text-xs font-semibold rounded-lg active:scale-95 transition-transform flex-shrink-0"
          >
            Update
          </button>
        </div>
      )}
    </>
  );
}
