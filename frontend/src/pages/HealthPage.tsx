import { useHealth } from '../api/hooks';
import { Badge, Card, Empty, ErrorBox, Loading, PageHeader, Stat, Table, mono, td } from '../components/ui';
import { ago } from '../lib/format';

export function HealthPage() {
  const h = useHealth();
  if (h.isLoading) return <Loading />;
  if (h.error) return <ErrorBox error={h.error} />;
  const d = h.data!;
  return (
    <>
      <PageHeader title="System health" subtitle="Worker liveness is derived from the database (recent processing attempts), so it works even when workers run as separate processes." />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="PostgreSQL" value={<Badge>{d.database.up ? 'UP' : 'DOWN'}</Badge>} hint={`${d.database.latencyMs} ms round trip`} />
        <Stat label="RabbitMQ" value={<Badge>{d.rabbitmq.up ? 'UP' : 'DOWN'}</Badge>} hint={`queue ${d.rabbitmq.queueDepth} · DLQ ${d.rabbitmq.dlqDepth}`} />
        <Stat label="Outbox backlog" value={d.outbox.backlog} hint={`oldest due ${d.outbox.oldestDueAgeSeconds}s`} tone={d.outbox.oldestDueAgeSeconds > 30 ? 'warn' : 'neutral'} />
        <Stat label="Active workers (15 min)" value={d.workers.length} tone={d.workers.length ? 'good' : 'warn'} />
      </div>
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card title="Processing latency, last hour (per attempt)">
          <dl className="grid grid-cols-4 gap-2 text-center">
            {(['p50', 'p95', 'p99'] as const).map((k) => (
              <div key={k}><dt className="text-xs text-slate-500">{k}</dt><dd className="text-lg font-semibold tabular-nums">{Math.round(d.processingLatencyMs[k])} ms</dd></div>
            ))}
            <div><dt className="text-xs text-slate-500">samples</dt><dd className="text-lg font-semibold tabular-nums">{d.processingLatencyMs.samples}</dd></div>
          </dl>
          <p className="mt-3 text-xs text-slate-500">Webhook received → processed: p50 {Math.round(d.receiveToProcessedMs.p50)} ms, p95 {Math.round(d.receiveToProcessedMs.p95)} ms ({d.receiveToProcessedMs.samples} events). Measured on this machine; not a benchmark.</p>
        </Card>
        <Card title="Workers">
          {d.workers.length ? (
            <Table head={['Worker', 'Last seen', 'Attempts (15 min)']}>
              {d.workers.map((w) => (
                <tr key={w.workerId}><td className={`${td} ${mono}`}>{w.workerId}</td><td className={td}>{ago(w.lastSeen)}</td><td className={`${td} tabular-nums`}>{w.attempts15m}</td></tr>
              ))}
            </Table>
          ) : <Empty>No worker activity in the last 15 minutes (idle, or not running).</Empty>}
        </Card>
      </div>
      <p className="mt-4 text-xs text-slate-500">Prometheus metrics: <code>/actuator/prometheus</code> (admin) — e.g. payguard_webhooks_total, payguard_outbox_backlog, payguard_worker_processing_seconds, payguard_reconciliation_open_mismatches.</p>
    </>
  );
}
