import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from './api';
import { useSubmission } from './submission';
import { Badge, Empty, ErrorBox, Loading, money, timestamp, useResource } from './ui';
import type { Account, Transaction } from './types';

type Payment = { id:number; senderAccountId:number; recipientAccountId:number; amount:string; currency:string; description:string|null; executeAt:string; status:string; outcomeReason:string|null; transaction:Transaction|null; recurringPlanId:number|null; occurrenceIndex:number|null };
type Beneficiary = { id:number; accountId:number; friendlyName:string; currency:string };
export function ScheduledPaymentsPage() {
  const accounts=useResource<Account[]>('/accounts');
  const beneficiaries=useResource<Beneficiary[]>('/beneficiaries');
  const [page,setPage]=useState(0),[revision,setRevision]=useState(0);
  const schedules=useResource<Payment[]>('/scheduled-payments?page='+page,revision);
  const [recipient,setRecipient]=useState('');
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const post=useSubmission();
  useEffect(()=>{const timer=window.setInterval(()=>setRevision(v=>v+1),5000);return()=>clearInterval(timer);},[]);
  async function submit(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form=e.currentTarget,values=new FormData(form);
    const executeAt=new Date(String(values.get('executeAt')));
    if(!Number.isFinite(executeAt.getTime())||executeAt.getTime()<=Date.now()){setError('Choose a time in the future.');return;}
    setBusy(true);setError('');setNotice('');
    try {
      await post('/scheduled-payments',{
        senderAccountId:Number(values.get('senderAccountId')),recipientAccountId:Number(recipient),
        amount:String(values.get('amount')),description:String(values.get('description')),executeAt:executeAt.toISOString()
      });
      form.reset();setRecipient('');setPage(0);setRevision(v=>v+1);
      setNotice('Payment scheduled. No funds have moved or been reserved.');
    } catch(e){setError(errorMessage(e));}finally{setBusy(false);}
  }
  async function cancel(id:number) {
    setBusy(true);setError('');setNotice('');
    try{await api.post('/scheduled-payments/'+id+'/cancel');setRevision(v=>v+1);setNotice('Cancellation checked. See the current payment status below.');}
    catch(e){setError(errorMessage(e));}finally{setBusy(false);}
  }
  const active=accounts.data?.filter(a=>a.status==='ACTIVE')||[];
  return <><h1>Scheduled payments</h1>
    <p><Link to="/recurring-payments">Create or manage a recurring plan</Link></p>
    <p>Authorize a one-time transfer for a future time. Funds are checked when it runs and are never reserved. Scheduling remains active after sign-out.</p>
    <p>Low-risk payments run automatically. Medium/high-risk payments need your password confirmation; critical-risk payments need admin review. Confirmation expires five minutes after processing by default.</p>
    {error&&<ErrorBox message={error}/>} {notice&&<p role="status">{notice}</p>}
    <section className="panel"><h2>Schedule a transfer</h2>
      {accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:!active.length?<Empty>Open an active account first.</Empty>:
      <form onSubmit={submit}>
        <label>From account<select name="senderAccountId" required disabled={busy}>{active.map(a=><option key={a.id} value={a.id}>#{a.id} · {money(a.balance,a.currency)}</option>)}</select></label>
        <label>Saved beneficiary<select value={recipient} disabled={busy} onChange={e=>setRecipient(e.target.value)}><option value="">Enter recipient ID manually</option>{beneficiaries.data?.map(b=><option key={b.id} value={b.accountId}>{b.friendlyName} · {b.currency}</option>)}</select></label>
        {beneficiaries.error&&<ErrorBox message={beneficiaries.error} retry={beneficiaries.retry}/>}
        <label>Recipient account ID<input type="number" min="1" step="1" value={recipient} onChange={e=>setRecipient(e.target.value)} required disabled={busy}/></label>
        <label>Amount<input name="amount" type="number" min="0.01" step="0.01" required disabled={busy}/></label>
        <label>Description<input name="description" maxLength={255} disabled={busy}/></label>
        <label>Execution time ({Intl.DateTimeFormat().resolvedOptions().timeZone})<input name="executeAt" type="datetime-local" required disabled={busy}/></label>
        <p className="muted">Both accounts must use the same currency. Payments run when the server next processes due schedules, normally within five seconds while it is running. Overdue payments run after restart.</p>
        <button disabled={busy}>{busy?'Please wait…':'Authorize scheduled transfer'}</button>
      </form>}
    </section>
    <section className="panel"><h2>Your scheduled payments</h2>
      {schedules.error?<ErrorBox message={schedules.error} retry={schedules.retry}/>:schedules.loading?<Loading/>:!schedules.data?.length?<Empty>No scheduled payments on this page.</Empty>:
      <div className="table-wrap"><table><thead><tr><th>Payment</th><th>Scheduled time</th><th>Amount</th><th>Status</th><th>Action</th></tr></thead>
      <tbody>{schedules.data.map(s=><tr key={s.id}><td>#{s.id}<small>Account #{s.senderAccountId} → #{s.recipientAccountId}</small>{s.description&&<small>{s.description}</small>}{s.recurringPlanId&&<small>Recurring plan #{s.recurringPlanId}, occurrence #{(s.occurrenceIndex??0)+1}</small>}</td>
        <td>{timestamp(s.executeAt)}</td><td>{money(s.amount,s.currency)}</td>
        <td><Badge value={s.transaction?.status||s.status}/>{s.transaction?.status==='PENDING_CONFIRMATION'&&<small>Password confirmation required. No funds moved.</small>}
          {s.transaction?.status==='AWAITING_REVIEW'&&<small>Awaiting admin approval. No funds moved.</small>}
          {(s.transaction?.outcomeReason||s.outcomeReason)&&<small>{s.transaction?.outcomeReason||s.outcomeReason}</small>}
          {s.status==='SCHEDULED'&&<small>Authorized; awaiting execution. No funds reserved.</small>}</td>
        <td>{s.transaction&&<Link to={'/transactions/'+s.transaction.id}>{s.transaction.canConfirm?'Authorize or cancel':'View transaction'}</Link>}
        {(s.status==='SCHEDULED'||s.transaction?.canCancel)&&<button className="secondary" disabled={busy} onClick={()=>cancel(s.id)}>Cancel payment</button>}</td></tr>)}</tbody></table></div>}
      <div className="filters"><button className="secondary" disabled={page===0} onClick={()=>setPage(p=>p-1)}>Previous</button><span>Page {page+1}</span><button className="secondary" disabled={schedules.loading||!schedules.data||schedules.data.length<20} onClick={()=>setPage(p=>p+1)}>Next</button><button className="secondary" onClick={()=>setRevision(v=>v+1)}>Refresh</button></div>
    </section>
  </>;
}
