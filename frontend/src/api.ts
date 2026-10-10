import axios from 'axios';
import type { Session } from './types';

const key = 'ledgerguard.session';
export function readSession(): Session | null {
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null');
    if (!value || typeof value.token !== 'string' || !['CUSTOMER','ADMIN'].includes(value.role)) return null;
    if (expiresAt(value.token) <= Date.now()) { localStorage.removeItem(key); return null; }
    return value;
  } catch { return null; }
}
export function expiresAt(token: string): number {
  try { return JSON.parse(atob(token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/'))).exp * 1000 || 0; }
  catch { return 0; }
}
export function saveSession(value: Session | null) {
  if (value) localStorage.setItem(key, JSON.stringify(value)); else localStorage.removeItem(key);
}
export const api = axios.create({ baseURL: '/api', timeout: 15000 });
api.interceptors.request.use(config => {
  const session = readSession();
  if (session) config.headers.Authorization = 'Bearer ' + session.token;
  return config;
});
api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401 && !error.config?.url?.startsWith('/auth/')) {
    saveSession(null);
    window.dispatchEvent(new Event('session-expired'));
  }
  return Promise.reject(error);
});
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return 'Unable to reach the API. Check that the backend is running, then retry.';
    return error.response.data?.message || 'The request could not be completed. Please retry.';
  }
  return error instanceof Error ? error.message : 'Something went wrong.';
}
