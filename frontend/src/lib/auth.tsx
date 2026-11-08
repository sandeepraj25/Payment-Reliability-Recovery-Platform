import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, basicToken, loadSession, saveSession, setUnauthorizedHandler, type Session } from '../api/client';
import type { Me } from '../api/types';

interface AuthCtx {
  session: Session | null;
  faultsEnabled: boolean;
  isAdmin: boolean;
  login: (u: string, p: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [faultsEnabled, setFaultsEnabled] = useState(false);

  const logout = () => {
    saveSession(null);
    setSession(null);
  };

  useEffect(() => {
    setUnauthorizedHandler(logout);
    if (session) {
      api<Me>('/api/me').then((me) => setFaultsEnabled(me.faultsEnabled)).catch(() => {});
    }
  }, [session]);

  const login = async (username: string, password: string) => {
    const token = basicToken(username, password);
    const me = await api<Me>('/api/me', {}, token); // throws ApiError(401) on bad credentials
    const s = { username: me.username, token, roles: me.roles };
    saveSession(s);
    setFaultsEnabled(me.faultsEnabled);
    setSession(s);
  };

  return (
    <Ctx.Provider value={{ session, faultsEnabled, isAdmin: !!session?.roles.includes('ADMIN'), login, logout }}>
      {children}
    </Ctx.Provider>
  );
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error('useAuth outside AuthProvider');
  return c;
}
