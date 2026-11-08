import { useState } from 'react';
import { usePaymentTimeline, usePayments } from '../api/hooks';
import { Badge, Card, Empty, ErrorBox, Loading, PageHeader, Select, Table, TextInput, mono, td } from '../components/ui';
import { money, short, time } from '../lib/format';

const STATUSES = ['', 'CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED'];

export function PaymentsPage() {
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const list = usePayments(status, q);

  return (
    <>
      <PageHeader title="Payment explorer" subtitle="Merchant payments. Select one to compare it with the gateway's authoritative record and see its full lifecycle." />
      <div className="mb-3 flex flex-wrap gap-3">
        <TextInput label="Search" value={q} onChange={setQ} placeholder="payment id, order id or gateway id (pay_…)" />
        <Select label="Status" value={status} onChange={setStatus} options={STATUSES} />
      </div>
      <div className="grid gap-4 xl:grid-cols-5">
        <Card className="xl:col-span-3">
          {list.isLoading ? <Loading /> : list.error ? <ErrorBox error={list.error} /> : list.data!.length === 0 ? <Empty>No payments match.</Empty> : (
            <Table head={['Payment', 'Gateway id', 'Amount', 'Merchant status', 'Order', 'Created']}>
              {list.data!.map((p) => (
                <tr key={p.id} onClick={() => setSelected(p.id)} tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && setSelected(p.id)}
                  className={`cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/60 ${selected === p.id ? 'bg-indigo-50/60 dark:bg-indigo-950/40' : ''}`}>
                  <td className={`${td} ${mono}`}>{short(p.id)}</td>
                  <td className={`${td} ${mono}`}>{short(p.gatewayPaymentId, 14)}</td>
                  <td className={`${td} tabular-nums`}>{money(p.amountMinor, p.currency)}</td>
                  <td className={td}><Badge>{p.status}</Badge> <span className="text-xs text-slate-400">v{p.gatewayVersion}</span></td>
                  <td className={td}><Badge>{p.orderStatus}</Badge></td>
                  <td className={`${td} text-xs text-slate-500`}>{time(p.createdAt)}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
        <div className="xl:col-span-2">{selected ? <PaymentDetail id={selected} /> : <Card><Empty>Select a payment.</Empty></Card>}</div>
      </div>
    </>
  );
}

function PaymentDetail({ id }: { id: string }) {
  const t = usePaymentTimeline(id);
  if (t.isLoading) return <Card><Loading /></Card>;
  if (t.error) return <ErrorBox error={t.error} />;
  const { payment, gateway, timeline, audit } = t.data!;
  const agree = gateway && !gateway.error && gateway.status === payment.status && gateway.version === payment.gatewayVersion;
  return (
    <div className="space-y-4">
      <Card title="Gateway vs merchant">
        <div className="grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-lg bg-slate-50 p-3 dark:bg-slate-800/60">
            <div className="text-xs text-slate-500">Merchant (our DB)</div>
            <div className="mt-1"><Badge>{payment.status}</Badge> v{payment.gatewayVersion}</div>
            <div className="mt-1 tabular-nums">{money(payment.amountMinor, payment.currency)}</div>
          </div>
          <div className="rounded-lg bg-slate-50 p-3 dark:bg-slate-800/60">
            <div className="text-xs text-slate-500">Gateway (authoritative)</div>
            {gateway?.error ? <div className="mt-1 text-rose-600">{gateway.error}</div> : gateway ? (
              <>
                <div className="mt-1"><Badge>{gateway.status}</Badge> v{gateway.version}</div>
                <div className="mt-1 tabular-nums">{money(gateway.amountMinor, payment.currency)}</div>
              </>
            ) : <div className="mt-1 text-slate-500">not linked yet</div>}
          </div>
        </div>
        <p className={`mt-3 text-sm ${agree ? 'text-emerald-600' : 'text-amber-600'}`}>
          {agree ? 'Consistent.' : 'Not (yet) consistent — a webhook may be in flight, lost, or a reconciliation run is due.'}
        </p>
      </Card>
      <Card title="Lifecycle timeline">
        <ol className="relative ml-2 border-l border-slate-200 dark:border-slate-700">
          {timeline.map((e, i) => (
            <li key={i} className="mb-3 ml-4">
              <span className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-white bg-indigo-500 dark:border-slate-900" aria-hidden />
              <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500">{time(e.at)} <Badge tone="neutral">{e.source}</Badge> <Badge>{e.kind}</Badge></div>
              <div className="mt-0.5 text-sm">{e.detail}</div>
            </li>
          ))}
        </ol>
      </Card>
      {audit.length > 0 && (
        <Card title="Audit trail">
          {audit.map((a) => (
            <div key={a.id} className="border-b border-slate-100 py-2 text-sm last:border-0 dark:border-slate-800">
              <div className="flex items-center gap-2"><Badge tone="info">{a.action}</Badge><span className="text-xs text-slate-500">{a.actor} · {time(a.createdAt)}</span></div>
              <div className="mt-1 text-xs text-slate-600 dark:text-slate-300">{a.reason}</div>
            </div>
          ))}
        </Card>
      )}
    </div>
  );
}
