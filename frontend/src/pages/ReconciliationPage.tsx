import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Download, Play } from 'lucide-react';
import { api, loadSession, post } from '../api/client';
import { useMismatches, useReconRuns } from '../api/hooks';
import type { Mismatch } from '../api/types';
import { Badge, Button, Card, Empty, ErrorBox, Evidence, Loading, PageHeader, Select, Table, mono, td } from '../components/ui';
import { useAuth } from '../lib/auth';
import { short, time } from '../lib/format';

export function ReconciliationPage() {
  const { isAdmin } = useAuth();
  const runs = useReconRuns();
  const [runId, setRunId] = useState<number | null>(null);
  const [resolution, setResolution] = useState('');
  const [selected, setSelected] = useState<Mismatch | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const mismatches = useMismatches(runId, resolution);
  const qc = useQueryClient();

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await post<{ id: number }>('/api/reconciliation/runs');
      setRunId(r.id);
      qc.invalidateQueries();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const exportCsv = async (id: number) => {
    // fetch with the Authorization header, then hand the CSV to the browser as a download
    const res = await fetch(`/api/reconciliation/runs/${id}/report.csv`, { headers: { Authorization: `Basic ${loadSession()?.token}` } });
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = `reconciliation-run-${id}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <>
      <PageHeader title="Reconciliation center"
        subtitle="Compares the merchant database, the gateway's authoritative records and settlement reports. Only provably safe cases are fixed automatically; everything ambiguous waits here for a human."
        actions={<>
          {runId != null && <Button variant="secondary" onClick={() => exportCsv(runId)}><Download className="h-4 w-4" aria-hidden /> Export CSV</Button>}
          <Button onClick={start} disabled={!isAdmin || busy}><Play className="h-4 w-4" aria-hidden /> {busy ? 'Running…' : 'Start reconciliation'}</Button>
        </>} />
      {error ? <div className="mb-3"><ErrorBox error={error} /></div> : null}
      <div className="grid gap-4 xl:grid-cols-5">
        <Card title="Runs" className="xl:col-span-2">
          {runs.isLoading ? <Loading /> : runs.data?.length ? (
            <ul className="divide-y divide-slate-100 text-sm dark:divide-slate-800">
              <li><button className={`w-full py-2 text-left ${runId == null ? 'font-medium text-indigo-600' : ''}`} onClick={() => setRunId(null)}>All runs</button></li>
              {runs.data.map((r) => (
                <li key={r.id}>
                  <button onClick={() => setRunId(r.id)} className={`w-full py-2 text-left ${runId === r.id ? 'text-indigo-600' : ''}`}>
                    <div className="flex items-center gap-2">#{r.id} <Badge>{r.status}</Badge><span className="text-xs text-slate-500">{r.triggerType} · {time(r.startedAt)}</span></div>
                    <div className="text-xs text-slate-500">{r.mismatchesFound} found · {r.autoResolved} auto · {r.manualReview} review · {r.merchantPayments}/{r.gatewayPayments} payments</div>
                  </button>
                </li>
              ))}
            </ul>
          ) : <Empty>No runs yet.</Empty>}
        </Card>
        <Card title="Mismatches" className="xl:col-span-3"
          actions={<Select label="Resolution" value={resolution} onChange={setResolution}
            options={['', 'OPEN', 'AUTO_RESOLVED', 'ALREADY_CONSISTENT', 'INFORMATIONAL', 'RESOLVED_MANUALLY']} />}>
          {mismatches.data?.length ? (
            <Table head={['Type', 'Severity', 'Resolution', 'Gateway id', 'Merchant', 'Gateway']}>
              {mismatches.data.map((m) => (
                <tr key={m.id} onClick={() => setSelected(m)} className="cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/60">
                  <td className={`${td} text-xs font-medium`}>{m.mismatchType}</td>
                  <td className={td}><Badge>{m.severity}</Badge></td>
                  <td className={td}><Badge>{m.resolution}</Badge></td>
                  <td className={`${td} ${mono}`}>{short(m.gatewayPaymentId, 14)}</td>
                  <td className={`${td} text-xs`}>{m.merchantValue}</td>
                  <td className={`${td} text-xs`}>{m.gatewayValue}</td>
                </tr>
              ))}
            </Table>
          ) : <Empty>No mismatches.</Empty>}
        </Card>
      </div>
      {selected && <MismatchDetail m={selected} canResolve={isAdmin} onDone={() => { setSelected(null); qc.invalidateQueries(); }} />}
    </>
  );
}

function MismatchDetail({ m, canResolve, onDone }: { m: Mismatch; canResolve: boolean; onDone: () => void }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(null);
  const resolve = async () => {
    try {
      await api(`/api/reconciliation/mismatches/${m.id}/resolve`, { method: 'POST', body: JSON.stringify({ note }) });
      onDone();
    } catch (e) {
      setError(e);
    }
  };
  let evidence: unknown = m.evidence;
  try { evidence = JSON.parse(m.evidence); } catch { /* keep text */ }
  return (
    <Card className="mt-4" title={`Mismatch #${m.id}: ${m.mismatchType}`}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="text-sm">
          <div>Resolution: <Badge>{m.resolution}</Badge> {m.resolvedBy && <span className="text-xs text-slate-500">by {m.resolvedBy}</span>}</div>
          {m.resolutionNote && <p className="mt-2 text-slate-600 dark:text-slate-300">{m.resolutionNote}</p>}
          <p className="mt-2 text-xs text-slate-500">order {m.orderId ?? '—'} · merchant payment {m.merchantPaymentId ?? '—'}</p>
          {m.resolution === 'OPEN' && canResolve && (
            <div className="mt-3">
              <label className="text-sm font-medium" htmlFor="note">Resolution note (audited, required)</label>
              <textarea id="note" value={note} onChange={(e) => setNote(e.target.value)} rows={3}
                className="mt-1 w-full rounded-lg border border-slate-300 bg-white p-2 text-sm dark:border-slate-700 dark:bg-slate-950" />
              <div className="mt-2"><Button onClick={resolve} disabled={!note.trim()}>Mark resolved</Button></div>
              {error ? <div className="mt-2"><ErrorBox error={error} /></div> : null}
            </div>
          )}
        </div>
        <div><div className="text-sm font-medium">Evidence</div><Evidence data={evidence} /></div>
      </div>
    </Card>
  );
}
