import type { Analysis, Transaction } from './types';

export const isSuspicious = (transaction: Transaction) =>
  ['MEDIUM', 'HIGH', 'CRITICAL'].includes(transaction.riskLevel);

export function outcomeMessage(transaction: Transaction) {
  if (transaction.status === 'AWAITING_REVIEW') return 'Awaiting admin review. No funds have moved or been reserved; account balances are unchanged. An administrator must approve or reject this request.';
  if (transaction.status === 'REJECTED') return 'An administrator rejected this request. No funds moved and balances remain unchanged by this attempt.';
  if (transaction.status === 'BLOCKED') return 'The transaction was blocked before funds were transferred. All account balances remain unchanged by this attempt.';
  if (transaction.status === 'PENDING_CONFIRMATION') return 'This payment is awaiting your password authorization. No funds have moved or been reserved; account balances are unchanged.';
  if (transaction.status === 'CANCELLED') return 'The payment was cancelled. No funds moved and account balances were not changed by this payment.';
  if (transaction.status === 'EXPIRED') return 'The authorization period expired. No funds moved and account balances were not changed by this payment.';
  if (transaction.status !== 'COMPLETED') return transaction.outcomeReason || 'The transaction could not be completed. No funds moved.';
  if (transaction.type === 'TRANSFER') return 'The transfer was completed. The amount was debited from the sender account and credited to the recipient account.';
  if (transaction.type === 'WITHDRAWAL') return 'The withdrawal was completed and the amount was deducted from the account balance.';
  return 'The deposit was completed and the amount was added to the account balance.';
}

type Props = {
  transaction: Transaction;
  analysis?: Analysis;
  loading?: boolean;
  error?: string;
  retry?: () => void;
  compact?: boolean;
};

// Display stored rule reasons verbatim. Never infer reasons from a score or rule name.
export function OutcomeNotice({ transaction, analysis, loading, error, retry, compact=false }: Props) {
  const blocked = transaction.status === 'BLOCKED';
  const flagged = transaction.status === 'COMPLETED' && isSuspicious(transaction);
  const pending = ['PENDING_CONFIRMATION','AWAITING_REVIEW'].includes(transaction.status);
  const warning = blocked || flagged || pending || isSuspicious(transaction);
  const currentAnalysis = analysis?.review || analysis?.confirmation || analysis;
  const label = transaction.status==='AWAITING_REVIEW' ? 'Awaiting admin review' : transaction.status==='REJECTED' ? 'Rejected' : pending ? 'Pending confirmation' : transaction.status==='CANCELLED' ? 'Cancelled' : transaction.status==='EXPIRED' ? 'Expired' : blocked ? 'Blocked' : flagged ? 'Suspicious activity detected' : transaction.status === 'COMPLETED' ? 'Completed' : 'Not completed';
  return <div className={'outcome-notice '+(blocked?'outcome-blocked':pending||flagged?'outcome-flagged':transaction.status==='COMPLETED'?'outcome-completed':'outcome-neutral')+(compact?' outcome-compact':'')}>
    <span className={'badge outcome-badge '+(blocked?'blocked':pending||flagged?'medium':transaction.status==='COMPLETED'?'completed':'cancelled')}>{label}</span>
    {pending && <span className="outcome-state">Suspicious activity detected</span>}
    {flagged && <span className="outcome-state">Transaction completed</span>}
    <p className="outcome-impact">{outcomeMessage(transaction)}</p>
    {warning && <>
      <strong className="outcome-reasons-title">{blocked ? 'Reason for blocking' : 'Activity that triggered this alert'}</strong>
      {loading && <p className="outcome-loading" role="status">Loading the recorded transaction analysis...</p>}
      {error && <div className="outcome-error" role="alert">The recorded analysis is temporarily unavailable. This does not change the transaction outcome shown above.
        {retry && <button type="button" className="secondary" onClick={retry}>Reload analysis</button>}
      </div>}
      {!loading && !error && currentAnalysis && (currentAnalysis.rules.length
        ? <ul className="outcome-reasons">{currentAnalysis.rules.map((rule,index)=><li key={rule.ruleName+index}>{rule.reason}</li>)}</ul>
        : <p>No rule explanations are available in the recorded analysis.</p>)}
      <p className="outcome-risk">Risk: {transaction.riskLevel.toLowerCase()} · Score: {transaction.riskScore}/100</p>
      <p className="outcome-disclaimer">This alert identifies unusual transaction activity, not proof of fraud. Medium and high risk require password authorization. New critical attempts enter administrator review; historical blocked attempts remain non-executable.</p>
    </>}
  </div>;
}
