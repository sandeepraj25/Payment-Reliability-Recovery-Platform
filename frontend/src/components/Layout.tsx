import { NavLink, Outlet } from 'react-router-dom';
import { useEffect, useState } from 'react';
import {
  Activity, BookOpenCheck, CreditCard, FlaskConical, GitCompareArrows, HeartPulse, Inbox, LayoutDashboard, LogOut,
  Moon, Network, ShieldCheck, Skull, Sun,
} from 'lucide-react';
import { useAuth } from '../lib/auth';

const NAV = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/payments', label: 'Payments', icon: CreditCard },
  { to: '/webhooks', label: 'Webhook events', icon: Inbox },
  { to: '/simulations', label: 'Failure lab', icon: FlaskConical },
  { to: '/reconciliation', label: 'Reconciliation', icon: GitCompareArrows },
  { to: '/dead-letters', label: 'Dead letters', icon: Skull },
  { to: '/health', label: 'System health', icon: HeartPulse },
  { to: '/architecture', label: 'Architecture', icon: Network },
  { to: '/demo', label: 'Interview demo', icon: BookOpenCheck },
];

function useTheme() {
  const [dark, setDark] = useState<boolean>(() => {
    try {
      const saved = localStorage.getItem('payguard.theme');
      if (saved) return saved === 'dark';
    } catch { /* ignore */ }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  });
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    try { localStorage.setItem('payguard.theme', dark ? 'dark' : 'light'); } catch { /* ignore */ }
  }, [dark]);
  return [dark, setDark] as const;
}

export function Layout() {
  const { session, logout } = useAuth();
  const [dark, setDark] = useTheme();
  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 hidden h-screen w-60 shrink-0 flex-col border-r border-slate-200 bg-white px-3 py-4 md:flex dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-6 flex items-center gap-2 px-2">
          <ShieldCheck className="h-6 w-6 text-indigo-600" aria-hidden />
          <div>
            <div className="text-sm font-semibold">PayGuard</div>
            <div className="text-[11px] text-slate-500">Payment reliability ops</div>
          </div>
        </div>
        <nav aria-label="Main" className="flex flex-1 flex-col gap-0.5">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end}
              className={({ isActive }) => `flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm ${isActive
                ? 'bg-indigo-50 font-medium text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300'
                : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'}`}>
              <Icon className="h-4 w-4" aria-hidden /> {label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-4 border-t border-slate-200 pt-3 text-xs dark:border-slate-800">
          <div className="flex items-center gap-2 px-2 text-slate-500"><Activity className="h-3.5 w-3.5" aria-hidden />
            {session?.username} · {session?.roles.join(', ')}</div>
          <div className="mt-2 flex gap-1">
            <button onClick={() => setDark(!dark)} aria-label="Toggle dark mode"
              className="flex flex-1 items-center justify-center gap-1 rounded-lg px-2 py-1.5 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800">
              {dark ? <Sun className="h-4 w-4" aria-hidden /> : <Moon className="h-4 w-4" aria-hidden />} {dark ? 'Light' : 'Dark'}
            </button>
            <button onClick={logout} aria-label="Sign out"
              className="flex flex-1 items-center justify-center gap-1 rounded-lg px-2 py-1.5 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800">
              <LogOut className="h-4 w-4" aria-hidden /> Sign out
            </button>
          </div>
        </div>
      </aside>
      <div className="min-w-0 flex-1">
        {/* compact nav for small screens */}
        <nav aria-label="Main (mobile)" className="flex gap-1 overflow-x-auto border-b border-slate-200 bg-white px-3 py-2 md:hidden dark:border-slate-800 dark:bg-slate-900">
          {NAV.map(({ to, label, end }) => (
            <NavLink key={to} to={to} end={end}
              className={({ isActive }) => `whitespace-nowrap rounded-md px-2 py-1 text-xs ${isActive ? 'bg-indigo-50 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300' : 'text-slate-600 dark:text-slate-300'}`}>
              {label}
            </NavLink>
          ))}
        </nav>
        <main className="mx-auto max-w-7xl px-4 py-6 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
