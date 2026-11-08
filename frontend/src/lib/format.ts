// Money arrives as an INTEGER number of minor units (paise). We only divide for display;
// nothing in the UI ever does arithmetic on money.
export function money(minor: number, currency = 'INR') {
  try {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(minor / 100);
  } catch {
    return `${(minor / 100).toFixed(2)} ${currency}`;
  }
}

export function time(iso: string | null | undefined) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString(undefined, { hour12: false, month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function ago(iso: string | null | undefined) {
  if (!iso) return '—';
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return `${Math.round(s / 3600)}h ago`;
}

export function short(id: string | null | undefined, n = 8) {
  if (!id) return '—';
  return id.length > n + 3 ? `${id.slice(0, n)}…` : id;
}

export function prettyJson(text: string | null | undefined) {
  if (!text) return '';
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
