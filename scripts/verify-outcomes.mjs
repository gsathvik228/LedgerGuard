// Real API verification plus server-rendered checks of the shared outcome component.
// Uses existing frontend dependencies; creates only new development records.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';

const root = new URL('../', import.meta.url);
const require = createRequire(new URL('frontend/package.json', root));
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const compiled = ts.transpileModule(readFileSync(new URL('frontend/src/outcome.tsx', root),'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 }
}).outputText;
const component = {};
new Function('require','exports',compiled)(require,component);
const { OutcomeNotice } = component;
const escape = text => text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#x27;');

async function request(path, token, body) {
  const response = await fetch('http://127.0.0.1:8080/api'+path, {
    method: body ? 'POST':'GET',
    headers: { 'Content-Type':'application/json', ...(token?{Authorization:'Bearer '+token}:{}) },
    body: body ? JSON.stringify(body):undefined,
    signal: AbortSignal.timeout(15000)
  });
  assert.equal(response.status,200,path+' returned unexpected HTTP status');
  return response.json();
}
async function run() {
  const stamp = Date.now();
  const credentials = {email:'outcome-'+stamp+'@example.test',password:randomBytes(20).toString('hex'),fullName:'Outcome verification'};
  const {token} = await request('/auth/register',null,credentials);
  mkdirSync(new URL('.local/',root),{recursive:true});
  writeFileSync(new URL('.local/outcome-credentials-'+stamp+'.json',root),JSON.stringify(credentials,null,2));
  const sender=await request('/accounts',token,{accountType:'CHECKING',currency:'USD',initialBalance:'0.00'});
  const recipient=await request('/accounts',token,{accountType:'SAVINGS',currency:'USD',initialBalance:'0.00'});
  const balances=async()=> (await request('/accounts',token)).map(a=>({id:a.id,balance:a.balance})).sort((a,b)=>a.id-b.id);
  await request('/transactions/deposit',token,{accountId:sender.id,amount:'30000.00'});
  let flagged=await request('/transactions/transfer',token,{senderAccountId:sender.id,recipientAccountId:recipient.id,amount:'10000.00',description:'Completed suspicious outcome verification'});
  assert.equal(flagged.status,'PENDING_CONFIRMATION');
  assert.deepEqual(await balances(),[{id:sender.id,balance:'30000.00'},{id:recipient.id,balance:'0.00'}]);
  flagged=await request('/transactions/'+flagged.id+'/confirm',token,{password:credentials.password});
  assert.equal(flagged.status,'COMPLETED');
  assert.equal(flagged.riskLevel,'MEDIUM');
  assert.deepEqual(await balances(),[{id:sender.id,balance:'20000.00'},{id:recipient.id,balance:'10000.00'}]);
  await request('/transactions/withdraw',token,{accountId:sender.id,amount:'1.00'});
  const before=await balances();
  const blocked=await request('/transactions/transfer',token,{senderAccountId:sender.id,recipientAccountId:recipient.id,amount:'19500.00',description:'Blocked outcome verification'});
  assert.equal(blocked.status,'BLOCKED');assert.equal(blocked.riskLevel,'CRITICAL');
  assert.deepEqual(await balances(),before);
  let high=await request('/transactions/transfer',token,{senderAccountId:sender.id,recipientAccountId:recipient.id,amount:'10000.00',description:'High-risk completed outcome verification'});
  assert.equal(high.status,'PENDING_CONFIRMATION');
  high=await request('/transactions/'+high.id+'/confirm',token,{password:credentials.password});
  assert.equal(high.status,'COMPLETED');assert.equal(high.riskLevel,'HIGH');
  assert.deepEqual(await balances(),[{id:sender.id,balance:'9999.00'},{id:recipient.id,balance:'20000.00'}]);

  for(const transaction of [flagged,high,blocked]) {
    const detail=await request('/transactions/'+transaction.id,token);
    const analysis=await request('/transactions/'+transaction.id+'/fraud',token);
    assert.equal(detail.status,transaction.status);
    assert.ok(analysis.rules.length>0);
    assert.equal(analysis.confirmation?.decision || analysis.decision,transaction.status==='BLOCKED'?'BLOCK':'APPROVE');
    for(const compact of [false,true]) {
      const html=renderToStaticMarkup(React.createElement(OutcomeNotice,{transaction:detail,analysis,compact}));
      assert.ok(html.includes(transaction.status==='BLOCKED'?'Blocked':'Suspicious activity detected'));
      assert.ok(html.includes(transaction.status==='BLOCKED'?'All account balances remain unchanged by this attempt':'The amount was debited from the sender account and credited to the recipient account'));
      assert.ok(html.includes('not proof of fraud'));
      assert.ok(html.includes('does not initiate a human review'));
      assert.ok(html.includes('Score: '+transaction.riskScore+'/100'));
      for(const rule of analysis.rules) assert.ok(html.includes(escape(rule.reason)),'Persisted reason missing from rendered outcome');
    }
    const loading=renderToStaticMarkup(React.createElement(OutcomeNotice,{transaction,loading:true}));
    assert.ok(loading.includes('Loading the recorded transaction analysis'));
    assert.ok(loading.includes(transaction.status==='BLOCKED'?'The transaction was blocked before funds were transferred':'The transfer was completed'));
    const failed=renderToStaticMarkup(React.createElement(OutcomeNotice,{transaction,error:'Unavailable',retry:()=>{}}));
    assert.ok(failed.includes('The recorded analysis is temporarily unavailable'));
    assert.ok(failed.includes('Reload analysis'));
    assert.ok(!failed.includes('No rule explanations are available in the recorded analysis'));
    console.log('PASS '+transaction.riskLevel+' '+transaction.status+': balances, persisted reasons, full/compact warning, loading and retry states');
  }
  const history=await request('/transactions?size=20',token);
  for(const t of [flagged,high,blocked]) assert.ok(history.content.some(row=>row.id===t.id&&row.status===t.status&&row.riskLevel===t.riskLevel));
  console.log('PASS History retains flagged and blocked outcomes');
  const report={verifiedAt:new Date().toISOString(),flagged:{id:flagged.id,risk:flagged.riskLevel,score:flagged.riskScore},high:{id:high.id,risk:high.riskLevel,score:high.riskScore},blocked:{id:blocked.id,risk:blocked.riskLevel,score:blocked.riskScore}};
  writeFileSync(new URL('.local/outcome-verification.json',root),JSON.stringify(report,null,2));
}
run().catch(error=>{console.error('FAIL '+error.message);process.exitCode=1;});
