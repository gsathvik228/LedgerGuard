// Opt-in browser/API integration checks. Creates isolated simulation users; never deletes user data.
// Install tooling privately: npm.cmd install --prefix .local/browser-check playwright
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {existsSync,readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
const root=new URL('../',import.meta.url),require=createRequire(import.meta.url);
const {chromium}=require('../.local/browser-check/node_modules/playwright');
const base='http://127.0.0.1:8080/api',site='http://127.0.0.1:5173',stamp=Date.now();
const checks=[],credentials=[];
function pass(message){checks.push(message);console.log('PASS '+message);}
async function api(path,token,body){
  const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','Idempotency-Key':randomUUID(),...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  assert.equal(r.status,200,path+' HTTP status');return r.json();
}
async function wallet(label){
  const input={email:`browser-${label}-${stamp}@example.test`,password:randomBytes(18).toString('hex'),fullName:`Browser consolidation ${stamp} ${label}`};credentials.push(input);
  const auth=await api('/auth/register',null,input);const account=await api('/accounts',auth.token,{accountType:'CHECKING',currency:'USD',initialBalance:'30000.00'});
  return {...input,token:auth.token,account};
}
async function until(work,predicate,timeout=90000){
  const end=Date.now()+timeout;let result;
  while(Date.now()<end){result=await work();if(predicate(result))return result;await new Promise(r=>setTimeout(r,500));}
  throw new Error('Timed out waiting for recorded application outcome');
}
const errors=[];
const browser=await chromium.launch({headless:true,executablePath:process.env.LEDGER_BROWSER_EXE||'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'});
mkdirSync(new URL('.local/',root),{recursive:true});
async function page(){const context=await browser.newContext({timezoneId:'Asia/Kolkata',viewport:{width:1440,height:1000}});const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));p.setDefaultTimeout(20000);return p;}
async function login(p,identity){await p.goto(site+'/login');await p.getByLabel('Email address').fill(identity.email);await p.getByLabel('Password',{exact:true}).fill(identity.password);await p.getByRole('button',{name:'Sign in'}).click();await p.getByRole('navigation',{name:'Main navigation'}).waitFor();}
async function localInput(p,milliseconds){return p.evaluate(ms=>{const d=new Date(ms);const pad=n=>String(n).padStart(2,'0');return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;},milliseconds);}
let recurringId,owner;
try{
  const a=await wallet('requester'),b=await wallet('payer');owner=a;
  const pa=await page(),pb=await page();await login(pa,a);await login(pb,b);
  for(const name of ['Scheduled payments','Recurring payments','Payment requests'])assert.equal(await pa.getByRole('navigation').getByRole('link',{name,exact:true}).count(),1);
  pass('Authenticated navigation exposes scheduled, recurring and requested payments together');

  await pa.getByRole('navigation').getByRole('link',{name:'Scheduled payments',exact:true}).click();
  await pa.getByLabel('From account').selectOption(String(a.account.id));await pa.getByLabel('Recipient account ID').fill(String(b.account.id));
  await pa.getByLabel('Amount',{exact:true}).fill('12.34');await pa.getByLabel('Description',{exact:true}).fill('Browser one-time transfer');
  // Minute input: at least one complete minute in the future, normally under 120 seconds.
  const due=Math.ceil((Date.now()+45000)/60000)*60000;
  await pa.getByLabel(/Execution time/).fill(await localInput(pa,due));
  await pa.getByRole('button',{name:'Authorize scheduled transfer',exact:true}).click();
  await pa.getByText('Payment scheduled. No funds have moved or been reserved.').waitFor();
  const oneTime=(await api('/scheduled-payments',a.token)).find(s=>s.description==='Browser one-time transfer');assert.ok(oneTime);
  pass('One-time scheduled transfer created through the real form and persisted');

  await pa.getByRole('navigation').getByRole('link',{name:'Recurring payments',exact:true}).click();
  await pa.getByLabel('Your paying account').selectOption(String(a.account.id));await pa.getByLabel('Recipient account ID').fill(String(b.account.id));
  await pa.getByLabel('Amount per occurrence').fill('9.99');await pa.getByLabel('Description',{exact:true}).fill('Browser recurring transfer');
  await pa.getByLabel('First execution (browser local time)').fill(await localInput(pa,due));await pa.locator('select[name=frequency]').selectOption('DAILY');await pa.getByLabel('Number of occurrences (1–120)').fill('2');
  await pa.getByRole('button',{name:'Review recurring authorization'}).click();await pa.getByRole('button',{name:'Authorize recurring plan',exact:true}).click();
  await pa.getByText('Recurring plan authorized. Funds move only when each occurrence completes its transfer.').waitFor();
  const plan=(await api('/recurring-payments',a.token)).find(p=>p.description==='Browser recurring transfer');recurringId=plan.id;assert.equal(plan.generatedCount,2);
  await pa.getByRole('heading',{name:`Plan #${plan.id} occurrence history`}).waitFor();
  pass('Recurring form confirms finite authorization and shows persisted occurrence history');

  await pa.getByRole('navigation').getByRole('link',{name:'Payment requests',exact:true}).click();
  await pa.getByLabel('Customer email').fill(b.email);await pa.getByLabel('Your receiving account').selectOption(String(a.account.id));await pa.getByLabel('Amount (receiving account currency)').fill('750.00');await pa.getByLabel('Purpose').fill('Browser shared meal');
  await pa.getByRole('button',{name:'Send request',exact:true}).click();await pa.getByText('Payment request sent. No funds have moved.').waitFor();
  const request=(await api('/payment-requests?direction=sent',a.token)).content.find(r=>r.description==='Browser shared meal');assert.ok(request);
  await pb.getByRole('navigation').getByRole('link',{name:'Payment requests',exact:true}).click();await pb.getByRole('link',{name:`Review request #${request.id}`,exact:true}).click();
  await pb.getByLabel('Your paying account').selectOption(String(b.account.id));await pb.getByRole('button',{name:'Review payment',exact:true}).click();await pb.getByRole('button',{name:'Confirm transfer',exact:true}).click();
  await pb.getByText('Payment completed.',{exact:true}).waitFor();
  assert.equal((await api('/payment-requests/'+request.id,a.token)).request.status,'PAID');
  await pa.goto(site+'/payment-requests/'+request.id);await pa.getByText('Paid',{exact:true}).waitFor();
  pass('Two-browser request creation, explicit payment review and authoritative paid status work end to end');

  console.log('Waiting for the authorized one-time and first recurring execution times...');
  const processed=await until(()=>api('/scheduled-payments/'+oneTime.id,a.token),s=>s.transaction?.status==='COMPLETED',150000);
  assert.equal(processed.transaction.amount,'12.34');
  const occurrences=await until(()=>api('/recurring-payments/'+plan.id+'/occurrences',a.token),rows=>rows[0].transaction?.status==='COMPLETED',150000);
  assert.equal(occurrences[0].transaction.amount,'9.99');assert.equal(occurrences[1].status,'SCHEDULED');
  const balancesA=await api('/accounts',a.token),balancesB=await api('/accounts',b.token);
  assert.equal(balancesA.find(w=>w.id===a.account.id).balance,'30727.67');assert.equal(balancesB.find(w=>w.id===b.account.id).balance,'29272.33');
  pass('Real background jobs complete both payment types with exact reconciled balances');

  await pa.getByRole('navigation').getByRole('link',{name:'Recurring payments',exact:true}).click();await pa.getByRole('button',{name:'Cancel plan',exact:true}).first().click();await pa.getByRole('button',{name:'Confirm cancellation',exact:true}).click();await pa.getByText('Plan cancelled. Unprocessed occurrences were cancelled; already processed transactions retain their own outcomes.').waitFor();
  const cancelled=await api('/recurring-payments/'+plan.id+'/occurrences',a.token);assert.equal(cancelled[0].transaction.status,'COMPLETED');assert.equal(cancelled[1].status,'CANCELLED');
  await pa.setViewportSize({width:390,height:844});
  const header=await pa.locator('.topbar').boundingBox(),main=await pa.locator('main').boundingBox();
  assert.ok(header.y+header.height<=main.y,'Mobile account header must not overlap page content');
  await pa.screenshot({path:new URL('.local/consolidated-recurring-mobile.png',root).pathname.replace(/^\/([A-Za-z]:)/,'$1'),fullPage:true});
  pass('Plan cancellation preserves completed history and cancels only unprocessed occurrences; mobile route renders');

  const devPath=existsSync(new URL('.local/runtime-dev.env',root))?'.local/runtime-dev.env':'.env.development';
  const dev=Object.fromEntries(readFileSync(new URL(devPath,root),'utf8').split(/\r?\n/).filter(l=>l.includes('=')&&!l.startsWith('#')).map(l=>{const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1).replace(/^['"]|['"]$/g,'')];}));
  const adminPage=await page();await login(adminPage,{email:dev.DEV_ADMIN_EMAIL,password:dev.DEV_ADMIN_PASSWORD});
  await adminPage.getByRole('navigation').getByRole('link',{name:'Users',exact:true}).click();await adminPage.getByLabel('Search users').fill('Browser consolidation '+stamp);await adminPage.getByRole('button',{name:'Search',exact:true}).click();await adminPage.getByRole('cell',{name:a.email,exact:true}).waitFor();await adminPage.getByRole('cell',{name:b.email,exact:true}).waitFor();
  pass('Preserved Admin Users page searches the actual registered browser fixtures');
  const c=await wallet('review-sender'),d=await wallet('review-receiver');
  for(let i=0;i<3;i++)await api('/transactions/withdraw',c.token,{accountId:c.account.id,amount:'1.00'});
  const critical=await api('/transactions/transfer',c.token,{senderAccountId:c.account.id,recipientAccountId:d.account.id,amount:'29000.00'});assert.equal(critical.status,'AWAITING_REVIEW');
  await adminPage.goto(site+'/admin/transactions/'+critical.id);await adminPage.getByLabel('Decision reason').fill('Browser consolidation verification: reviewed recorded critical-risk findings.');await adminPage.getByRole('button',{name:'Approve with fraud override',exact:true}).click();
  await until(()=>api('/transactions/'+critical.id,c.token),t=>t.status==='COMPLETED',20000);
  pass('Admin review UI approves a real critical transfer through the existing protected workflow');
  await adminPage.screenshot({path:new URL('.local/consolidated-admin-review.png',root).pathname.replace(/^\/([A-Za-z]:)/,'$1'),fullPage:true});
  assert.deepEqual(errors,[],'Browser runtime exceptions');
  console.log(`All ${checks.length} browser workflow groups passed.`);
}finally{
  if(recurringId&&owner){try{await api('/recurring-payments/'+recurringId+'/cancel',owner.token,{});}catch{/* Preserve the original failure; fixtures remain private. */}}
  writeFileSync(new URL('.local/consolidated-browser-credentials.json',root),JSON.stringify(credentials,null,2));
  writeFileSync(new URL('.local/consolidated-browser-report.json',root),JSON.stringify({at:new Date().toISOString(),checks,errors},null,2));
  await browser.close();
}
