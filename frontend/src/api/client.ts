// Thin fetch wrapper. Credentials live in sessionStorage (cleared when the tab closes), never localStorage.
// The dashboard only DISPLAYS backend data: there is no mock data anywhere in the frontend.

const KEY = 'payguard.auth';

export interface Session {
  username: string;
  token: string; // base64(username:password) for HTTP Basic
  roles: string[];
}

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session | null) {
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s));
    else sessionStorage.removeItem(KEY);
  } catch {
    /* storage unavailable: session lasts for this page only */
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

export async function api<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const auth = token ?? loadSession()?.token;
  const headers = new Headers(init.headers);
  if (auth) headers.set('Authorization', `Basic ${auth}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && !token) onUnauthorized();
  if (!res.ok) {
    let code = `HTTP_${res.status}`;
    let message = res.statusText;
    try {
      const body = await res.json();
      code = body.code ?? code;
      message = body.message ?? message;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, code, message);
  }
  const type = res.headers.get('Content-Type') ?? '';
  if (res.status === 204 || !type) return undefined as T;
  return (type.includes('json') ? res.json() : res.text()) as Promise<T>;
}

export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });

export function basicToken(username: string, password: string) {
  return btoa(unescape(encodeURIComponent(`${username}:${password}`)));
}
