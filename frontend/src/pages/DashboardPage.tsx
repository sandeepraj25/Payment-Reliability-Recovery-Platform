import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Link } from 'react-router-dom';
import { useSummary, useTimeseries } from '../api/hooks';
import { Badge, Card, ErrorBox, Loading, PageHeader, Stat } from '../components/ui';
import { ago } from '../lib/format';

const axis = { fontSize: 11, fill: '#64748b' };

export function DashboardPage() {
  const summary = useSummary();
  const series = useTimeseries();
  if (summary.isLoading) return <Loading />;
  if (summary.error) return <ErrorBox error={summary.error} />;
  const s = summary.data!;
  const statusBars = Object.entries(s.paymentsByStatus).map(([status, count]) => ({ status, count }));
  const last = s.lastReconciliation;

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Live numbers from PostgreSQL and RabbitMQ, refreshed every 5 seconds." />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Payments" value={s.totalPayments} hint={`${s.fulfilments} orders fulfilled`} />
        <Stat label="Successful" value={s.successfulPayments} tone="good" hint="captured / refunded" />
        <Stat label="Pending" value={s.pendingPayments} tone={s.pendingPayments ? 'warn' : 'neutral'} hint="created / authorized" />
        <Stat label="Failed events" value={s.failedEvents} tone={s.failedEvents ? 'bad' : 'neutral'} hint="dead-lettered" />
        <Stat label="Recovered" value={s.recoveredByReconciliation + s.replayedDeadLetters} tone="info"
          hint={`${s.recoveredByReconciliation} reconciliation · ${s.replayedDeadLetters} replays`} />
        <Stat label="Outbox backlog" value={s.outboxBacklog} tone={s.outboxBacklog > 50 ? 'warn' : 'neutral'}
          hint={s.outboxOldestDueAgeSeconds ? `oldest due ${s.outboxOldestDueAgeSeconds}s` : 'nothing waiting'} />
        <Stat label="Retry queue" value={s.retryScheduled} tone={s.retryScheduled ? 'warn' : 'neutral'}
          hint={`broker queue ${s.queueDepth < 0 ? 'unreachable' : s.queueDepth} · DLQ ${s.dlqDepth < 0 ? '?' : s.dlqDepth}`} />
        <Stat label="Open mismatches" value={s.openMismatches} tone={s.openMismatches ? 'bad' : 'good'} hint="need manual review" />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <Card title="Webhook & worker activity (last 60 min)" className="lg:col-span-2">
          {series.data ? (
            <div className="h-64">
              <ResponsiveContainer>
                <LineChart data={series.data}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis dataKey="minute" tick={axis} interval={9} />
                  <YAxis tick={axis} allowDecimals={false} width={32} />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Line type="monotone" dataKey="received" stroke="#6366f1" dot={false} strokeWidth={2} />
                  <Line type="monotone" dataKey="processed" stroke="#10b981" dot={false} strokeWidth={2} />
                  <Line type="monotone" dataKey="skipped" stroke="#0ea5e9" dot={false} />
                  <Line type="monotone" dataKey="failed" stroke="#f59e0b" dot={false} />
                  <Line type="monotone" dataKey="rejected" stroke="#e11d48" dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          ) : <Loading />}
        </Card>
        <Card title="Merchant payments by status">
          <div className="h-64">
            <ResponsiveContainer>
              <BarChart data={statusBars} layout="vertical" margin={{ left: 40 }}>
                <XAxis type="number" tick={axis} allowDecimals={false} />
                <YAxis type="category" dataKey="status" tick={axis} width={120} />
                <Tooltip />
                <Bar dataKey="count" fill="#6366f1" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card title="Safety counters">
          <dl className="grid grid-cols-2 gap-y-2 text-sm">
            <dt className="text-slate-500">Duplicate deliveries absorbed</dt><dd className="text-right tabular-nums">{s.duplicateDeliveries}</dd>
            <dt className="text-slate-500">Rejected webhooks (bad signature / payload)</dt><dd className="text-right tabular-nums">{s.rejectedWebhooks}</dd>
            <dt className="text-slate-500">Orders by status</dt>
            <dd className="flex flex-wrap justify-end gap-1">{Object.entries(s.ordersByStatus).map(([k, v]) => <Badge key={k}>{`${k} ${v}`}</Badge>)}</dd>
          </dl>
        </Card>
        <Card title="Last reconciliation" actions={<Link className="text-xs text-indigo-600 hover:underline" to="/reconciliation">Open</Link>}>
          {last ? (
            <div className="text-sm">
              <div className="flex items-center gap-2">Run #{last.id} <Badge>{last.status}</Badge> <span className="text-slate-500">{ago(last.startedAt)}</span></div>
              <div className="mt-2 text-slate-600 dark:text-slate-300">{last.mismatchesFound} findings · {last.autoResolved} auto-resolved · {last.manualReview} for review</div>
            </div>
          ) : <p className="text-sm text-slate-500">No run yet.</p>}
        </Card>
      </div>
    </>
  );
}
