import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Link, NavLink, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { AuthProvider, Protected, useAuth } from './src/auth';
import { AuthPage, CustomerDashboard, AccountsPage, MoneyPage, HistoryPage, DetailPage, AdminDashboard } from './src/pages';
import { ReviewQueue, AuditPage, NotificationLink, NotificationsPage, BeneficiariesPage, StatementsPage, SecurityPage, InsightsPage } from './src/workspace';
import { UsersPage } from './src/users';
import { ScheduledPaymentsPage } from './src/scheduled';
import { RecurringPaymentsPage } from './src/recurring';
import { PaymentRequestsPage, PaymentRequestDetailPage } from './src/paymentRequests';
import './style.css';

function Layout() {
  const {session,logout} = useAuth();
  return <div className="shell"><aside className="sidebar">
    <Link className="brand" to="/"><span className="brand-icon">L</span><span>LedgerGuard<small>Clarity in every transaction</small></span></Link>
    <div className="nav-label">YOUR WORKSPACE</div><nav aria-label="Main navigation">
      <NavLink end to="/">Overview</NavLink><NavLink to="/accounts">Accounts</NavLink><NavLink to="/move">Move money</NavLink><NavLink to="/scheduled-payments">Scheduled payments</NavLink><NavLink to="/recurring-payments">Recurring payments</NavLink><NavLink to="/payment-requests">Payment requests</NavLink><NavLink to="/transactions">Transactions</NavLink><NavLink to="/beneficiaries">Beneficiaries</NavLink><NavLink to="/statements">Statements</NavLink><NavLink to="/insights">Insights</NavLink><NavLink to="/security">Security settings</NavLink>
      {session?.role==='ADMIN'&&<><div className="nav-label">ADMINISTRATION</div><NavLink end to="/admin">Monitoring overview</NavLink><NavLink to="/admin/transactions">All transactions</NavLink><NavLink to="/admin/fraud">Fraud monitoring</NavLink><NavLink to="/admin/reviews">Review queue</NavLink><NavLink to="/admin/audit">Audit trail</NavLink><NavLink to="/admin/users">Users</NavLink></>}
    </nav><div className="sidebar-bottom"><span className="live-dot"/> Local development ledger<p>Rule-based protection.<br/>An explanation for every decision.</p></div>
  </aside><div className="workspace"><header className="topbar"><span className="environment">DEVELOPMENT</span><NotificationLink/><div><strong>{session?.fullName}</strong><small>{session?.role==='ADMIN'?'Administrator':'Customer'}</small></div><button className="secondary" onClick={logout}>Sign out</button></header><main><Outlet/></main><footer>LedgerGuard · Transaction processing & fraud detection</footer></div></div>;
}
function App() {
  return <BrowserRouter><AuthProvider><Routes>
    <Route path="/login" element={<AuthPage/>}/><Route path="/register" element={<AuthPage register/>}/>
    <Route element={<Protected/>}><Route element={<Layout/>}>
      <Route path="/recurring-payments" element={<RecurringPaymentsPage/>}/>
      <Route index element={<CustomerDashboard/>}/><Route path="/scheduled-payments" element={<ScheduledPaymentsPage/>}/><Route path="/payment-requests" element={<PaymentRequestsPage/>}/><Route path="/payment-requests/:id" element={<PaymentRequestDetailPage/>}/><Route path="/notifications" element={<NotificationsPage/>}/><Route path="/beneficiaries" element={<BeneficiariesPage/>}/><Route path="/statements" element={<StatementsPage/>}/><Route path="/security" element={<SecurityPage/>}/><Route path="/insights" element={<InsightsPage/>}/><Route path="/accounts" element={<AccountsPage/>}/><Route path="/move" element={<MoneyPage/>}/>
      <Route path="/transactions" element={<HistoryPage key="customer"/>}/><Route path="/transactions/:id" element={<DetailPage/>}/>
      <Route element={<Protected admin/>}><Route path="/admin/users" element={<UsersPage/>}/><Route path="/admin/reviews" element={<ReviewQueue/>}/><Route path="/admin/audit" element={<AuditPage/>}/><Route path="/admin" element={<AdminDashboard/>}/><Route path="/admin/transactions" element={<HistoryPage key="admin" admin/>}/><Route path="/admin/fraud" element={<HistoryPage key="fraud" admin suspicious/>}/><Route path="/admin/transactions/:id" element={<DetailPage admin/>}/></Route>
      <Route path="*" element={<section className="panel"><h1>Page not found</h1><Link to="/">Back to overview</Link></section>}/>
    </Route></Route>
    <Route path="*" element={<Navigate to="/login" replace/>}/>
  </Routes></AuthProvider></BrowserRouter>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><App/></StrictMode>);
