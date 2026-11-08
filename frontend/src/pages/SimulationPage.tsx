import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Play } from 'lucide-react';
import { post } from '../api/client';
import { useScenarios, useSimRun, useSimRuns } from '../api/hooks';
import { Badge, Button, Card, Empty, ErrorBox, Loading, PageHeader } from '../components/ui';
import { SimTimeline } from '../components/SimTimeline';
import { useAuth } from '../lib/auth';
import { ago } from '../lib/format';

export function SimulationPage() {
  const { faultsEnabled, isAdmin } = useAuth();
  const scenarios = useScenarios();
  const runs = useSimRuns();
  const [selected, setSelected] = useState<string | null>(null);
  const [runId, setRunId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const run = useSimRun(runId);
  const qc = useQueryClient();

  if (!faultsEnabled) {
    return (
      <>
        <PageHeader title="Failure simulation lab" />
        <Card><p className="text-sm">Fault injection is disabled on this backend (<code>PAYGUARD_FAULTS_ENABLED=false</code>), which is the safe default. Enable it only in local/demo environments.</p></Card>
      </>
    );
  }

  const start = async (id: string) => {
    setError(null);
    try {
      const r = await post<{ runId: number }>(`/api/simulations/${id}/runs`);
      setRunId(r.runId);
      qc.invalidateQueries({ queryKey: ['simRuns'] });
    } catch (e) {
      setError(e);
    }
  };

  const current = scenarios.data?.find((s) => s.id === selected);
  return (
    <>
      <PageHeader title="Failure simulation lab"
        subtitle="Each scenario injects a real fault into real backend code (worker, outbox, gateway, database path). The timeline below is recorded by the backend, with evidence queried from PostgreSQL at every step." />
      <div className="grid gap-4 xl:grid-cols-5">
        <Card title="Scenarios" className="xl:col-span-2">
          {scenarios.isLoading ? <Loading /> : scenarios.error ? <ErrorBox error={scenarios.error} /> : (
            <ul className="space-y-1">
              {scenarios.data!.map((s) => (
                <li key={s.id}>
                  <button onClick={() => setSelected(s.id)}
                    className={`w-full rounded-lg px-3 py-2 text-left text-sm ${selected === s.id ? 'bg-indigo-50 text-indigo-800 dark:bg-indigo-950 dark:text-indigo-200' : 'hover:bg-slate-50 dark:hover:bg-slate-800'}`}>
                    {s.title}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <div className="space-y-4 xl:col-span-3">
          {current ? (
            <Card title={current.title} actions={
              <Button onClick={() => start(current.id)} disabled={!isAdmin || run.data?.status === 'RUNNING'} title={isAdmin ? '' : 'admin only'}>
                <Play className="h-4 w-4" aria-hidden /> Execute
              </Button>}>
              <p className="text-sm">{current.explanation}</p>
              <p className="mt-2 text-sm text-slate-600 dark:text-slate-300"><span className="font-medium">Mechanism:</span> {current.mechanisms}</p>
              {error ? <div className="mt-3"><ErrorBox error={error} /></div> : null}
            </Card>
          ) : <Card><Empty>Pick a scenario.</Empty></Card>}
          {run.data && <Card title="Event flow & recovery timeline"><SimTimeline run={run.data} /></Card>}
          <Card title="Recent runs">
            {runs.data?.length ? (
              <ul className="divide-y divide-slate-100 text-sm dark:divide-slate-800">
                {runs.data.map((r) => (
                  <li key={r.id}>
                    <button onClick={() => setRunId(r.id)} className="flex w-full items-center gap-2 py-2 text-left">
                      <Badge>{r.status}</Badge> <span>{r.scenario}</span> <span className="ml-auto text-xs text-slate-500">#{r.id} · {ago(r.startedAt)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : <Empty>No runs yet.</Empty>}
          </Card>
        </div>
      </div>
    </>
  );
}
