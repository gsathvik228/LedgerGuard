import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { api, errorMessage } from './api';
import { OutcomeNotice } from './outcome';
import { useSubmission } from './submission';
import { ReviewActions, Timeline, type Beneficiary } from './workspace';
import { ConfirmationActions } from './confirmation';
import { useAuth } from './auth';
import type { Account, Analysis, Dashboard, Page, Session, Transaction } from './types';
import { Badge, Empty, ErrorBox, Loading, money, timestamp, title, TransactionTable, PersistedOutcome, useResource } from './ui';

function Heading({eyebrow,title:heading,children}:{eyebrow:string;title:string;children?:React.ReactNode}) {
  return <div className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{heading}</h1></div>{children}</div>;
}
export function AuthPage({register=false}:{register?:boolean}) {
  const {session,login,notice} = useAuth();
  const [error,setError] = useState('');
  const [busy,setBusy] = useState(false);
  const navigate = useNavigate();
  if(session) return <Navigate to={session.role==='ADMIN'?'/admin':'/'} replace/>;
  async function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('');
    const form = new FormData(event.currentTarget);
    const payload = {email:String(form.get('email')).trim(),password:form.get('password'),...(register?{fullName:String(form.get('fullName')).trim()}:{})};
    try {
      const response = await api.post<Session>('/auth/'+(register?'register':'login'),payload);
      login(response.data); navigate(response.data.role==='ADMIN'?'/admin':'/',{replace:true});
    } catch(e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  return <div className="auth-layout"><section className="auth-story"><Link className="brand" to="/login"><span className="brand-icon">L</span>LedgerGuard</Link><div><p className="eyebrow">YOUR MONEY, ACCOUNTED FOR</p><h1>A clearer view.<br/>A safer move.</h1><p>Manage accounts, move funds, and understand the checks behind every transaction.</p><div className="story-rule"><span>01</span> Precise account balances</div><div className="story-rule"><span>02</span> Transparent fraud decisions</div><div className="story-rule"><span>03</span> One connected ledger</div></div><small>Local development application · No real banking connections</small></section>
    <section className="auth-form"><p className="eyebrow">WELCOME TO LEDGERGUARD</p><h1>{register?'Create your account':'Welcome back'}</h1><p className="muted">{register?'Start with a customer account.':'Sign in to your financial workspace.'}</p>
      {notice&&<p role="status" className="notice">{notice}</p>}{error&&<ErrorBox message={error}/>}
      <form onSubmit={submit} key={register?'register':'login'}>
        {register&&<label>Full name<input name="fullName" autoComplete="name" required maxLength={120}/></label>}
        <label>Email address<input name="email" type="email" autoComplete="email" required maxLength={255}/></label>
        <label>Password<input name="password" type="password" autoComplete={register?'new-password':'current-password'} required minLength={register?8:undefined} maxLength={72}/></label>
        {register&&<small>Use at least 8 characters (maximum 72 UTF-8 bytes).</small>}
        <button disabled={busy}>{busy?'Please wait…':register?'Create account':'Sign in'} <span aria-hidden="true">→</span></button>
      </form><p>{register?'Already registered?':'New here?'} <Link to={register?'/login':'/register'} onClick={()=>setError('')}>{register?'Sign in':'Create an account'}</Link></p>
    </section></div>;
}
function balanceTotals(accounts:Account[]) {
  const totals:Record<string,bigint> = {};
  for(const account of accounts) {
    const [whole,fraction=''] = String(account.balance).split('.');
    const cents = BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0').slice(0,2));
    totals[account.currency]=(totals[account.currency]||0n)+cents;
  }
  return Object.entries(totals).map(([currency,cents])=>({currency,amount:(cents/100n)+'.'+String(cents%100n).padStart(2,'0')}));
}
function AccountCards({accounts}:{accounts:Account[]}) {
  if(!accounts.length) return <Empty>No accounts yet. <Link to="/accounts">Open your first account</Link>.</Empty>;
  return <div className="account-grid">{accounts.map(account=><article className="account-card" key={account.id}>
    <div className="split"><span>{title(account.accountType)}</span><Badge value={account.status}/></div><h2>{money(account.balance,account.currency)}</h2><p className="account-number">{account.accountNumber}</p><small>Account ID #{account.id} · Share this ID to receive transfers</small>
  </article>)}</div>;
}
export function CustomerDashboard() {
  const {session} = useAuth();
  const accounts=useResource<Account[]>('/accounts');
  const recent=useResource<Page<Transaction>>('/transactions?size=5');
  return <><Heading eyebrow="YOUR FINANCIAL WORKSPACE" title={'Hello, '+session?.fullName.split(' ')[0]}><Link className="button" to="/move">Move money ↗</Link></Heading>
    <section className="hero-panel"><div><p className="eyebrow">ACCOUNT OVERVIEW</p><h2>Every balance. One place.</h2><p>Balances stay separate by currency, so you always know where you stand.</p></div><div className="hero-mark" aria-hidden="true">LG</div></section>
    {accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:<><div className="stats-grid">{balanceTotals(accounts.data||[]).map(total=><article className="stat" key={total.currency}><small>Total {total.currency} balance</small><strong>{money(total.amount,total.currency)}</strong><span>Across your accounts</span></article>)}<article className="stat"><small>Your accounts</small><strong>{accounts.data?.length||0}</strong><Link to="/accounts">Manage accounts →</Link></article></div>
    <div className="section-heading"><h2>Your accounts</h2><Link to="/accounts">View all →</Link></div><AccountCards accounts={accounts.data||[]}/></>}
    <section className="panel"><div className="section-heading"><div><h2>Recent activity</h2><p className="muted">Incoming and outgoing transactions</p></div><Link to="/transactions">View history →</Link></div>
      {recent.loading?<Loading/>:recent.error?<ErrorBox message={recent.error} retry={recent.retry}/>:<TransactionTable rows={recent.data?.content||[]}/>}
    </section></>;
}
export function AccountsPage() {
  const [revision,setRevision]=useState(0), [busy,setBusy]=useState(false), [error,setError]=useState(''), [notice,setNotice]=useState('');
  const accounts=useResource<Account[]>('/accounts',revision);
  async function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form=event.currentTarget; const values=new FormData(form); setBusy(true);setError('');setNotice('');
    try { await api.post('/accounts',{accountType:values.get('accountType'),currency:values.get('currency'),initialBalance:'0.00'}); setRevision(v=>v+1);setNotice('Account opened with a zero balance. Add funds from Move money.');form.reset(); }
    catch(e){setError(errorMessage(e));} finally{setBusy(false);}
  }
  return <><Heading eyebrow="A HOME FOR YOUR FUNDS" title="Your accounts"/>
    <div className="two-column"><section><h2>Account ledger</h2>{accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:<AccountCards accounts={accounts.data||[]}/>}</section>
    <section className="panel"><h2>Open an account</h2><p className="muted">New accounts start at zero. No opening deposit required.</p>{error&&<ErrorBox message={error}/>}<form onSubmit={submit}>
      <label>Account type<select name="accountType"><option value="CHECKING">Checking</option><option value="SAVINGS">Savings</option></select></label>
      <label>Currency<select name="currency">{['USD','INR','EUR','GBP','CAD','AUD','JPY'].map(c=><option key={c}>{c}</option>)}</select></label>
      <button disabled={busy}>{busy?'Opening…':'Open account'}</button></form>{notice&&<p role="status" className="success">{notice}</p>}</section></div></>;
}
export function MoneyPage() {
  const post=useSubmission();const beneficiaries=useResource<Beneficiary[]>('/beneficiaries');const [recipient,setRecipient]=useState('');
  const [operation,setOperation]=useState('deposit'),[revision,setRevision]=useState(0),[busy,setBusy]=useState(false),[error,setError]=useState(''),[result,setResult]=useState<Transaction>();
  const accounts=useResource<Account[]>('/accounts',revision);
  const active=(accounts.data||[]).filter(a=>a.status==='ACTIVE');
  async function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); const values=new FormData(event.currentTarget);setError('');setResult(undefined);
    const amount=String(values.get('amount'));
    if(!/^\d{1,17}(\.\d{1,2})?$/.test(amount)||Number(amount)<=0) {setError('Enter a positive amount with at most two decimal places.');return;}
    const accountId=Number(values.get('accountId'));
    const recipientAccountId=Number(values.get('recipientAccountId'));
    if(operation==='transfer'&&accountId===recipientAccountId){setError('Choose a different recipient account.');return;}
    setBusy(true);
    try {
      const payload={amount,description:values.get('description'),...(operation==='transfer'?{senderAccountId:accountId,recipientAccountId}:{accountId})};
      const response=await post<Transaction>('/transactions/'+operation,payload);setResult(response.data);setRevision(v=>v+1);
    }catch(e){setError(errorMessage(e));}finally{setBusy(false);}
  }
  return <><Heading eyebrow="MAKE YOUR NEXT MOVE" title="Move money"/><div className="two-column"><section className="panel">
    <div className="tabs" aria-label="Operation">{['deposit','withdraw','transfer'].map(value=><button key={value} className={operation===value?'selected':'secondary'} disabled={busy} onClick={()=>{setOperation(value);setError('');setResult(undefined);}}>{title(value)}</button>)}</div>
    <h2>{operation==='deposit'?'Add funds':operation==='withdraw'?'Withdraw funds':'Send a transfer'}</h2>
    <p className="muted">{operation==='transfer'?'Transfers require both accounts to use the same currency.':'Development ledger operation. No external bank is charged.'}</p>
    {error&&<ErrorBox message={error}/>}
    {accounts.loading?<Loading/>:accounts.error?<ErrorBox message={accounts.error} retry={accounts.retry}/>:!active.length?<Empty>Create an active account before moving funds. <Link to="/accounts">Open account</Link></Empty>:<form onSubmit={submit} key={operation}>
      <label>{operation==='deposit'?'To account':'From account'}<select name="accountId" required>{active.map(a=><option key={a.id} value={a.id}>#{a.id} · {title(a.accountType)} · {money(a.balance,a.currency)}</option>)}</select></label>
      {operation==='transfer'&&<><label>Saved beneficiary<select value={recipient} onChange={e=>setRecipient(e.target.value)}><option value="">Enter recipient ID manually</option>{beneficiaries.data?.map(b=><option key={b.id} value={b.accountId}>{b.friendlyName} · {b.accountNumber} · {b.currency}</option>)}</select></label>{beneficiaries.error&&<ErrorBox message={beneficiaries.error} retry={beneficiaries.retry}/>}<label>Recipient account ID<input value={recipient} onChange={e=>setRecipient(e.target.value)} name="recipientAccountId" type="number" min="1" step="1" required placeholder="Ask the recipient for their account ID"/></label></>}
      <label>Amount<input name="amount" type="text" inputMode="decimal" pattern="[0-9]{1,17}(\.[0-9]{1,2})?" required placeholder="0.00"/></label>
      <label>Description <span className="muted">(optional)</span><input name="description" maxLength={255} placeholder="What is this transaction for?"/></label>
      <button disabled={busy}>{busy?'Processing…':title(operation)+' funds'}</button></form>}
    {result&&<div role="status" aria-live="polite"><PersistedOutcome key={result.id+result.status} transaction={result}/><ConfirmationActions transaction={result} onUpdated={value=>{setResult(value);setRevision(v=>v+1);}}/><Link to={'/transactions/'+result.id}>View transaction & fraud analysis</Link></div>}
    </section><section><div className="panel guidance"><p className="eyebrow">BUILT-IN CHECKS</p><h2>A decision you can understand.</h2><p>Outgoing transactions are checked for high amounts, rapid activity, unusual spending, repeated recipients, and low remaining balances.</p><p>New critical attempts await administrator review without moving or reserving funds. Medium and high risk require your password confirmation. Every decision remains in your history.</p></div><h2>Current balances</h2>{accounts.data&&<AccountCards accounts={accounts.data}/>}</section></div></>;
}
export function HistoryPage({admin=false,suspicious=false}:{admin?:boolean;suspicious?:boolean}) {
  const [filters,setFilters]=useState(''),[page,setPage]=useState(0),[filterError,setFilterError]=useState('');
  const endpoint=admin?(suspicious?'/admin/fraud':'/admin/transactions'):'/transactions';
  const resource=useResource<Page<Transaction>>(endpoint+'?size=10&page='+page+filters);
  function apply(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); const values=new FormData(event.currentTarget);const query=new URLSearchParams();setFilterError('');
    const from=String(values.get('from')||''),to=String(values.get('to')||'');
    if(from&&to&&from>to){setFilterError('Start date must be before end date.');return;}
    for(const [key,value] of values) if(value)query.set(key,key==='from'||key==='to'?new Date(String(value)).toISOString():String(value));
    setFilters('&'+query.toString());setPage(0);
  }
  return <><Heading eyebrow={admin?'ADMINISTRATION':'YOUR COMPLETE LEDGER'} title={suspicious?'Fraud review':admin?'All transactions':'Transaction history'}/><section className="panel">
    <p className="muted">{suspicious?'High and critical risk transactions, with decisions and explanations.':admin?'Monitor transactions across all customers.':'Includes incoming transfers and all activity on your accounts.'}</p>
    <form className="filters" onSubmit={apply} onReset={()=>{setFilters('');setPage(0);setFilterError('');}}>
      <label>Type<select name="type"><option value="">All types</option>{['DEPOSIT','WITHDRAWAL','TRANSFER'].map(v=><option key={v} value={v}>{title(v)}</option>)}</select></label>
      <label>Status<select name="status"><option value="">All statuses</option>{['PENDING_CONFIRMATION','AWAITING_REVIEW','COMPLETED','BLOCKED','REJECTED','CANCELLED','EXPIRED','FAILED'].map(v=><option key={v} value={v}>{title(v)}</option>)}</select></label>
      <label>Risk<select name="risk"><option value="">All levels</option>{(suspicious?['HIGH','CRITICAL']:['LOW','MEDIUM','HIGH','CRITICAL']).map(v=><option key={v} value={v}>{title(v)}</option>)}</select></label>
      <label>From (local time)<input name="from" type="datetime-local"/></label><label>To (local time)<input name="to" type="datetime-local"/></label><button>Apply filters</button><button type="reset" className="secondary">Clear</button>
    </form>{filterError&&<ErrorBox message={filterError}/>}
    {resource.loading?<Loading/>:resource.error?<ErrorBox message={resource.error} retry={resource.retry}/>:<><TransactionTable rows={resource.data?.content||[]} admin={admin}/><div className="pagination"><span>{resource.data?.totalElements||0} transactions · Page {page+1} of {Math.max(1,resource.data?.totalPages||0)}</span><div><button className="secondary" disabled={page===0} onClick={()=>setPage(p=>p-1)}>Previous</button><button className="secondary" disabled={page+1>=(resource.data?.totalPages||0)} onClick={()=>setPage(p=>p+1)}>Next</button></div></div></>}
  </section></>;
}
export function DetailPage({admin=false}:{admin?:boolean}) {
  const {id}=useParams();
  const [timelineRevision,setTimelineRevision]=useState(0);
  const prefix=admin?'/admin':'';
  const transaction=useResource<Transaction>(prefix+'/transactions/'+id);
  const analysis=useResource<Analysis>(prefix+'/transactions/'+id+'/fraud');
  const t=transaction.data;
  return <><Heading eyebrow="TRANSACTION EXPLORER" title={'Transaction #'+id}><Link className="button secondary" to={prefix+'/transactions'}>Back to history</Link></Heading>
    {transaction.loading?<Loading/>:transaction.error?<ErrorBox message={transaction.error} retry={transaction.retry}/>:t&&<section className="panel"><div className="split"><div><p className="eyebrow">{title(t.type)}</p><h2 className="amount">{money(t.amount,t.currency)}</h2></div><Badge value={t.status}/></div>
      <OutcomeNotice transaction={t} analysis={analysis.data} loading={analysis.loading} error={analysis.error} retry={analysis.retry}/><ConfirmationActions transaction={t} onUpdated={()=>{transaction.retry();analysis.retry();setTimelineRevision(n=>n+1);}}/>{admin&&<ReviewActions transaction={t} onUpdated={()=>{transaction.retry();analysis.retry();setTimelineRevision(n=>n+1);}}/>}{t.outcomeReason&&<p className="muted">{t.outcomeReason}</p>}
      <dl className="detail-grid"><div><dt>From</dt><dd>{t.senderAccountId?'Account #'+t.senderAccountId:'External deposit'}</dd></div><div><dt>To</dt><dd>{t.recipientAccountId?'Account #'+t.recipientAccountId:'External withdrawal'}</dd></div><div><dt>Recorded</dt><dd>{timestamp(t.createdAt)}</dd></div><div><dt>Description</dt><dd>{t.description||'No description'}</dd></div></dl></section>}
    <section className="panel"><h2>Original fraud assessment</h2>{analysis.loading?<Loading/>:analysis.error?<ErrorBox message={analysis.error} retry={analysis.retry}/>:analysis.data&&<><div className="risk-summary"><div className="risk-score">{analysis.data.riskScore}<small>/100</small></div><div><Badge value={analysis.data.riskLevel}/><h3>{'Decision: '+title(analysis.data.decision)}</h3><p className="muted">{timestamp(analysis.data.createdAt)}</p></div></div><div className="score-track"><div style={{width:analysis.data.riskScore+'%'}}/></div>
      <h3>Triggered rules</h3>{!analysis.data.rules.length?<Empty>No fraud rules were triggered.</Empty>:<div className="rule-list">{analysis.data.rules.map(rule=><article key={rule.ruleName}><div className="split"><strong>{title(rule.ruleName)}</strong><span>+{rule.scoreContribution} points</span></div><p>{rule.reason}</p><Badge value={rule.severity}/></article>)}</div>}<p className="muted">Scores: Low 0–29 · Medium 30–59 · High 60–79 · Critical 80–100. New critical attempts await admin review; a critical password reassessment remains blocked.</p></>}</section>{analysis.data?.confirmation&&<section className="panel"><h2>Confirmation-time assessment</h2><p>{timestamp(analysis.data.confirmation.createdAt)} · {title(analysis.data.confirmation.decision)}</p><p>Risk: {title(analysis.data.confirmation.riskLevel)} · Score: {analysis.data.confirmation.riskScore}/100</p>{analysis.data.confirmation.rules.length?<ul>{analysis.data.confirmation.rules.map((r,i)=><li key={i}>{r.reason} (+{r.scoreContribution})</li>)}</ul>:<p>No rules triggered during reassessment.</p>}</section>}{analysis.data?.review&&<section className="panel"><h2>Administrator reassessment</h2><p>{title(analysis.data.review.decision)} · {title(analysis.data.review.riskLevel)} · {analysis.data.review.riskScore}/100</p><p>{timestamp(analysis.data.review.createdAt)}</p><ul>{analysis.data.review.rules.map((r,i)=><li key={i}>{r.reason} (+{r.scoreContribution})</li>)}</ul></section>}{t&&<Timeline id={t.id} revision={timelineRevision}/>}</>;
}
function Chart({heading,values}:{heading:string;values:Record<string,number>}) {
  const maximum=Math.max(1,...Object.values(values));
  return <section className="panel"><h2>{heading}</h2><div className="bar-chart">{Object.entries(values).map(([label,count])=><div key={label}><div className="split"><span>{title(label)}</span><strong>{count}</strong></div><div className="bar-track"><div className={'bar '+label.toLowerCase()} style={{width:(100*count/maximum)+'%'}}/></div></div>)}</div></section>;
}
export function AdminDashboard() {
  const dashboard=useResource<Dashboard>('/admin/dashboard');
  const suspicious=useResource<Page<Transaction>>('/admin/fraud?size=5');
  const data=dashboard.data;
  return <><Heading eyebrow="ADMINISTRATION" title="Ledger monitoring"><Link className="button" to="/admin/fraud">Review fraud →</Link></Heading>
    {dashboard.loading?<Loading/>:dashboard.error?<ErrorBox message={dashboard.error} retry={dashboard.retry}/>:data&&<><div className="stats-grid"><article className="stat"><small>Transactions</small><strong>{data.totalTransactions}</strong><span>Across the entire ledger</span></article><article className="stat"><small>Blocked transactions</small><strong>{data.byStatus.BLOCKED||0}</strong><span>Funds protected</span></article><article className="stat"><small>High & critical risk</small><strong>{(data.byRisk.HIGH||0)+(data.byRisk.CRITICAL||0)}</strong><Link to="/admin/fraud">Review cases →</Link></article></div>
    <section className="panel"><h2>Completed transaction volume</h2><p className="muted">Gross recorded activity, including deposits, withdrawals, and transfers. Currencies are never combined.</p><div className="volume-list">{Object.entries(data.completedVolumeByCurrency).length?Object.entries(data.completedVolumeByCurrency).map(([currency,value])=><strong key={currency}>{money(value,currency)}</strong>):<Empty>No completed transactions yet.</Empty>}</div></section>
    <div className="charts-grid"><Chart heading="By risk level" values={data.byRisk}/><Chart heading="By status" values={data.byStatus}/><Chart heading="By type" values={data.byType}/></div></>}
    <section className="panel"><div className="section-heading"><h2>Recent high-risk activity</h2><Link to="/admin/transactions">All transactions →</Link></div>{suspicious.loading?<Loading/>:suspicious.error?<ErrorBox message={suspicious.error} retry={suspicious.retry}/>:<TransactionTable rows={suspicious.data?.content||[]} admin/>}</section></>;
}
