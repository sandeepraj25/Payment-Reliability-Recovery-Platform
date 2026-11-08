import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { RotateCcw } from 'lucide-react';
import { post } from '../api/client';
import { useAttempts, useDeadLetters } from '../api/hooks';
import { Badge, Button, Card, Empty, ErrorBox, Loading, PageHeader, Table, mono, td } from '../components/ui';
import { useAuth } from '../lib/auth';
import { short, time } from '../lib/format';

export function DeadLettersPage() {
  const { isAdmin } = useAuth();
  const dead = useDeadLetters();
  const [selected, setSelected] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const attempts = useAttempts(selected);
  const qc = useQueryClient();

  const replay = async () => {
    setError(null);
    try {
      await post(`/api/dead-letters/${selected}/replay`, { reason });
      setDone(`Event ${selected} re-queued through the outbox. Watch it in Webhook events.`);
      setSelected(null);
      setReason('');
      qc.invalidateQueries();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <PageHeader title="Dead-letter queue"
        subtitle="Events the worker gave up on (permanent failure, or retries exhausted). Replay is safe: it re-runs the normal guarded path, never forces a state. Every replay is audited." />
      {done && <p className="mb-3 text-sm text-emerald-600">{done}</p>}
      <div className="grid gap-4 xl:grid-cols-5">
        <Card className="xl:col-span-3" title={`Dead events · broker DLQ depth ${dead.data?.brokerDlqDepth ?? '…'}`}>
          {dead.isLoading ? <Loading /> : dead.error ? <ErrorBox error={dead.error} /> : dead.data!.events.length === 0 ? <Empty>Nothing dead-lettered.</Empty> : (
            <Table head={['Event', 'Type', 'Gateway id', 'Attempts', 'Failure reason', 'Received']}>
              {dead.data!.events.map((e) => (
                <tr key={e.webhookEventId} onClick={() => setSelected(e.webhookEventId)} className="cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/60">
                  <td className={`${td} ${mono}`}>{short(e.providerEventId, 14)}</td>
                  <td className={td}>{e.eventType}</td>
                  <td className={`${td} ${mono}`}>{short(e.gatewayPaymentId, 14)}</td>
                  <td className={`${td} tabular-nums`}>{e.attemptCount}</td>
                  <td className={`${td} max-w-xs truncate text-xs text-rose-600`} title={e.lastError ?? ''}>{e.lastError}</td>
                  <td className={`${td} text-xs text-slate-500`}>{time(e.receivedAt)}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
        <Card className="xl:col-span-2" title={selected ? `Event #${selected}` : 'Inspect'}>
          {selected == null ? <Empty>Select an event.</Empty> : (
            <>
              <ul className="mb-4 space-y-1 text-sm">
                {attempts.data?.map((a) => (
                  <li key={a.id}>#{a.attemptNo} <Badge>{a.outcome}</Badge> <span className="text-xs text-rose-600">{a.errorClass}</span>
                    <div className="text-xs text-slate-500">{a.errorMessage}</div></li>
                ))}
              </ul>
              {isAdmin ? (
                <>
                  <label className="text-sm font-medium" htmlFor="reason">Reason for replay (audited, required)</label>
                  <textarea id="reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={3}
                    className="mt-1 w-full rounded-lg border border-slate-300 bg-white p-2 text-sm dark:border-slate-700 dark:bg-slate-950" />
                  <div className="mt-2"><Button onClick={replay} disabled={!reason.trim()}><RotateCcw className="h-4 w-4" aria-hidden /> Replay safely</Button></div>
                </>
              ) : <p className="text-sm text-slate-500">Replays need the ADMIN role.</p>}
              {error ? <div className="mt-2"><ErrorBox error={error} /></div> : null}
            </>
          )}
        </Card>
      </div>
    </>
  );
}
