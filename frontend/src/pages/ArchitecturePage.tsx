import { useState } from 'react';
import { useSummary } from '../api/hooks';
import { Card, PageHeader } from '../components/ui';

interface Node { id: string; label: string; x: number; y: number; live?: (s: ReturnType<typeof useSummary>['data']) => string; text: string; code: string }

// Positions in a 1000 x 320 viewBox. Live numbers come from /api/ops/summary.
const NODES: Node[] = [
  { id: 'gw', label: 'Gateway simulator', x: 20, y: 40, text: 'Authoritative payment state machine (CREATED -> AUTHORIZED -> CAPTURED ...). Each change: SELECT ... FOR UPDATE, version + 1, one event row, webhook sent AFTER commit (HMAC-SHA256 signed).', code: 'gateway/GatewayPaymentService.java', live: (s) => `${s?.totalPayments ?? '…'} payments` },
  { id: 'api', label: 'Webhook API', x: 220, y: 40, text: 'POST /api/webhooks/{provider}: verifies the signature over the RAW bytes (constant-time), validates the schema, then stores event + outbox row in ONE transaction. 200 only after COMMIT.', code: 'webhook/WebhookIngestionService.java', live: (s) => `${s?.duplicateDeliveries ?? '…'} dupes absorbed` },
  { id: 'db', label: 'PostgreSQL', x: 420, y: 40, text: 'Source of truth. UNIQUE(provider, event_id) deduplicates; UNIQUE(order_id) on fulfilments makes shipping idempotent; idempotency_records protect the checkout API.', code: 'db/migration/V1..V8', live: (s) => `${s?.fulfilments ?? '…'} fulfilments` },
  { id: 'outbox', label: 'Outbox publisher', x: 620, y: 40, text: 'Claims due rows with FOR UPDATE SKIP LOCKED, publishes, waits for broker confirms, marks PUBLISHED. Ambiguous outcome -> stays PENDING -> republished (at-least-once). Also schedules retries.', code: 'outbox/OutboxPublisher.java', live: (s) => `backlog ${s?.outboxBacklog ?? '…'}` },
  { id: 'mq', label: 'RabbitMQ', x: 820, y: 40, text: 'Durable exchange + queue, persistent messages, manual acks. Rejected messages go to the dead-letter queue (7-day parking lot); the DB status DEAD is the permanent record.', code: 'messaging/RabbitTopology.java', live: (s) => `queue ${s?.queueDepth ?? '…'} · DLQ ${s?.dlqDepth ?? '…'}` },
  { id: 'worker', label: 'Worker', x: 820, y: 200, text: 'Locks event -> payment -> order (same order everywhere: no deadlocks). Stale version -> skipped. Applies state, fulfils once, records the attempt, COMMITS, and only then ACKs.', code: 'worker/EventProcessor.java', live: (s) => `${s?.retryScheduled ?? '…'} retrying` },
  { id: 'merchant', label: 'Merchant DB', x: 520, y: 200, text: 'Orders, payments (with the last applied gateway version), fulfilments, processing attempts, audit log. Can lag the gateway - by design - until a webhook or reconciliation fixes it.', code: 'merchant/GatewayStateApplier.java', live: (s) => `${s?.pendingPayments ?? '…'} pending` },
  { id: 'recon', label: 'Reconciliation', x: 220, y: 200, text: 'Compares merchant DB vs gateway status API vs settlement reports. Auto-fixes only verifiable forward steps through the same guarded applier; everything ambiguous stays OPEN for a human.', code: 'reconciliation/ReconciliationService.java', live: (s) => `${s?.openMismatches ?? '…'} open` },
];
const EDGES: [string, string][] = [['gw', 'api'], ['api', 'db'], ['db', 'outbox'], ['outbox', 'mq'], ['mq', 'worker'], ['worker', 'merchant'], ['merchant', 'recon'], ['recon', 'gw']];
const W = 160, H = 70;

export function ArchitecturePage() {
  const summary = useSummary();
  const [sel, setSel] = useState<Node>(NODES[0]);
  const byId = Object.fromEntries(NODES.map((n) => [n.id, n]));
  return (
    <>
      <PageHeader title="Architecture explorer" subtitle="Click a component. Numbers on the boxes are live from the backend." />
      <Card>
        <svg viewBox="0 0 1000 300" className="h-auto w-full" role="img" aria-label="PayGuard architecture diagram">
          <defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" className="fill-slate-400" />
            </marker>
          </defs>
          {EDGES.map(([a, b]) => {
            const A = byId[a], B = byId[b];
            const horizontal = A.y === B.y;
            const x1 = horizontal ? (A.x < B.x ? A.x + W : A.x) : A.x + W / 2;
            const x2 = horizontal ? (A.x < B.x ? B.x : B.x + W) : B.x + W / 2;
            const y1 = horizontal ? A.y + H / 2 : A.y < B.y ? A.y + H : A.y;
            const y2 = horizontal ? B.y + H / 2 : A.y < B.y ? B.y : B.y + H;
            return <line key={a + b} x1={x1} y1={y1} x2={x2} y2={y2} className="stroke-slate-400" strokeWidth={1.5} markerEnd="url(#arrow)" />;
          })}
          {NODES.map((n) => (
            <g key={n.id} onClick={() => setSel(n)} onKeyDown={(e) => e.key === 'Enter' && setSel(n)} tabIndex={0} role="button"
              aria-label={n.label} className="cursor-pointer focus:outline-none">
              <rect x={n.x} y={n.y} width={W} height={H} rx={10}
                className={sel.id === n.id ? 'fill-indigo-50 stroke-indigo-500 dark:fill-indigo-950' : 'fill-white stroke-slate-300 dark:fill-slate-900 dark:stroke-slate-600'} strokeWidth={1.5} />
              <text x={n.x + W / 2} y={n.y + 30} textAnchor="middle" className="fill-slate-800 text-[13px] font-semibold dark:fill-slate-100">{n.label}</text>
              <text x={n.x + W / 2} y={n.y + 50} textAnchor="middle" className="fill-slate-500 text-[11px]">{n.live?.(summary.data) ?? ''}</text>
            </g>
          ))}
        </svg>
      </Card>
      <Card className="mt-4" title={sel.label}>
        <p className="text-sm leading-relaxed">{sel.text}</p>
        <p className="mt-2 text-xs text-slate-500">Code: <code>backend/src/main/{sel.code.startsWith('db') ? 'resources/' : 'java/com/payguard/'}{sel.code}</code></p>
      </Card>
    </>
  );
}
