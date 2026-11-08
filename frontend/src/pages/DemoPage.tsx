import { useState } from 'react';
import { ChevronLeft, ChevronRight, Play } from 'lucide-react';
import { post } from '../api/client';
import { useScenarios, useSimRun } from '../api/hooks';
import type { SimRun, SimStep } from '../api/types';
import { Badge, Button, Card, Empty, Evidence, Loading, PageHeader, Select } from '../components/ui';
import { useAuth } from '../lib/auth';

/**
 * Guided walkthrough for an interview. It RUNS the chosen scenario on the backend, then explains it
 * using only what the backend recorded (steps, evidence, invariants) - nothing here is pre-written output.
 */
export function DemoPage() {
  const { faultsEnabled, isAdmin } = useAuth();
  const scenarios = useScenarios();
  const [scenario, setScenario] = useState('MISSING_WEBHOOK');
  const [runId, setRunId] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const run = useSimRun(runId);

  if (!faultsEnabled) return <><PageHeader title="Interview demo" /><Card><p className="text-sm">Needs a backend with PAYGUARD_FAULTS_ENABLED=true (local/demo only).</p></Card></>;

  const start = async () => {
    setPage(0);
    const r = await post<{ runId: number }>(`/api/simulations/${scenario}/runs`);
    setRunId(r.runId);
  };
  const meta = scenarios.data?.find((s) => s.id === scenario);

  return (
    <>
      <PageHeader title="Interview demo mode" subtitle="Pick a failure, run it for real, then walk through what happened - every answer below comes from the recorded backend run."
        actions={<>
          {scenarios.data && <Select label="Scenario" value={scenario} onChange={(v) => { setScenario(v); setRunId(null); }} options={scenarios.data.map((s) => s.id)} />}
          <Button onClick={start} disabled={!isAdmin || run.data?.status === 'RUNNING'}><Play className="h-4 w-4" aria-hidden /> Run</Button>
        </>} />
      {!runId ? <Card><Empty>{meta ? meta.explanation : 'Choose a scenario and press Run.'}</Empty></Card>
        : !run.data ? <Loading /> : run.data.status === 'RUNNING' ? <Loading label="The backend is executing the scenario…" />
          : <Walkthrough run={run.data} page={page} setPage={setPage} why={meta?.explanation ?? ''} mechanism={meta?.mechanisms ?? ''} />}
    </>
  );
}

function stepsOf(run: SimRun, phase: SimStep['phase']) {
  return run.steps.filter((s) => s.phase === phase);
}

function Walkthrough({ run, page, setPage, why, mechanism }: { run: SimRun; page: number; setPage: (n: number) => void; why: string; mechanism: string }) {
  const pages: { q: string; body: JSX.Element }[] = [
    { q: '1. What went wrong?', body: <Steps steps={[...stepsOf(run, 'INJECT'), ...stepsOf(run, 'OBSERVED')]} /> },
    { q: '2. Why did it happen?', body: <p className="text-sm leading-relaxed">{why} In distributed systems networks drop, delay and duplicate messages and processes crash between any two lines - so the design assumes every step can fail.</p> },
    { q: '3. Which backend component detected it?', body: <p className="text-sm leading-relaxed">{mechanism}</p> },
    { q: '4. How did recovery occur?', body: <Steps steps={stepsOf(run, 'RECOVERY')} /> },
    { q: '5. How was duplicate processing prevented?', body: (
      <ul className="space-y-1 text-sm">{run.invariants.map((v, i) => (
        <li key={i} className="flex items-center gap-2"><Badge tone={v.held ? 'good' : 'bad'}>{v.held ? 'held' : 'VIOLATED'}</Badge>{v.name}<span className="text-slate-500">{v.detail}</span></li>))}</ul>) },
    { q: '6. Which database records prove correctness?', body: <Steps steps={stepsOf(run, 'FINAL').length ? stepsOf(run, 'FINAL') : run.steps.slice(-1)} /> },
  ];
  const p = pages[page];
  return (
    <Card title={`Run #${run.id} · ${run.scenario}`} actions={<Badge>{run.status}</Badge>}>
      <h2 className="text-base font-semibold">{p.q}</h2>
      <div className="mt-3">{p.body}</div>
      <div className="mt-5 flex items-center justify-between">
        <Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}><ChevronLeft className="h-4 w-4" aria-hidden /> Back</Button>
        <span className="text-xs text-slate-500">{page + 1} / {pages.length}</span>
        <Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page === pages.length - 1}>Next <ChevronRight className="h-4 w-4" aria-hidden /></Button>
      </div>
    </Card>
  );
}

function Steps({ steps }: { steps: SimStep[] }) {
  if (!steps.length) return <p className="text-sm text-slate-500">No step of this kind was recorded for this scenario.</p>;
  return (
    <div className="space-y-3">
      {steps.map((s, i) => (
        <div key={i}>
          <div className="text-sm font-medium">{s.title}</div>
          {s.detail && <div className="text-xs text-slate-600 dark:text-slate-300">{s.detail}</div>}
          <Evidence data={s.evidence} />
        </div>
      ))}
    </div>
  );
}
