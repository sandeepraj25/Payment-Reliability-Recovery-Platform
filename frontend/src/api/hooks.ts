import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type {
  Attempt, DeadEvent, Health, Mismatch, MinutePoint, OutboxRow, PaymentRow, PaymentTimeline, ReconRun, Rejection,
  Scenario, SimRun, SimRunRaw, Summary, WebhookEvent, AuditEntry,
} from './types';

const LIVE = 5_000; // dashboards refresh every 5s from the real backend

export const useSummary = () => useQuery({ queryKey: ['summary'], queryFn: () => api<Summary>('/api/ops/summary'), refetchInterval: LIVE });
export const useTimeseries = () => useQuery({ queryKey: ['timeseries'], queryFn: () => api<MinutePoint[]>('/api/ops/timeseries'), refetchInterval: 15_000 });
export const useHealth = () => useQuery({ queryKey: ['health'], queryFn: () => api<Health>('/api/ops/health'), refetchInterval: LIVE });

export const usePayments = (status: string, q: string) =>
  useQuery({
    queryKey: ['payments', status, q],
    queryFn: () => api<PaymentRow[]>(`/api/payments?${new URLSearchParams({ status, q, limit: '200' })}`),
    refetchInterval: LIVE,
  });

export const usePaymentTimeline = (id: string | null) =>
  useQuery({
    queryKey: ['timeline', id],
    queryFn: () => api<PaymentTimeline>(`/api/payments/${id}/timeline`),
    enabled: !!id,
    refetchInterval: LIVE,
  });

export const useWebhookEvents = (status: string, eventId: string, gatewayPaymentId: string) =>
  useQuery({
    queryKey: ['events', status, eventId, gatewayPaymentId],
    queryFn: () => api<WebhookEvent[]>(`/api/webhook-events?${new URLSearchParams({ status, eventId, gatewayPaymentId, limit: '200' })}`),
    refetchInterval: LIVE,
  });

export const useRejections = () =>
  useQuery({ queryKey: ['rejections'], queryFn: () => api<Rejection[]>('/api/webhook-rejections?limit=100'), refetchInterval: LIVE });

export const useAttempts = (eventId: number | null) =>
  useQuery({ queryKey: ['attempts', eventId], queryFn: () => api<Attempt[]>(`/api/webhook-events/${eventId}/attempts`), enabled: eventId != null, refetchInterval: LIVE });

export const useOutboxRows = (eventId: number | null) =>
  useQuery({ queryKey: ['outbox', eventId], queryFn: () => api<OutboxRow[]>(`/api/webhook-events/${eventId}/outbox`), enabled: eventId != null, refetchInterval: LIVE });

export const useDeadLetters = () =>
  useQuery({ queryKey: ['dead'], queryFn: () => api<{ events: DeadEvent[]; brokerDlqDepth: number }>('/api/dead-letters'), refetchInterval: LIVE });

export const useReconRuns = () => useQuery({ queryKey: ['reconRuns'], queryFn: () => api<ReconRun[]>('/api/reconciliation/runs?limit=20'), refetchInterval: LIVE });

export const useMismatches = (runId: number | null, resolution: string) =>
  useQuery({
    queryKey: ['mismatches', runId, resolution],
    queryFn: () => {
      const p = new URLSearchParams({ limit: '500' });
      if (runId != null) p.set('runId', String(runId));
      if (resolution) p.set('resolution', resolution);
      return api<Mismatch[]>(`/api/reconciliation/mismatches?${p}`);
    },
    refetchInterval: LIVE,
  });

export const useAudit = () => useQuery({ queryKey: ['audit'], queryFn: () => api<AuditEntry[]>('/api/audit?limit=100'), refetchInterval: LIVE });

export const useScenarios = () => useQuery({ queryKey: ['scenarios'], queryFn: () => api<Scenario[]>('/api/simulations/scenarios'), staleTime: Infinity, retry: false });

export function parseRun(r: SimRunRaw): SimRun {
  return { ...r, steps: JSON.parse(r.steps || '[]'), invariants: JSON.parse(r.invariants || '[]') };
}

export const useSimRun = (id: number | null) =>
  useQuery({
    queryKey: ['simRun', id],
    queryFn: async () => parseRun(await api<SimRunRaw>(`/api/simulations/runs/${id}`)),
    enabled: id != null,
    // Poll quickly while the backend is still executing the scenario, then stop.
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' || !q.state.data ? 700 : false),
  });

export const useSimRuns = () =>
  useQuery({
    queryKey: ['simRuns'],
    queryFn: async () => (await api<SimRunRaw[]>('/api/simulations/runs?limit=30')).map(parseRun),
    refetchInterval: LIVE,
    retry: false,
  });
