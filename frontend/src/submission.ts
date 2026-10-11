import { useRef } from 'react';
import { api } from './api';
import axios from 'axios';

// One key per logical submission, retained after transport errors. Passwords
// are never retained; the in-memory digest only detects changed retry content.
export function useSubmission() {
  const pending=useRef<{fingerprint:string;key:string}|undefined>(undefined);
  return async function post<T>(url:string,body:unknown) {
    const bytes=new TextEncoder().encode(url+JSON.stringify(body));
    const digest=await crypto.subtle.digest('SHA-256',bytes);
    const fingerprint=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
    if(pending.current?.fingerprint!==fingerprint)pending.current={fingerprint,key:crypto.randomUUID()};
    try {
      const response=await api.post<T>(url,body,{headers:{'Idempotency-Key':pending.current.key}});
      pending.current=undefined;
      return response;
    } catch(error) {
      // A received 4xx is a definitive rejection. The next click is a new
      // attempt; a timeout/network/5xx retry must keep its original key.
      if(axios.isAxiosError(error) && error.response && error.response.status>=400 && error.response.status<500)pending.current=undefined;
      throw error;
    }
  };
}
