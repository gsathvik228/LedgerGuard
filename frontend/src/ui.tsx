import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from './api';
import type { Transaction } from './types';

export function useResource<T>(url: string, revision=0) {
  const [data,setData] = useState<T>();
  const [error,setError] = useState('');
  const [loading,setLoading] = useState(true);
  const [attempt,setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setData(undefined);
    api.get<T>(url,{signal:controller.signal}).then(r=>setData(r.data))
      .catch(e=>{if(!controller.signal.aborted)setError(errorMessage(e));})
      .finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return ()=>controller.abort();
  },[url,revision,attempt]);
  return {data,error,loading,retry:()=>setAttempt(v=>v+1)};
}
export function money(amount: string | number, currency: string) {
  const [whole,fraction=''] = String(amount).split('.');
  try { return currency+' '+BigInt(whole).toLocaleString('en-US')+'.'+fraction.padEnd(2,'0').slice(0,2); }
  catch { return currency+' '+amount; }
}
export const timestamp = (value:string) => new Date(value).toLocaleString();
export const title = (value:string) => value.toLowerCase().replace(/_/g,' ').replace(/\b\w/g,c=>c.toUpperCase());
export function Badge({value}:{value:string}) { return <span className={'badge '+value.toLowerCase()}>{title(value)}</span>; }
export function ErrorBox({message,retry}:{message:string;retry?:()=>void}) {
  return <div className="error" role="alert">{message}{retry&&<button className="secondary" onClick={retry}>Retry</button>}</div>;
}
export function Loading() { return <p className="loading" role="status">Loading your ledger…</p>; }
export function Empty({children}:{children:React.ReactNode}) { return <div className="empty">{children}</div>; }
export function TransactionTable({rows,admin=false}:{rows:Transaction[];admin?:boolean}) {
  if (!rows.length) return <Empty>No transactions match this view.</Empty>;
  return <div className="table-wrap"><table><thead><tr><th>Transaction</th><th>Accounts</th><th>Amount</th><th>Status</th><th>Risk</th><th>Recorded</th></tr></thead><tbody>{rows.map(t=><tr key={t.id}>
    <td><Link to={(admin?'/admin':'')+'/transactions/'+t.id}>#{t.id} · {title(t.type)}</Link><small>{t.description||'No description'}</small></td>
    <td>{t.senderAccountId ? '#'+t.senderAccountId : 'External'} → {t.recipientAccountId ? '#'+t.recipientAccountId : 'External'}</td>
    <td className="numeric">{money(t.amount,t.currency)}</td><td><Badge value={t.status}/></td><td><Badge value={t.riskLevel}/><small>{t.riskScore}/100</small></td><td>{timestamp(t.createdAt)}</td>
  </tr>)}</tbody></table></div>;
}
