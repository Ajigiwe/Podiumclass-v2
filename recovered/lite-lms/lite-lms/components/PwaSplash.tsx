'use client';

import { useEffect, useState } from 'react';

export default function PwaSplash() {
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => setVisible(false), 1500);
    return () => clearTimeout(timer);
  }, []);

  if (!visible) return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 99999,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#1845D4',
        transition: 'opacity 0.4s ease',
      }}
      className={visible ? '' : 'opacity-0 pointer-events-none'}
    >
      <div
        style={{
          width: 72,
          height: 72,
          background: 'rgba(255,255,255,0.2)',
          borderRadius: 22,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 24,
          animation: 'pwaPulse 1.5s ease-in-out infinite',
        }}
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="white"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          style={{ width: 36, height: 36 }}
        >
          <path d="M22 10v6M2 10l10-5 10 5-10 5z" />
          <path d="M6 12v5c0 2 4 3 6 3s6-1 6-3v-5" />
        </svg>
      </div>
      <div style={{ color: '#fff', fontFamily: 'Inter, system-ui, sans-serif', fontSize: 28, fontWeight: 800, letterSpacing: -0.5, marginBottom: 4 }}>
        Podium
      </div>
      <div style={{ color: 'rgba(255,255,255,0.7)', fontFamily: 'Inter, system-ui, sans-serif', fontSize: 13, fontWeight: 500 }}>
        Elevate Your Learning
      </div>
      <div style={{ marginTop: 32, width: 40, height: 4, background: 'rgba(255,255,255,0.2)', borderRadius: 2, overflow: 'hidden' }}>
        <div
          style={{
            width: '40%',
            height: '100%',
            background: '#fff',
            borderRadius: 2,
            animation: 'pwaLoading 1s ease-in-out infinite',
          }}
        />
      </div>
      <style>{`
        @keyframes pwaPulse {
          0%, 100% { transform: scale(1); }
          50% { transform: scale(1.05); }
        }
        @keyframes pwaLoading {
          0% { transform: translateX(-100%); }
          100% { transform: translateX(250%); }
        }
      `}</style>
    </div>
  );
}
