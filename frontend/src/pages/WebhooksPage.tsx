import { useState } from 'react';
import { useAttempts, useOutboxRows, useRejections, useWebhookEvents } from '../api/hooks';
import type { WebhookEvent } from '../api/types';
import { Badge, Card, Empty, ErrorBox, Loading, PageHeader, Select, Table, TextInput, mono, td } from '../components/ui';
import { prettyJson, short, time } from '../lib/format';

const STATUSES = ['', 'RECEIVED', 'RETRY_SCHEDULED', 'PROCESSED', 'IGNORED', 'DEAD'];

export function WebhooksPage() {
  const [status, setStatus] = useState('');
  const [eventId, setEventId] = useState('');
  const [selected, setSelected] = useState<WebhookEvent | null>(null);
  const events = useWebhookEvents(status, eventId.trim(), '');
  const rejections = useRejections();

  return (
    <>
      <PageHeader title="Webhook event explorer"
        subtitle="Every authentic event is stored once (duplicates are counted, not stored). Requests that fail signature or schema checks never become events — they are listed separately below." />
      <div className="mb-3 flex flex-wrap gap-3">
        <TextInput label="Event id" value={eventId} onChange={setEventId} placeholder="exact event id (evt_…)" />
        <Select label="Processing state" value={status} onChange={setStatus} options={STATUSES} />
      </div>
      <div className="grid gap-4 xl:grid-cols-5">
        <Card className="xl:col-span-3" title="Verified events">
          {events.isLoading ? <Loading /> : events.error ? <ErrorBox error={events.error} /> : events.data!.length === 0 ? <Empty>No events.</Empty> : (
            <Table head={['Event', 'Type', 'Signature', 'State', 'Attempts', 'Duplicates', 'Received']}>
              {events.data!.map((e) => (
                <tr key={e.id} onClick={() => setSelected(e)} tabIndex={0} onKeyDown={(k) => k.key === 'Enter' && setSelected(e)}
                  className={`cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/60 ${selected?.id === e.id ? 'bg-indigo-50/60 dark:bg-indigo-950/40' : ''}`}>
                  <td className={`${td} ${mono}`}>{short(e.providerEventId, 14)}</td>
                  <td className={td}>{e.eventType} <span className="text-xs text-slate-400">v{e.gatewayPaymentVersion ?? '—'}</span></td>
                  <td className={td}><Badge tone="good">valid</Badge></td>
                  <td className={td}><Badge>{e.processingStatus}</Badge>{e.processingResult && <span className="ml-1 text-xs text-slate-500">{e.processingResult}</span>}</td>
                  <td className={`${td} tabular-nums`}>{e.attemptCount}</td>
                  <td className={`${td} tabular-nums`}>{e.duplicateCount}</td>
                  <td className={`${td} text-xs text-slate-500`}>{time(e.receivedAt)}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
        <div className="xl:col-span-2">{selected ? <EventDetail e={selected} /> : <Card><Empty>Select an event to see retries, outbox rows and errors.</Empty></Card>}</div>
      </div>
      <Card className="mt-4" title="Rejected requests (never stored as events)">
        {rejections.data?.length ? (
          <Table head={['When', 'Reason', 'Claimed event id', 'Detail', 'Source IP']}>
            {rejections.data.map((r) => (
              <tr key={r.id}>
                <td className={`${td} text-xs text-slate-500`}>{time(r.receivedAt)}</td>
                <td className={td}><Badge tone="bad">{r.reason}</Badge></td>
                <td className={`${td} ${mono}`}>{r.claimedEventId ?? '—'}</td>
                <td className={`${td} text-xs`}>{r.detail}</td>
                <td className={`${td} ${mono}`}>{r.sourceIp}</td>
              </tr>
            ))}
          </Table>
        ) : <Empty>No rejected requests.</Empty>}
      </Card>
    </>
  );
}

function EventDetail({ e }: { e: WebhookEvent }) {
  const attempts = useAttempts(e.id);
  const outbox = useOutboxRows(e.id);
  return (
    <div className="space-y-4">
      <Card title="Processing attempts (retry history)">
        {attempts.data?.length ? attempts.data.map((a) => (
          <div key={a.id} className="border-b border-slate-100 py-2 text-sm last:border-0 dark:border-slate-800">
            <div className="flex flex-wrap items-center gap-2">#{a.attemptNo} <Badge>{a.outcome}</Badge>
              {a.redelivered && <Badge tone="warn">redelivered</Badge>}
              <span className="text-xs text-slate-500">{a.durationMs} ms · {short(a.workerId, 18)}</span></div>
            {a.errorClass && <div className="mt-1 text-xs text-rose-600">{a.errorClass}: {a.errorMessage}</div>}
            {a.retryDelayMs != null && <div className="mt-1 text-xs text-amber-600">next retry after {a.retryDelayMs} ms (backoff + jitter)</div>}
          </div>
        )) : <Empty>No attempts yet.</Empty>}
      </Card>
      <Card title="Outbox rows">
        {outbox.data?.map((o) => (
          <div key={o.id} className="flex flex-wrap items-center gap-2 border-b border-slate-100 py-2 text-sm last:border-0 dark:border-slate-800">
            <Badge tone="neutral">{o.purpose}</Badge><Badge>{o.status}</Badge>
            <span className="text-xs text-slate-500">publish attempts {o.publishAttempts}{o.lastError ? ` · ${o.lastError}` : ''}</span>
          </div>
        ))}
      </Card>
      {e.lastError && <Card title="Last error"><p className="text-sm text-rose-600">{e.lastError}</p></Card>}
      <Card title="Stored payload (exact signed body)">
        <pre className="max-h-72 overflow-auto rounded-lg bg-slate-950 p-3 font-mono text-[11px] text-slate-200">{prettyJson(e.payload)}</pre>
        <p className="mt-2 break-all text-xs text-slate-500">sha256 {e.payloadSha256}</p>
      </Card>
    </div>
  );
}
