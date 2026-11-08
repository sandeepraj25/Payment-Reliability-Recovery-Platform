import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthProvider } from './lib/auth';
import './index.css';

// Apply the theme before the first paint (login screen included): saved choice, else the OS preference.
(() => {
  let dark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  try {
    const saved = localStorage.getItem('payguard.theme');
    if (saved) dark = saved === 'dark';
  } catch { /* storage unavailable */ }
  document.documentElement.classList.toggle('dark', dark);
})();

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
