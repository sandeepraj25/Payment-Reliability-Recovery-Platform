import type { SimRun } from '../api/types';
import { Badge, Evidence, type Tone } from './ui';
import { CheckCircle2, XCircle } from 'lucide-react';

const PHASE: Record<string, { label: string; tone: Tone }> = {
  INITIAL: { label: '1 · Initial state', tone: 'neutral' },
  INJECT: { label: '2 · Injected failure', tone: 'bad' },
  OBSERVED: { label: '3 · Observed state', tone: 'warn' },
  RECOVERY: { label: '4 · Recovery mechanism', tone: 'info' },
  FINAL: { label: '5 · Final verified state', tone: 'good' },
  ERROR: { label: 'Error', tone: 'bad' },
};

/** Renders the timeline exactly as recorded by the backend (steps + DB evidence + invariants). */
export function SimTimeline({ run }: { run: SimRun }) {
  return (
    <div>
      <div className="mb-3 flex items-center gap-2 text-sm">
        <Badge>{run.status}</Badge> <span className="text-slate-500">run #{run.id} by {run.startedBy}</span>
      </div>
      <ol className="space-y-3">
        {run.steps.map((s, i) => {
          const p = PHASE[s.phase] ?? { label: s.phase, tone: 'neutral' as Tone };
          return (
            <li key={i} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
              <div className="flex flex-wrap items-center gap-2"><Badge tone={p.tone}>{p.label}</Badge>
                <span className="text-xs tabular-nums text-slate-500">+{(s.elapsedMs / 1000).toFixed(1)}s</span></div>
              <div className="mt-1 text-sm font-medium">{s.title}</div>
              {s.detail && <div className="text-xs text-slate-600 dark:text-slate-300">{s.detail}</div>}
              <Evidence data={s.evidence} />
            </li>
          );
        })}
        {run.status === 'RUNNING' && <li className="text-sm text-slate-500">Running on the backend…</li>}
      </ol>
      {run.invariants.length > 0 && (
        <div className="mt-4">
          <div className="mb-2 text-sm font-semibold">Invariants checked against the database</div>
          <ul className="space-y-1">
            {run.invariants.map((v, i) => (
              <li key={i} className="flex items-start gap-2 text-sm">
                {v.held ? <CheckCircle2 className="mt-0.5 h-4 w-4 text-emerald-600" aria-label="held" />
                  : <XCircle className="mt-0.5 h-4 w-4 text-rose-600" aria-label="violated" />}
                <span>{v.name}{v.detail && <span className="text-slate-500"> — {v.detail}</span>}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {run.error && <p className="mt-3 text-sm text-rose-600">{run.error}</p>}
    </div>
  );
}
