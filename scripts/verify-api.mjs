// Native Node.js API verification. Creates new development records; never deletes data.
import assert from 'node:assert/strict';
import { randomBytes, createHmac } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const base='http://127.0.0.1:8080/api';
const stamp=Date.now();
const password=randomBytes(20).toString('hex');
const customers=[
  {email:'demo-alice-'+stamp+'@example.test',password,fullName:'Alice Demo'},
  {email:'demo-bob-'+stamp+'@example.test',password,fullName:'Bob Demo'}
];
const checks=[];
function envFile(name) {
  return Object.fromEntries(readFileSync(root+name,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/).filter(l=>l.trim()&&!l.trim().startsWith('#')).map(l=>{
    const i=l.indexOf('=');let value=l.slice(i+1).trim();
    if((value.startsWith('"')&&value.endsWith('"'))||(value.startsWith("'")&&value.endsWith("'")))value=value.slice(1,-1);
    return [l.slice(0,i).trim(),value];
  }));
}
const dev=envFile('.env.development');
const privateInfo={createdAt:new Date().toISOString(),customers,admin:{email:dev.DEV_ADMIN_EMAIL,password:dev.DEV_ADMIN_PASSWORD}};
mkdirSync(root+'.local',{recursive:true});
writeFileSync(root+'.local/demo-'+stamp+'.json',JSON.stringify(privateInfo,null,2));
writeFileSync(root+'.local/demo-credentials.json',JSON.stringify(privateInfo,null,2));

async function request(path,{token,body,status=200,method}={}) {
  const response=await fetch(base+path,{
    method:method||(body?'POST':'GET'),
    headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
    body:body===undefined?undefined:JSON.stringify(body),
    signal:AbortSignal.timeout(15000)
  });
  assert.equal(response.status,status,path+' returned unexpected HTTP status');
  return response.status===204?null:response.json();
}
function pass(label) { checks.push(label);console.log('PASS '+label); }
async function balances(token) { return (await request('/accounts',{token})).map(a=>({id:a.id,balance:a.balance})).sort((a,b)=>a.id-b.id); }
async function run() {
  const alice=await request('/auth/register',{body:{...customers[0],role:'ADMIN'}});
  const bob=await request('/auth/register',{body:customers[1]});
  assert.equal(alice.role,'CUSTOMER');assert.equal(bob.role,'CUSTOMER');
  pass('Public registration is CUSTOMER-only, including attempted role injection');
  await request('/auth/register',{body:{...customers[0],email:customers[0].email.toUpperCase()},status:409});
  await request('/auth/register',{body:{email:null,password:'valid-password',fullName:'Invalid'},status:400});
  pass('Duplicate email and invalid registration rejected');
  const a=(await request('/auth/login',{body:customers[0]})).token;
  const b=(await request('/auth/login',{body:customers[1]})).token;
  await request('/auth/login',{body:{email:customers[0].email,password:'wrong-password'},status:401});
  assert.equal((await request('/me',{token:a})).email,customers[0].email);
  pass('Login, invalid credentials, and authenticated session profile');
  await request('/accounts',{status:401});
  await request('/accounts',{token:'invalid-token',status:401});
  const config=envFile('.env');
  const header=Buffer.from(JSON.stringify({alg:'HS512',typ:'JWT'})).toString('base64url');
  const claims=Buffer.from(JSON.stringify({sub:customers[0].email,exp:Math.floor(Date.now()/1000)-60})).toString('base64url');
  const signature=createHmac('sha512',config.JWT_SECRET).update(header+'.'+claims).digest('base64url');
  await request('/accounts',{token:header+'.'+claims+'.'+signature,status:401});
  pass('Missing, invalid, and expired JWTs return 401');

  const first=await request('/accounts',{token:a,body:{accountType:'CHECKING',currency:'USD',initialBalance:'0.00'}});
  const second=await request('/accounts',{token:b,body:{accountType:'SAVINGS',currency:'USD',initialBalance:'0.00'}});
  const euro=await request('/accounts',{token:b,body:{accountType:'SAVINGS',currency:'EUR',initialBalance:'0.00'}});
  assert.equal(first.balance,'0.00');
  assert.equal((await request('/accounts',{token:b})).find(x=>x.id===second.id).accountType,'SAVINGS');
  await request('/accounts',{token:a,body:{accountType:'INVALID',currency:'USD',initialBalance:0},status:400});
  pass('Zero-balance accounts, stored account types, and account validation');

  const deposit=await request('/transactions/deposit',{token:a,body:{accountId:first.id,amount:'30000.00',description:'Demo starting funds'}});
  assert.equal(deposit.status,'COMPLETED');
  await request('/transactions/deposit',{token:b,body:{accountId:second.id,amount:'50.00',description:'Recipient funds'}});
  const withdrawal=await request('/transactions/withdraw',{token:a,body:{accountId:first.id,amount:'50.00',description:'Demo withdrawal'}});
  assert.equal(withdrawal.status,'COMPLETED');
  const transfer=await request('/transactions/transfer',{token:a,body:{senderAccountId:first.id,recipientAccountId:second.id,amount:'100.25',description:'Demo payment to Bob'}});
  assert.equal(transfer.status,'COMPLETED');
  assert.equal((await balances(a))[0].balance,'29849.75');
  assert.equal((await balances(b)).find(x=>x.id===second.id).balance,'150.25');
  pass('Deposit, withdrawal, and transfer update exact balances');
  const incoming=await request('/transactions?type=TRANSFER',{token:b});
  assert.ok(incoming.content.some(t=>t.id===transfer.id));
  assert.equal((await request('/transactions/'+transfer.id,{token:b})).id,transfer.id);
  assert.equal((await request('/transactions/'+transfer.id+'/fraud',{token:b})).decision,'APPROVE');
  pass('Recipient sees incoming transfer, transaction detail, and fraud detail');
  await request('/transactions/'+withdrawal.id,{token:b,status:404});
  await request('/transactions/'+withdrawal.id+'/fraud',{token:b,status:404});
  pass('Unrelated transaction and fraud detail access denied');

  const beforeA=await balances(a),beforeB=await balances(b);
  const rejects=[
    ['/transactions/withdraw',{accountId:first.id,amount:'999999.00'},a,400],
    ['/transactions/deposit',{accountId:first.id,amount:'-1'},a,400],
    ['/transactions/withdraw',{accountId:first.id,amount:'0'},a,400],
    ['/transactions/deposit',{accountId:first.id,amount:'1.001'},a,400],
    ['/transactions/transfer',{senderAccountId:first.id,recipientAccountId:first.id,amount:'1'},a,400],
    ['/transactions/transfer',{senderAccountId:first.id,recipientAccountId:euro.id,amount:'1'},a,400],
    ['/transactions/deposit',{accountId:first.id,amount:'1'},b,403],
    ['/transactions/withdraw',{accountId:first.id,amount:'1'},b,403],
    ['/transactions/transfer',{senderAccountId:first.id,recipientAccountId:second.id,amount:'1'},b,403]
  ];
  for(const [path,body,token,status] of rejects) await request(path,{body,token,status});
  assert.deepEqual(await balances(a),beforeA);assert.deepEqual(await balances(b),beforeB);
  pass('Invalid amounts, insufficient funds, same-account, cross-currency, and ownership failures leave balances unchanged');

  const blocked=await request('/transactions/transfer',{token:a,body:{senderAccountId:first.id,recipientAccountId:second.id,amount:'29000.00',description:'Reproducible critical-risk demo'}});
  assert.equal(blocked.status,'BLOCKED');assert.equal(blocked.riskLevel,'CRITICAL');assert.ok(blocked.riskScore>=80);
  assert.deepEqual(await balances(a),beforeA);assert.deepEqual(await balances(b),beforeB);
  const analysis=await request('/transactions/'+blocked.id+'/fraud',{token:a});
  assert.equal(analysis.decision,'BLOCK');
  assert.ok(analysis.rules.some(r=>r.ruleName==='HIGH_VALUE'));
  assert.ok(analysis.rules.some(r=>r.ruleName==='RAPID_TRANSACTIONS'));
  assert.ok(analysis.rules.some(r=>r.ruleName==='BALANCE_RISK'));
  assert.equal((await request('/transactions/'+blocked.id,{token:b})).status,'BLOCKED');
  pass('CRITICAL transfer persists BLOCKED with explainable rules and unchanged balances');

  const filtered=await request('/transactions?status=BLOCKED&risk=CRITICAL&type=TRANSFER&size=1',{token:a});
  assert.equal(filtered.content.length,1);assert.equal(filtered.content[0].id,blocked.id);
  const page0=await request('/transactions?size=1&page=0',{token:a});
  const page1=await request('/transactions?size=1&page=1',{token:a});
  assert.notEqual(page0.content[0].id,page1.content[0].id);assert.ok(page0.totalElements>=4);
  const dated=await request('/transactions?from='+encodeURIComponent(new Date(stamp-1000).toISOString())+'&to='+encodeURIComponent(new Date(Date.now()+1000).toISOString()),{token:a});
  assert.ok(dated.content.some(t=>t.id===transfer.id));
  await request('/transactions?from=2030-01-01T00:00:00Z&to=2020-01-01T00:00:00Z',{token:a,status:400});
  await request('/transactions?size=0',{token:a,status:400});
  await request('/transactions?type=NOT_A_TYPE',{token:a,status:400});
  pass('Transaction filters, date ranges, stable server pagination, and invalid filter validation');

  for(const path of ['/admin/dashboard','/admin/transactions','/admin/fraud','/admin/transactions/'+transfer.id,'/admin/transactions/'+transfer.id+'/fraud'])
    await request(path,{token:a,status:403});
  pass('Customers denied from every admin API');
  const admin=await request('/auth/login',{body:{email:dev.DEV_ADMIN_EMAIL,password:dev.DEV_ADMIN_PASSWORD}});
  assert.equal(admin.role,'ADMIN');
  const metrics=await request('/admin/dashboard',{token:admin.token});
  assert.ok(metrics.totalTransactions>=5);assert.ok(metrics.byStatus.BLOCKED>=1);assert.ok(Number(metrics.completedVolumeByCurrency.USD)>0);
  const all=await request('/admin/transactions?size=1',{token:admin.token});
  assert.equal(all.content.length,1);assert.ok(all.totalElements>=5);
  const monitored=await request('/admin/fraud?risk=CRITICAL&status=BLOCKED&size=100',{token:admin.token});
  assert.ok(monitored.content.some(t=>t.id===blocked.id));
  assert.equal((await request('/admin/transactions/'+transfer.id,{token:admin.token})).id,transfer.id);
  assert.equal((await request('/admin/transactions/'+blocked.id+'/fraud',{token:admin.token})).decision,'BLOCK');
  pass('Development admin login, currency-separated metrics, paginated monitoring, and details');

  const reciprocal=await Promise.all([
    request('/transactions/transfer',{token:a,body:{senderAccountId:first.id,recipientAccountId:second.id,amount:'1.00',description:'Concurrent transfer A'}}),
    request('/transactions/transfer',{token:b,body:{senderAccountId:second.id,recipientAccountId:first.id,amount:'1.00',description:'Concurrent transfer B'}})
  ]);
  assert.ok(reciprocal.every(t=>t.status==='COMPLETED'));
  assert.deepEqual(await balances(a),beforeA);assert.deepEqual(await balances(b),beforeB);
  pass('Concurrent reciprocal transfers complete without deadlock or lost balance updates');

  const proxy=await fetch('http://127.0.0.1:5173/api/accounts',{headers:{Authorization:'Bearer '+a}});
  assert.equal(proxy.status,200);assert.ok((await proxy.json()).some(x=>x.id===first.id));
  pass('Vite /api proxy serves authenticated real backend data');
  const report={verifiedAt:new Date().toISOString(),checks,accountIds:{alice:first.id,bob:second.id,bobEur:euro.id},transactionIds:{deposit:deposit.id,withdrawal:withdrawal.id,transfer:transfer.id,blocked:blocked.id},criticalRiskScore:blocked.riskScore};
  writeFileSync(root+'.local/verification-latest.json',JSON.stringify(report,null,2));
  console.log('All '+checks.length+' verification groups passed. Private demo logins: .local/demo-credentials.json');
}
run().catch(error=>{console.error('FAIL '+error.message);process.exitCode=1;});
