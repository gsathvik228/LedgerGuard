// Run "prepare", restart the application, then run "check". Uses isolated simulation fixtures.
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
const root=new URL('../',import.meta.url),file=new URL('.local/scheduler-restart-fixture.json',root),base='http://127.0.0.1:8080/api';
async function api(path,token,body){const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','Idempotency-Key':randomUUID(),...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});assert.equal(r.status,200,path+' status');return r.json();}
if(process.argv[2]==='prepare'){
  const password=randomBytes(18).toString('hex'),stamp=Date.now();
  async function wallet(label){const email=`restart-${label}-${stamp}@example.test`;const auth=await api('/auth/register',null,{email,password,fullName:'Restart verification '+label});const account=await api('/accounts',auth.token,{accountType:'CHECKING',currency:'USD',initialBalance:'30000.00'});return {email,password,token:auth.token,account};}
  const a=await wallet('sender'),b=await wallet('receiver'),executeAt=new Date(Date.now()+15000).toISOString();
  const schedule=await api('/scheduled-payments',a.token,{senderAccountId:a.account.id,recipientAccountId:b.account.id,amount:'20.00',executeAt,description:'Restart verification one-time'});
  const plan=await api('/recurring-payments',a.token,{senderAccountId:a.account.id,recipientAccountId:b.account.id,amount:'10.00',firstExecuteAt:executeAt,frequency:'DAILY',timeZone:'Asia/Kolkata',occurrenceCount:2,description:'Restart verification recurring'});
  const occurrences=await api('/recurring-payments/'+plan.id+'/occurrences',a.token);
  mkdirSync(new URL('.local/',root),{recursive:true});writeFileSync(file,JSON.stringify({a,b,scheduleId:schedule.id,planId:plan.id,occurrenceIds:occurrences.map(o=>o.id)},null,2));
  console.log('PASS persisted one-time schedule and finite recurring series; restart now before the 15-second execution time.');
}else if(process.argv[2]==='check'){
  const f=JSON.parse(readFileSync(file,'utf8'));let schedule,occurrences;
  for(let i=0;i<90;i++){
    schedule=await api('/scheduled-payments/'+f.scheduleId,f.a.token);occurrences=await api('/recurring-payments/'+f.planId+'/occurrences',f.a.token);
    if(schedule.transaction?.status==='COMPLETED'&&occurrences[0].transaction?.status==='COMPLETED')break;
    await new Promise(r=>setTimeout(r,1000));
  }
  assert.equal(schedule.transaction?.status,'COMPLETED');assert.equal(occurrences[0].transaction?.status,'COMPLETED');
  assert.deepEqual(occurrences.map(o=>o.id),f.occurrenceIds);assert.equal(occurrences[1].status,'SCHEDULED');
  assert.equal((await api('/accounts',f.a.token)).find(a=>a.id===f.a.account.id).balance,'29970.00');assert.equal((await api('/accounts',f.b.token)).find(a=>a.id===f.b.account.id).balance,'30030.00');
  const payments=await api('/scheduled-payments',f.a.token);assert.equal(payments.filter(p=>p.transaction).length,2);
  await api('/recurring-payments/'+f.planId+'/cancel',f.a.token,{});
  writeFileSync(new URL('.local/scheduler-restart-report.json',root),JSON.stringify({at:new Date().toISOString(),passed:true,oneTimeId:schedule.id,occurrenceIds:f.occurrenceIds},null,2));
  console.log('PASS actual process restart recovered both due payment types exactly once, retained occurrence IDs and reconciled balances; future test occurrence cancelled.');
}else throw new Error('Use prepare or check.');
