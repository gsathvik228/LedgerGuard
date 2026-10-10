import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { api, errorMessage, expiresAt, readSession, saveSession } from './api';
import type { Session } from './types';

const AuthContext = createContext<{ session: Session | null; login: (s: Session) => void; logout: () => void; notice: string }>({ session:null, login:()=>{}, logout:()=>{}, notice:'' });
export const useAuth = () => useContext(AuthContext);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session,setSession] = useState(readSession);
  const [checking,setChecking] = useState(!!session);
  const [error,setError] = useState('');
  const [notice,setNotice] = useState('');
  useEffect(() => {
    if (!session) { setChecking(false); return; }
    let current = true;
    api.get('/me').then(r => {
      if (current) { const updated = { ...session, ...r.data }; saveSession(updated); setSession(updated); setChecking(false); }
    }).catch(e => { if(current) { setError(errorMessage(e)); setChecking(false); } });
    return () => { current = false; };
  }, [session?.token]);
  useEffect(() => {
    const expired = () => { saveSession(null); setSession(null); setError(''); setNotice('Your session expired. Please sign in again.'); };
    const synced = () => { setSession(readSession()); setError(''); };
    window.addEventListener('session-expired',expired);
    window.addEventListener('storage',synced);
    const timer = session ? window.setTimeout(expired, Math.max(0,expiresAt(session.token)-Date.now())) : undefined;
    return () => { clearTimeout(timer); window.removeEventListener('session-expired',expired); window.removeEventListener('storage',synced); };
  }, [session?.token]);
  const login = (value:Session) => { saveSession(value); setSession(value); setNotice(''); setError(''); };
  const logout = () => { saveSession(null); setSession(null); setError(''); setNotice('You have been signed out.'); };
  if (checking) return <div className="boot">Checking your session…</div>;
  if (error && session) return <div className="boot"><p role="alert">{error}</p><button onClick={()=>window.location.reload()}>Retry</button><button className="secondary" onClick={logout}>Sign out</button></div>;
  return <AuthContext.Provider value={{session,login,logout,notice}}>{children}</AuthContext.Provider>;
}
export function Protected({ admin=false }: { admin?:boolean }) {
  const { session } = useAuth();
  if (!session) return <Navigate to="/login" replace/>;
  if (admin && session.role !== 'ADMIN') return <Navigate to="/" replace/>;
  return <Outlet/>;
}
