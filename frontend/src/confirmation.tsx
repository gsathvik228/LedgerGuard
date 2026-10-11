import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSubmission } from './submission';
import { api, errorMessage } from './api';
import type { Transaction } from './types';

export function ConfirmationActions({transaction,onUpdated}:{transaction:Transaction;onUpdated:(value:Transaction)=>void}) {
  const post=useSubmission();
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [now,setNow]=useState(Date.now());
  const update=useRef(onUpdated);
  update.current=onUpdated;
  const review=transaction.status==='AWAITING_REVIEW';
  const pending=transaction.status==='PENDING_CONFIRMATION'||review;
  const deadline=review?transaction.reviewExpiresAt:transaction.confirmationExpiresAt;
  const expired=!!deadline && now>=new Date(deadline).getTime();
  useEffect(()=>{
    if(!pending)return;
    let active=true;
    const clock=window.setInterval(()=>setNow(Date.now()),1000);
    const poll=window.setInterval(()=>{
      api.get<Transaction>('/transactions/'+transaction.id).then(r=>{
        if(active && r.data.status!==transaction.status)update.current(r.data);
      }).catch(()=>{/* Explicit actions report errors; periodic refresh does not erase the form. */});
    },5000);
    return ()=>{active=false;clearInterval(clock);clearInterval(poll);};
  },[pending,transaction.id,transaction.status]);
  if(!pending)return null;
  async function confirm(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const input=event.currentTarget.elements.namedItem('confirmationPassword') as HTMLInputElement;
    const password=input.value;
    input.value=''; // Never place the password in React state or browser storage.
    await act('confirm',{password});
  }
  async function act(action:'confirm'|'cancel',body?:{password:string}) {
    setBusy(true);setError('');
    try {
      const response=await post<Transaction>('/transactions/'+transaction.id+'/'+action,body);
      update.current(response.data);
    }catch(e){setError(errorMessage(e));}finally{setBusy(false);}
  }
  return <section className="confirmation-panel" aria-label="Payment authorization">
    <h3>{review?'Awaiting admin review':'Authorize or cancel this payment'}</h3>
    {review?<p>No funds have moved or been reserved. An administrator must approve or reject this request. You may cancel while it is pending.</p>:<p>No funds have moved or been reserved. Confirming with your current password authorizes this payment despite the warning. Account checks and fraud reassessment must still pass; password confirmation cannot override critical risk.</p>}
    <p className="muted">Authorization deadline: {deadline?new Date(deadline).toLocaleString():'Unavailable'}</p>
    {expired?<p role="status">The authorization window has ended. Waiting for the recorded expiry status.</p>:review&&transaction.canCancel?<button className="secondary" disabled={busy} onClick={()=>act('cancel')}>Cancel review request</button>:transaction.canConfirm?<form onSubmit={confirm}>
      <label>Current password<input name="confirmationPassword" type="password" autoComplete="current-password" required maxLength={72} disabled={busy}/></label>
      <div className="confirmation-buttons"><button disabled={busy}>{busy?'Processing...':'Authorize payment'}</button><button type="button" className="secondary" disabled={busy} onClick={()=>act('cancel')}>Cancel payment</button></div>
    </form>:<p>Only the user who initiated this transaction can authorize or cancel it.</p>}
    {error&&<p className="error" role="alert">{error}</p>}
  </section>;
}
