import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from './api';
import { useSubmission } from './submission';
import { Badge, Empty, ErrorBox, Loading, money, timestamp, title, useResource } from './ui';
import type { Account, Transaction } from './types';

type Plan={id:number;senderAccountId:number;recipientAccountId:number;amount:string;currency:string;description:string|null;firstExecuteAt:string;frequency:string;timeZone:string;occurrenceCount:number;generatedCount:number;nextUnqueuedAt:string|null;status:string};
type Occurrence={id:number;executeAt:string;amount:string;currency:string;status:string;outcomeReason:string|null;transaction:Transaction|null;occurrenceIndex:number};
type Input={senderAccountId:number;recipientAccountId:number;amount:string;description:string;firstExecuteAt:string;frequency:string;timeZone:string;occurrenceCount:number};
type Beneficiary={id:number;accountId:number;friendlyName:string;currency:string};

function Occurrences({id,revision}:{id:number;revision:number}) {
  const [page,setPage]=useState(0);const r=useResource<Occurrence[]>('/recurring-payments/'+id+'/occurrences?page='+page,revision);
  return <section className="panel"><h2>Plan #{id} occurrence history</h2><p>Each queued occurrence is an ordinary scheduled transfer. A processed occurrence shows its linked transaction's current outcome.</p>
    {r.loading?<Loading/>:r.error?<ErrorBox message={r.error} retry={r.retry}/>:!r.data?.length?<Empty>No occurrences queued on this page. Future dates are queued within a 45-day horizon.</Empty>:<div className="table-wrap"><table><thead><tr><th>Occurrence</th><th>Due</th><th>Amount</th><th>Outcome</th><th>Transaction</th></tr></thead><tbody>{r.data.map(s=><tr key={s.id}><td>#{s.occurrenceIndex+1}</td><td>{timestamp(s.executeAt)}</td><td>{money(s.amount,s.currency)}</td><td><Badge value={s.transaction?.status||s.status}/><small>{s.transaction?.outcomeReason||s.outcomeReason}</small></td><td>{s.transaction?<Link to={'/transactions/'+s.transaction.id}>{s.transaction.canConfirm?'Authorize or cancel':'View transaction'} #{s.transaction.id}</Link>:<Link to="/scheduled-payments">View queued payment #{s.id}</Link>}</td></tr>)}</tbody></table></div>}
    <div className="pagination"><button className="secondary" disabled={!page} onClick={()=>setPage(n=>n-1)}>Previous</button><span>Page {page+1}</span><button className="secondary" disabled={r.loading||!r.data||r.data.length<20} onClick={()=>setPage(n=>n+1)}>Next</button></div>
  </section>;
}

export function RecurringPaymentsPage(){
  const accounts=useResource<Account[]>('/accounts'),beneficiaries=useResource<Beneficiary[]>('/beneficiaries');
  const [page,setPage]=useState(0),[revision,setRevision]=useState(0),[selected,setSelected]=useState<number>();
  const plans=useResource<Plan[]>('/recurring-payments?page='+page,revision);
  const [recipient,setRecipient]=useState(''),[review,setReview]=useState<Input>(),[cancelId,setCancelId]=useState<number>();
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const submitting=useRef(false),post=useSubmission();
  useEffect(()=>{const timer=setInterval(()=>setRevision(n=>n+1),15000);return()=>clearInterval(timer);},[]);
  function preview(e:FormEvent<HTMLFormElement>){
    e.preventDefault();const values=new FormData(e.currentTarget);const at=new Date(String(values.get('firstExecuteAt')));
    if(!Number.isFinite(at.getTime())||at.getTime()<=Date.now()){setError('Choose a first execution time in the future.');return;}
    setError('');setReview({senderAccountId:Number(values.get('senderAccountId')),recipientAccountId:Number(recipient),amount:String(values.get('amount')),description:String(values.get('description')),firstExecuteAt:at.toISOString(),frequency:String(values.get('frequency')),timeZone:String(values.get('timeZone')),occurrenceCount:Number(values.get('occurrenceCount'))});
  }
  async function create(){
    if(!review||submitting.current)return;submitting.current=true;setBusy(true);setError('');setNotice('');
    try{const response=await post<Plan>('/recurring-payments',review);setSelected(response.data.id);setReview(undefined);setPage(0);setRevision(n=>n+1);setNotice('Recurring plan authorized. Funds move only when each occurrence completes its transfer.');}
    catch(e){setError(errorMessage(e));}finally{submitting.current=false;setBusy(false);}
  }
  async function cancel(){
    if(!cancelId||submitting.current)return;submitting.current=true;setBusy(true);setError('');setNotice('');
    try{await api.post('/recurring-payments/'+cancelId+'/cancel');setCancelId(undefined);setRevision(n=>n+1);setNotice('Plan cancelled. Unprocessed occurrences were cancelled; already processed transactions retain their own outcomes.');}
    catch(e){setError(errorMessage(e));}finally{submitting.current=false;setBusy(false);}
  }
  const active=accounts.data?.filter(a=>a.status==='ACTIVE')||[];
  return <><h1>Recurring payments</h1><p>Authorize a finite series of daily, weekly, monthly or yearly transfers. Each occurrence uses the existing scheduled-payment and fraud workflow. <Link to="/scheduled-payments">View scheduled payments</Link></p>
    <p>Dates use the selected time zone. Month-end dates clamp to the last day, returning to the original day in later months. No funds are reserved. Due occurrences missed during downtime run after restart and can still require password confirmation or admin review.</p>
    {error&&<ErrorBox message={error}/>} {notice&&<p className="success" role="status">{notice}</p>}
    <section className="panel"><h2>Create a recurring plan</h2>
      {accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:!active.length?<Empty>Open an active account first.</Empty>:
      <form className="request-form" onSubmit={preview}><fieldset disabled={busy||!!review}><div className="filters">
        <label>Your paying account<select name="senderAccountId" required>{active.map(a=><option key={a.id} value={a.id}>{a.accountNumber} · {a.currency}</option>)}</select></label>
        <label>Saved beneficiary<select value={recipient} onChange={e=>setRecipient(e.target.value)}><option value="">Enter account ID</option>{beneficiaries.data?.map(b=><option key={b.id} value={b.accountId}>{b.friendlyName} · {b.currency}</option>)}</select></label>
        <label>Recipient account ID<input type="number" min="1" step="1" required value={recipient} onChange={e=>setRecipient(e.target.value)}/></label>
        <label>Amount per occurrence<input name="amount" type="number" min="0.01" step="0.01" required/></label>
        <label>Description<input name="description" maxLength={255}/></label>
        <label>First execution (browser local time)<input name="firstExecuteAt" type="datetime-local" required/></label>
        <label>Recurrence<select name="frequency" defaultValue="MONTHLY">{['DAILY','WEEKLY','MONTHLY','YEARLY'].map(f=><option key={f}>{f}</option>)}</select></label>
        <label>Recurrence time zone<input name="timeZone" required maxLength={80} defaultValue={Intl.DateTimeFormat().resolvedOptions().timeZone}/></label>
        <label>Number of occurrences (1–120)<input name="occurrenceCount" type="number" min="1" max="120" step="1" defaultValue={12} required/></label>
      </div><p className="muted">Currency follows your paying account. All occurrences must be before 2038. At most 45 days of occurrences are queued ahead; the total authorization is limited to the count above.</p><button disabled={busy}>Review recurring authorization</button></fieldset></form>}
      {beneficiaries.error&&<ErrorBox message={beneficiaries.error} retry={beneficiaries.retry}/>}
      {review&&<section className="confirmation-panel" aria-label="Review recurring authorization"><h3>Confirm recurring plan</h3><p>Send {money(review.amount,active.find(a=>a.id===review.senderAccountId)?.currency||'')} from account #{review.senderAccountId} to account #{review.recipientAccountId}, {title(review.frequency)}, for {review.occurrenceCount} occurrences.</p><p>First execution: {timestamp(review.firstExecuteAt)}. Recurrence zone: {review.timeZone}. This authorizes future transfer attempts after sign-out. Funds and fraud checks still apply to every occurrence.</p><div className="confirmation-buttons"><button disabled={busy} onClick={create}>{busy?'Saving…':'Authorize recurring plan'}</button><button className="secondary" disabled={busy} onClick={()=>setReview(undefined)}>Back</button></div></section>}
    </section>
    <section className="panel"><h2>Your recurring plans</h2>{plans.loading?<Loading/>:plans.error?<ErrorBox message={plans.error} retry={plans.retry}/>:!plans.data?.length?<Empty>No recurring plans on this page.</Empty>:plans.data.map(p=><article className="timeline-event" key={p.id}><div className="split"><strong>Plan #{p.id}: {money(p.amount,p.currency)} · {title(p.frequency)}</strong><Badge value={p.status}/></div><p>Account #{p.senderAccountId} → #{p.recipientAccountId}. {p.description}</p><small>First execution {timestamp(p.firstExecuteAt)} · {p.timeZone} · {p.generatedCount} of {p.occurrenceCount} occurrences queued</small>{p.nextUnqueuedAt&&<small>Next date not yet queued: {timestamp(p.nextUnqueuedAt)}</small>}<div className="confirmation-buttons"><button className="secondary" disabled={busy} onClick={()=>setSelected(p.id)}>View occurrence history</button>{p.status==='ACTIVE'&&<button className="secondary" disabled={busy} onClick={()=>setCancelId(p.id)}>Cancel plan</button>}</div></article>)}
      <div className="pagination"><button className="secondary" disabled={!page} onClick={()=>setPage(n=>n-1)}>Previous</button><span>Page {page+1}</span><button className="secondary" disabled={plans.loading||!plans.data||plans.data.length<20} onClick={()=>setPage(n=>n+1)}>Next</button><button className="secondary" onClick={()=>setRevision(n=>n+1)}>Refresh</button></div>
    </section>
    {cancelId&&<section className="confirmation-panel" aria-label="Confirm plan cancellation"><p>Cancel plan #{cancelId} and all its unprocessed occurrences? Already processed transactions must be reviewed separately in their transaction details.</p><button disabled={busy} onClick={cancel}>Confirm cancellation</button><button className="secondary" disabled={busy} onClick={()=>setCancelId(undefined)}>Keep plan</button></section>}
    {selected&&<Occurrences key={selected} id={selected} revision={revision}/>}
  </>;
}
