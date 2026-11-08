import { useState, type FormEvent } from 'react';
import { ShieldCheck } from 'lucide-react';
import { useAuth } from '../lib/auth';
import { ApiError } from '../api/client';

export function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? 'Wrong username or password.' : 'Cannot reach the PayGuard backend.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <form onSubmit={submit} className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-5 flex items-center gap-2">
          <ShieldCheck className="h-7 w-7 text-indigo-600" aria-hidden />
          <div>
            <h1 className="text-lg font-semibold">PayGuard</h1>
            <p className="text-xs text-slate-500">Payment reliability, recovery &amp; reconciliation</p>
          </div>
        </div>
        <label className="block text-sm font-medium" htmlFor="username">Username</label>
        <input id="username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username"
          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-950" />
        <label className="mt-3 block text-sm font-medium" htmlFor="password">Password</label>
        <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password"
          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-950" />
        {error && <p role="alert" className="mt-3 text-sm text-rose-600">{error}</p>}
        <button type="submit" disabled={busy || !password}
          className="mt-5 w-full rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50">
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="mt-4 text-xs text-slate-500">Credentials come from your <code>.env</code> (PAYGUARD_ADMIN_USER / PAYGUARD_ADMIN_PASSWORD). They are kept only for this browser tab.</p>
      </form>
    </div>
  );
}
