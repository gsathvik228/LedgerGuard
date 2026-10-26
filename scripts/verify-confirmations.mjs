// Runs against native MySQL through the real API. Adds isolated development records only.
// For a quick expiry check, start the backend with CONFIRMATION_TTL_SECONDS=20.
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
const root=new URL('../',import.meta.url);
const base='http://127.0.0.1:8080/api';
const stamp=Date.now();
const credentials=[];
const groups=[];
async function request(path,token,body,status=200) {
    const r=await fetch(base+path,{method:body!==undefined?'POST':'GET',headers:{'Content-Type':'application/json','Idempotency-Key':randomUUID(),...(token?{Authorization:'Bearer '+token}:{})},body:body!==undefined?JSON.stringify(body):undefined,signal:AbortSignal.timeout(20000)});
    assert.equal(r.status,status,path+' unexpected HTTP status');
    return r.json();
}
function pass(label){groups.push(label);console.log('PASS '+label);}
async function wallet(label,funding='30000.00') {
    const user={email:'confirm-'+label+'-'+stamp+'@example.test',password:randomBytes(18).toString('hex'),fullName:'Confirmation '+label};
    credentials.push(user);
    const auth=await request('/auth/register',null,user);
    const account=await request('/accounts',auth.token,{accountType:'CHECKING',currency:'USD',initialBalance:'0.00'});
    await request('/transactions/deposit',auth.token,{accountId:account.id,amount:funding});
    return {...user,token:auth.token,id:account.id};
}
const balance=async w=>(await request('/accounts',w.token)).find(a=>a.id===w.id).balance;
const confirm=(w,t,password=w.password,status=200)=>request('/transactions/'+t.id+'/confirm',w.token,{password},status);
const cancel=(w,t,status=200)=>request('/transactions/'+t.id+'/cancel',w.token,{},status);
const withdraw=(w,amount)=>request('/transactions/withdraw',w.token,{accountId:w.id,amount});
async function run() {
    const recipient=await wallet('recipient','10.00');
    const transfer=(w,amount='10000.00')=>request('/transactions/transfer',w.token,{senderAccountId:w.id,recipientAccountId:recipient.id,amount});
    const sender=await wallet('concurrent');
    assert.equal((await withdraw(sender,'1.00')).status,'COMPLETED');
    const t=await transfer(sender);
    assert.equal(t.status,'PENDING_CONFIRMATION');assert.equal(t.canConfirm,true);
    assert.equal(await balance(sender),'29999.00');assert.equal(await balance(recipient),'10.00');
    const original=await request('/transactions/'+t.id+'/fraud',sender.token);
    assert.equal(original.decision,'CONFIRM');assert.equal(original.confirmation,null);
    pass('LOW processes normally; MEDIUM is pending with original assessment and unchanged balances');
    await confirm(recipient,t,recipient.password,403);await cancel(recipient,t,403);
    const dev=Object.fromEntries(readFileSync(new URL(existsSync(new URL('.local/runtime-dev.env',root))?'.local/runtime-dev.env':'.env.development',root),'utf8').replace(/^\uFEFF/,'').split(/\r?\n/).filter(l=>l.includes('=')).map(l=>{const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1)];}));
    const admin=await request('/auth/login',null,{email:dev.DEV_ADMIN_EMAIL,password:dev.DEV_ADMIN_PASSWORD});
    await request('/transactions/'+t.id+'/confirm',admin.token,{password:dev.DEV_ADMIN_PASSWORD},403);
    await request('/transactions/'+t.id+'/cancel',admin.token,{},403);
    assert.equal((await request('/transactions/'+t.id,recipient.token)).canConfirm,false);
    pass('Recipient and admin cannot confirm or cancel another user payment');
    await confirm(sender,t,'incorrect-password',400);
    assert.equal(await balance(sender),'29999.00');
    const concurrent=await Promise.all(Array.from({length:4},()=>confirm(sender,t)));
    assert.ok(concurrent.every(r=>r.status==='COMPLETED'));
    assert.equal(await balance(sender),'19999.00');assert.equal(await balance(recipient),'10010.00');
    assert.equal((await confirm(sender,t)).status,'COMPLETED');
    const assessed=await request('/transactions/'+t.id+'/fraud',sender.token);
    assert.deepEqual({...assessed,confirmation:null},original);
    assert.equal(assessed.confirmation.riskScore,45); // Including the pending row would incorrectly add rapid activity.
    assert.equal(assessed.confirmation.decision,'APPROVE');
    pass('Wrong password leaves funds intact; concurrent and duplicate confirmations execute once; pending row excluded from reassessment');

    const cancelledWallet=await wallet('cancel');
    const cancelled=await transfer(cancelledWallet);
    assert.equal((await cancel(cancelledWallet,cancelled)).status,'CANCELLED');
    assert.equal((await confirm(cancelledWallet,cancelled)).status,'CANCELLED');
    assert.equal(await balance(cancelledWallet),'30000.00');
    pass('Cancellation is final and leaves balances unchanged');

    const limitedWallet=await wallet('limited');
    const limited=await transfer(limitedWallet);
    for(let i=0;i<4;i++)await confirm(limitedWallet,limited,'incorrect-password',400);
    await confirm(limitedWallet,limited,'incorrect-password',429);
    await confirm(limitedWallet,limited,limitedWallet.password,429);
    const other=await transfer(limitedWallet);
    await confirm(limitedWallet,other,limitedWallet.password,429);
    assert.equal(await balance(limitedWallet),'30000.00');
    pass('Persisted failed-password limit applies across pending transactions for the same user');

    const spentWallet=await wallet('insufficient','15000.00');
    const spent=await transfer(spentWallet);
    assert.equal((await withdraw(spentWallet,'6000.00')).status,'COMPLETED');
    const rejected=await confirm(spentWallet,spent);
    assert.equal(rejected.status,'FAILED');assert.ok(rejected.outcomeReason.includes('Insufficient funds'));
    assert.equal(await balance(spentWallet),'9000.00');
    assert.equal((await request('/transactions/'+spent.id+'/fraud',spentWallet.token)).confirmation.decision,'REJECT');
    pass('Funds rechecked after authorization; rejection and reassessment persisted without debit');

    const criticalWallet=await wallet('critical');
    const critical=await transfer(criticalWallet,'29000.00');
    assert.equal(critical.status,'PENDING_CONFIRMATION');assert.equal(critical.riskLevel,'HIGH');
    const originalCritical=await request('/transactions/'+critical.id+'/fraud',criticalWallet.token);
    await withdraw(criticalWallet,'1.00');await withdraw(criticalWallet,'1.00');
    const beforeRecipient=await balance(recipient);
    const blocked=await confirm(criticalWallet,critical);
    assert.equal(blocked.status,'BLOCKED');assert.equal(blocked.riskLevel,'CRITICAL');
    assert.equal(await balance(criticalWallet),'29998.00');assert.equal(await balance(recipient),beforeRecipient);
    const criticalAudit=await request('/transactions/'+critical.id+'/fraud',criticalWallet.token);
    assert.deepEqual({...criticalAudit,confirmation:null},originalCritical);
    assert.equal(criticalAudit.confirmation.decision,'BLOCK');
    assert.equal(criticalAudit.confirmation.riskScore,90);
    assert.equal((await confirm(criticalWallet,critical)).status,'BLOCKED');
    const originallyBlocked=await transfer(criticalWallet,'29000.00');
    assert.equal(originallyBlocked.status,'AWAITING_REVIEW');
    assert.equal((await confirm(criticalWallet,originallyBlocked)).status,'AWAITING_REVIEW');
    pass('HIGH pending payment becomes CRITICAL on reassessment; original and confirmation assessments preserved; blocks cannot be overridden');

    const raceWallet=await wallet('cancel-race');
    const race=await transfer(raceWallet);
    const raceResults=await Promise.all([confirm(raceWallet,race),cancel(raceWallet,race)]);
    const final=await request('/transactions/'+race.id,raceWallet.token);
    assert.ok(['COMPLETED','CANCELLED'].includes(final.status));
    assert.ok(raceResults.every(r=>r.status===final.status));
    assert.equal(await balance(raceWallet),final.status==='COMPLETED'?'20000.00':'30000.00');
    pass('Concurrent cancellation and confirmation produce one consistent final outcome');

    const expiryWallet=await wallet('expiry');
    const expiring=await transfer(expiryWallet);
    const expiringCancel=await transfer(expiryWallet);
    const waitMs=Math.max(0,new Date(expiringCancel.confirmationExpiresAt).getTime()-Date.now())+6000;
    console.log('Waiting '+Math.ceil(waitMs/1000)+' seconds for the configured confirmation expiry...');
    // Chunked waits keep progress visible when verifying the default five-minute TTL.
    let remaining=waitMs;
    while(remaining>0){const chunk=Math.min(remaining,30000);await new Promise(r=>setTimeout(r,chunk));remaining-=chunk;if(remaining>0)console.log('Expiry check: '+Math.ceil(remaining/1000)+' seconds remaining');}
    assert.equal((await request('/transactions/'+expiring.id,expiryWallet.token)).status,'EXPIRED');
    assert.equal((await confirm(expiryWallet,expiring)).status,'EXPIRED');
    assert.equal((await cancel(expiryWallet,expiringCancel)).status,'EXPIRED');
    assert.equal(await balance(expiryWallet),'30000.00');
    const history=await request('/transactions?status=EXPIRED',expiryWallet.token);
    assert.ok(history.content.some(row=>row.id===expiring.id));
    pass('Scheduled expiry persists without user action; expired confirmation/cancellation cannot move funds; history filters reflect expiry');

    mkdirSync(new URL('.local/',root),{recursive:true});
    writeFileSync(new URL('.local/confirmation-credentials-'+stamp+'.json',root),JSON.stringify(credentials,null,2));
    writeFileSync(new URL('.local/confirmation-verification.json',root),JSON.stringify({verifiedAt:new Date().toISOString(),groups,transactionIds:{confirmed:t.id,cancelled:cancelled.id,failed:spent.id,blocked:critical.id,expired:expiring.id}},null,2));
    console.log('All '+groups.length+' confirmation verification groups passed.');
}
run().catch(e=>{console.error('FAIL '+e.message);process.exitCode=1;});
