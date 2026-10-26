import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, errorMessage } from './api';
import { useSubmission } from './submission';
import { ConfirmationActions } from './confirmation';
import { Badge, Empty, ErrorBox, Loading, money, PersistedOutcome, timestamp, title, useResource } from './ui';
import type { Account, Page, Transaction } from './types';

type RequestView = {
  id:number; requesterName:string; recipientName:string; amount:string; currency:string;
  description:string; status:string; createdAt:string; updatedAt:string; expiresAt:string|null;
  resolvedAt:string|null; transactionId:number|null; canPay:boolean; canDecline:boolean; canCancel:boolean;
};
type RequestDetail = {request:RequestView; attempts:{transactionId:number;status:string;createdAt:string}[]};
const statuses=['PENDING','PAYMENT_PENDING','PAID','DECLINED','CANCELLED','EXPIRED'];

export function PaymentRequestsPage() {
  const [revision,setRevision]=useState(0),[direction,setDirection]=useState('received'),[status,setStatus]=useState(''),[page,setPage]=useState(0);
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const submitting=useRef(false);
  const accounts=useResource<Account[]>('/accounts');
  const r=useResource<Page<RequestView>>('/payment-requests?'+new URLSearchParams({direction,status,page:String(page)}),revision);
  async function create(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();if(submitting.current)return;
    const form=e.currentTarget,values=new FormData(form),expiry=String(values.get('expiresAt')||'');
    submitting.current=true;setBusy(true);setError('');setNotice('');
    try {
      await api.post('/payment-requests',{
        recipientEmail:values.get('recipientEmail'),receivingAccountId:Number(values.get('receivingAccountId')),
        amount:values.get('amount'),description:values.get('description'),expiresAt:expiry?new Date(expiry).toISOString():null
      });
      form.reset();setNotice('Payment request sent. No funds have moved.');setDirection('sent');setPage(0);setRevision(n=>n+1);
    }catch(err){setError(errorMessage(err));}finally{submitting.current=false;setBusy(false);}
  }
  const eligible=accounts.data?.filter(a=>a.status==='ACTIVE')||[];
  return <><h1>Payment requests</h1><p>Request money from a registered customer. They choose whether to pay through the usual protected transfer workflow.</p>
    {error&&<ErrorBox message={error}/>} {notice&&<p role="status" className="success">{notice}</p>}
    <section className="panel"><h2>Request money</h2>
      {accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:!eligible.length?<Empty>Open an active account to receive requested payments. <Link to="/accounts">Manage accounts</Link></Empty>:
      <form className="request-form" onSubmit={create}><fieldset disabled={busy}><div className="filters">
        <label>Customer email<input name="recipientEmail" type="email" required maxLength={255} autoComplete="off"/></label>
        <label>Your receiving account<select name="receivingAccountId" required>{eligible.map(a=><option value={a.id} key={a.id}>{a.accountNumber} · {a.currency}</option>)}</select></label>
        <label>Amount (receiving account currency)<input name="amount" type="number" min="0.01" step="0.01" required/></label>
        <label>Purpose<input name="description" required maxLength={255}/></label>
        <label>Optional expiry (local time)<input name="expiresAt" type="datetime-local"/></label>
      </div><button disabled={busy}>{busy?'Sending…':'Send request'}</button></fieldset></form>}
    </section>
    <section className="panel"><div className="filters">
      <label>Direction<select value={direction} onChange={e=>{setDirection(e.target.value);setPage(0);}}><option value="received">Received</option><option value="sent">Sent</option></select></label>
      <label>Status<select value={status} onChange={e=>{setStatus(e.target.value);setPage(0);}}><option value="">All statuses</option>{statuses.map(s=><option key={s} value={s}>{title(s)}</option>)}</select></label>
      <button className="secondary" onClick={()=>setRevision(n=>n+1)}>Refresh requests</button>
    </div>{r.loading?<Loading/>:r.error?<ErrorBox message={r.error} retry={r.retry}/>:<>
      {!r.data?.content.length?<Empty>No {direction} requests match these filters.</Empty>:r.data.content.map(request=><article className="timeline-event" key={request.id}>
        <div className="split"><strong>{money(request.amount,request.currency)}</strong><Badge value={request.status}/></div>
        <p>{request.requesterName} requests from {request.recipientName}: {request.description}</p>
        <small>Created {timestamp(request.createdAt)}{request.expiresAt&&' · Expires '+timestamp(request.expiresAt)}</small>
        <Link to={'/payment-requests/'+request.id}>Review request #{request.id}</Link>
      </article>)}
      <div className="pagination"><span>Page {page+1} of {Math.max(1,r.data?.totalPages||0)}</span>
        <button className="secondary" disabled={!page} onClick={()=>setPage(n=>n-1)}>Previous</button>
        <button className="secondary" disabled={page+1>=(r.data?.totalPages||0)} onClick={()=>setPage(n=>n+1)}>Next</button>
      </div></>}
    </section></>;
}

export function PaymentRequestDetailPage() {
  const {id}=useParams();const [revision,setRevision]=useState(0);
  const r=useResource<RequestDetail>('/payment-requests/'+id,revision),accounts=useResource<Account[]>('/accounts');
  const [source,setSource]=useState(''),[review,setReview]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const [result,setResult]=useState<Transaction>();const post=useSubmission(),submitting=useRef(false);
  const request=r.data?.request;
  const eligible=accounts.data?.filter(a=>a.status==='ACTIVE'&&a.currency===request?.currency)||[];
  useEffect(()=>{if(request?.status!=='PAYMENT_PENDING')return;const timer=setInterval(()=>setRevision(n=>n+1),10000);return()=>clearInterval(timer);},[request?.status]);
  async function act(action:'pay'|'decline'|'cancel') {
    if(submitting.current)return;submitting.current=true;setBusy(true);setError('');setNotice('');
    try {
      if(action==='pay') {
        const response=await post<Transaction>('/payment-requests/'+id+'/pay',{senderAccountId:Number(source)});
        setResult(response.data);setReview(false);
        setNotice(response.data.status==='COMPLETED'?'Payment completed.':'Payment attempt recorded. Check its transaction outcome below.');
      }else {await api.post('/payment-requests/'+id+'/'+action);setNotice('Request '+(action==='decline'?'declined':'cancelled')+'.');}
      setRevision(n=>n+1);
    }catch(err){setError(errorMessage(err));setRevision(n=>n+1);}finally{submitting.current=false;setBusy(false);}
  }
  return <><Link to="/payment-requests">Back to payment requests</Link><h1>Payment request #{id}</h1>
    {error&&<ErrorBox message={error}/>} {notice&&<p role="status" className="success">{notice}</p>}
    {r.loading?<Loading/>:r.error?<ErrorBox message={r.error} retry={r.retry}/>:request&&<section className="panel">
      <div className="split"><h2>{money(request.amount,request.currency)}</h2><Badge value={request.status}/></div>
      <p>{request.requesterName} requests from {request.recipientName}</p><p>{request.description}</p>
      <p>Created {timestamp(request.createdAt)}{request.expiresAt&&' · Request expiry '+timestamp(request.expiresAt)}</p>
      {request.status==='PAYMENT_PENDING'&&<p>No completed payment is recorded yet. The transfer must complete before this request is marked paid. A transfer initiated before request expiry retains its own authorization deadline.</p>}
      {request.transactionId&&<p><Link to={'/transactions/'+request.transactionId}>View latest transaction #{request.transactionId} and authorization actions</Link></p>}
      {request.canPay&&(accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:!eligible.length?<Empty>No active {request.currency} account is available to pay this request.</Empty>:
        <form onSubmit={e=>{e.preventDefault();if(!busy)setReview(true);}}>
          <label>Your paying account<select required value={source} disabled={busy} onChange={e=>{setSource(e.target.value);setReview(false);}}><option value="">Choose an account</option>{eligible.map(a=><option value={a.id} key={a.id}>{a.accountNumber} · {money(a.balance,a.currency)}</option>)}</select></label>
          {!review?<button disabled={busy||!source}>Review payment</button>:<div className="confirmation-panel" role="region" aria-label="Review requested payment"><p>Transfer {money(request.amount,request.currency)} from {eligible.find(a=>String(a.id)===source)?.accountNumber} to {request.requesterName}'s receiving account?</p><p>Fraud checks may require password confirmation or admin review. Only a completed transfer pays this request.</p><button type="button" disabled={busy||!source} onClick={()=>act('pay')}>{busy?'Processing…':'Confirm transfer'}</button><button type="button" className="secondary" disabled={busy} onClick={()=>setReview(false)}>Back</button></div>}
        </form>)}
      <div className="confirmation-buttons">{request.canDecline&&<button className="secondary" disabled={busy} onClick={()=>act('decline')}>Decline request</button>}{request.canCancel&&<button className="secondary" disabled={busy} onClick={()=>act('cancel')}>Cancel request</button>}</div>
    </section>}
    {result&&<section className="panel"><PersistedOutcome transaction={result}/><ConfirmationActions transaction={result} onUpdated={value=>{setResult(value);setRevision(n=>n+1);}}/><Link to={'/transactions/'+result.id}>View transaction #{result.id}</Link></section>}
    {r.data&&<section className="panel"><h2>Payment attempt history</h2>{!r.data.attempts.length?<Empty>No payment attempts yet.</Empty>:r.data.attempts.map(attempt=><article className="timeline-event" key={attempt.transactionId}><Badge value={attempt.status}/><p>{timestamp(attempt.createdAt)}</p><Link to={'/transactions/'+attempt.transactionId}>Transaction #{attempt.transactionId}</Link></article>)}</section>}
  </>;
}
