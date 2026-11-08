import type { ReactNode } from 'react';
import { AlertTriangle, Loader2 } from 'lucide-react';
import { ApiError } from '../api/client';

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 max-w-3xl text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ title, children, className = '', actions }: { title?: string; children: ReactNode; className?: string; actions?: ReactNode }) {
  return (
    <section className={`rounded-xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 ${className}`}>
      {(title || actions) && (
        <div className="mb-3 flex items-center justify-between gap-2">
          {title && <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">{title}</h2>}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

export function Stat({ label, value, hint, tone = 'neutral' }: { label: string; value: ReactNode; hint?: string; tone?: Tone }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</div>
      <div className={`mt-2 text-2xl font-semibold tabular-nums ${toneText[tone]}`}>{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500 dark:text-slate-400">{hint}</div>}
    </div>
  );
}

export type Tone = 'neutral' | 'good' | 'warn' | 'bad' | 'info';
const toneText: Record<Tone, string> = {
  neutral: 'text-slate-900 dark:text-slate-100',
  good: 'text-emerald-600 dark:text-emerald-400',
  warn: 'text-amber-600 dark:text-amber-400',
  bad: 'text-rose-600 dark:text-rose-400',
  info: 'text-sky-600 dark:text-sky-400',
};
const toneBadge: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700',
  good: 'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-950 dark:text-emerald-300 dark:ring-emerald-900',
  warn: 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:ring-amber-900',
  bad: 'bg-rose-50 text-rose-700 ring-rose-200 dark:bg-rose-950 dark:text-rose-300 dark:ring-rose-900',
  info: 'bg-sky-50 text-sky-700 ring-sky-200 dark:bg-sky-950 dark:text-sky-300 dark:ring-sky-900',
};

/** One mapping from backend status strings to colours, used on every page. */
export function toneOf(status: string | null | undefined): Tone {
  if (!status) return 'neutral';
  const s = status.toUpperCase();
  if (['CAPTURED', 'FULFILLED', 'PROCESSED', 'PUBLISHED', 'SUCCESS', 'PASSED', 'COMPLETED', 'AUTO_RESOLVED', 'PAID', 'ACCEPTED', 'RESOLVED_MANUALLY', 'ALREADY_CONSISTENT', 'UP'].includes(s)) return 'good';
  if (['FAILED', 'DEAD', 'PAYMENT_FAILED', 'PERMANENT_FAILURE', 'ERROR', 'CRITICAL', 'OPEN', 'DOWN'].includes(s) || s.startsWith('REJECTED') || s.includes('INVALID')) return 'bad';
  if (['RETRY_SCHEDULED', 'TRANSIENT_FAILURE', 'PENDING', 'PAYMENT_PENDING', 'RUNNING', 'WARNING', 'AUTHORIZED', 'REFUND_PENDING', 'SKIPPED_STALE'].includes(s)) return 'warn';
  if (['INFO', 'INFORMATIONAL', 'SKIPPED_ALREADY_PROCESSED', 'IGNORED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'CREATED', 'RECEIVED'].includes(s)) return 'info';
  return 'neutral';
}

export function Badge({ children, tone }: { children: ReactNode; tone?: Tone }) {
  const t = tone ?? toneOf(typeof children === 'string' ? children : undefined);
  return <span className={`inline-flex items-center rounded-md px-1.5 py-0.5 text-[11px] font-medium ring-1 ring-inset ${toneBadge[t]}`}>{children}</span>;
}

export function Button({ children, onClick, variant = 'primary', disabled, type = 'button', title }: {
  children: ReactNode; onClick?: () => void; variant?: 'primary' | 'secondary' | 'danger'; disabled?: boolean; type?: 'button' | 'submit'; title?: string;
}) {
  const styles = {
    primary: 'bg-indigo-600 text-white hover:bg-indigo-500 focus-visible:outline-indigo-600',
    secondary: 'bg-white text-slate-700 ring-1 ring-inset ring-slate-300 hover:bg-slate-50 dark:bg-slate-800 dark:text-slate-200 dark:ring-slate-700 dark:hover:bg-slate-700',
    danger: 'bg-rose-600 text-white hover:bg-rose-500 focus-visible:outline-rose-600',
  }[variant];
  return (
    <button type={type} title={title} onClick={onClick} disabled={disabled}
      className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium shadow-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${styles}`}>
      {children}
    </button>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return <div className="flex items-center gap-2 py-6 text-sm text-slate-500"><Loader2 className="h-4 w-4 animate-spin" aria-hidden /> {label}</div>;
}

export function ErrorBox({ error }: { error: unknown }) {
  const msg = error instanceof ApiError ? `${error.code}: ${error.message}` : error instanceof Error ? error.message : String(error);
  return (
    <div role="alert" className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800 dark:border-rose-900 dark:bg-rose-950 dark:text-rose-200">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden /> <span>{msg}</span>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="py-8 text-center text-sm text-slate-500 dark:text-slate-400">{children}</div>;
}

/** Renders backend evidence (objects / lists) as compact, readable key-value blocks. */
export function Evidence({ data }: { data: unknown }) {
  if (data == null || (typeof data === 'object' && Object.keys(data as object).length === 0)) return null;
  return (
    <pre className="mt-2 max-h-72 overflow-auto rounded-lg bg-slate-950 p-3 font-mono text-[11px] leading-relaxed text-slate-200">
      {JSON.stringify(data, null, 2)}
    </pre>
  );
}

export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="-mx-4 overflow-x-auto">
      <table className="min-w-full text-left text-sm">
        <thead>
          <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
            {head.map((h) => <th key={h} scope="col" className="whitespace-nowrap px-4 py-2 font-medium">{h}</th>)}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-slate-800">{children}</tbody>
      </table>
    </div>
  );
}

export const td = 'whitespace-nowrap px-4 py-2 align-top';
export const mono = 'font-mono text-xs';

export function Select({ value, onChange, options, label }: { value: string; onChange: (v: string) => void; options: string[]; label: string }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="text-slate-500">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}
        className="rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
        {options.map((o) => <option key={o} value={o}>{o || 'All'}</option>)}
      </select>
    </label>
  );
}

export function TextInput({ value, onChange, placeholder, label }: { value: string; onChange: (v: string) => void; placeholder?: string; label: string }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="sr-only">{label}</span>
      <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={label}
        className="w-72 rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-sm placeholder:text-slate-400 dark:border-slate-700 dark:bg-slate-900" />
    </label>
  );
}
