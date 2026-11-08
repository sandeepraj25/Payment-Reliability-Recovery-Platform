// Shapes of the backend JSON (see docs/api-documentation.md).

export interface ReconRun {
  id: number;
  triggerType: string;
  triggeredBy: string;
  status: string;
  windowFrom: string;
  windowTo: string;
  merchantPayments: number;
  gatewayPayments: number;
  mismatchesFound: number;
  autoResolved: number;
  manualReview: number;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface Summary {
  paymentsByStatus: Record<string, number>;
  ordersByStatus: Record<string, number>;
  webhookEventsByStatus: Record<string, number>;
  totalPayments: number;
  successfulPayments: number;
  pendingPayments: number;
  failedEvents: number;
  retryScheduled: number;
  rejectedWebhooks: number;
  duplicateDeliveries: number;
  recoveredByReconciliation: number;
  replayedDeadLetters: number;
  fulfilments: number;
  outboxBacklog: number;
  outboxOldestDueAgeSeconds: number;
  queueDepth: number;
  dlqDepth: number;
  openMismatches: number;
  lastReconciliation: ReconRun | null;
}

export interface MinutePoint {
  minute: string;
  received: number;
  rejected: number;
  processed: number;
  failed: number;
  skipped: number;
}

export interface Health {
  database: { up: boolean; latencyMs: number };
  rabbitmq: { up: boolean; queueDepth: number; dlqDepth: number };
  workers: { workerId: string; lastSeen: string; attempts15m: number }[];
  outbox: { backlog: number; oldestDueAgeSeconds: number };
  processingLatencyMs: { p50: number; p95: number; p99: number; samples: number };
  receiveToProcessedMs: { p50: number; p95: number; samples: number };
}

export interface PaymentRow {
  id: string;
  orderId: string;
  gatewayPaymentId: string | null;
  amountMinor: number;
  amountRefundedMinor: number;
  currency: string;
  status: string;
  gatewayVersion: number;
  orderStatus: string;
  createdAt: string;
  updatedAt: string;
}

export interface TimelineEntry {
  at: string;
  source: string;
  kind: string;
  detail: string;
}

export interface AuditEntry {
  id: number;
  actor: string;
  action: string;
  targetType: string;
  targetId: string;
  reason: string | null;
  beforeState: string | null;
  afterState: string | null;
  correlationId: string | null;
  createdAt: string;
}

export interface PaymentTimeline {
  payment: PaymentRow;
  gateway: { status: string; version: number; amountMinor: number; capturedAt: string | null; error?: string } | null;
  timeline: TimelineEntry[];
  audit: AuditEntry[];
}

export interface WebhookEvent {
  id: number;
  provider: string;
  providerEventId: string;
  eventType: string;
  gatewayPaymentId: string | null;
  gatewayPaymentVersion: number | null;
  providerCreatedAt: string | null;
  payload: string;
  payloadSha256: string;
  processingStatus: string;
  duplicateCount: number;
  lastDuplicateAt: string | null;
  receivedAt: string;
  processedAt: string | null;
  attemptCount: number;
  processingResult: string | null;
  lastError: string | null;
}

export interface Rejection {
  id: number;
  provider: string;
  claimedEventId: string | null;
  reason: string;
  detail: string | null;
  bodySha256: string | null;
  sourceIp: string | null;
  receivedAt: string;
}

export interface Attempt {
  id: number;
  attemptNo: number;
  workerId: string;
  redelivered: boolean;
  outcome: string;
  errorClass: string | null;
  errorMessage: string | null;
  retryDelayMs: number | null;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
}

export interface OutboxRow {
  id: number;
  purpose: string;
  status: string;
  publishAttempts: number;
  nextAttemptAt: string;
  lastError: string | null;
  createdAt: string;
  publishedAt: string | null;
}

export interface DeadEvent {
  webhookEventId: number;
  providerEventId: string;
  eventType: string;
  gatewayPaymentId: string | null;
  attemptCount: number;
  lastError: string | null;
  receivedAt: string;
  replayable: boolean;
}

export interface Mismatch {
  id: number;
  runId: number;
  mismatchType: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  gatewayPaymentId: string | null;
  merchantPaymentId: string | null;
  orderId: string | null;
  merchantValue: string | null;
  gatewayValue: string | null;
  evidence: string;
  resolution: string;
  resolutionNote: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

export interface Scenario {
  id: string;
  title: string;
  explanation: string;
  mechanisms: string;
}

export interface SimStep {
  phase: 'INITIAL' | 'INJECT' | 'OBSERVED' | 'RECOVERY' | 'FINAL' | 'ERROR';
  title: string;
  detail: string;
  evidence: Record<string, unknown>;
  at: string;
  elapsedMs: number;
}

export interface SimInvariant {
  name: string;
  held: boolean;
  detail: string;
}

export interface SimRunRaw {
  id: number;
  scenario: string;
  status: 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR';
  startedBy: string;
  steps: string; // JSON text from the backend
  invariants: string;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface SimRun extends Omit<SimRunRaw, 'steps' | 'invariants'> {
  steps: SimStep[];
  invariants: SimInvariant[];
}

export interface Me {
  username: string;
  roles: string[];
  faultsEnabled: boolean;
}
