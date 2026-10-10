# LedgerGuard — Transaction Processing & Fraud Detection

A working local development ledger built with Spring Boot, MySQL, React, TypeScript, and Vite. It includes customer banking flows, JWT authentication, deterministic fraud decisions, and admin monitoring. It does not connect to real payment networks.

## Open the running application

- Frontend: http://127.0.0.1:5173
- Backend: http://127.0.0.1:8080
- API documentation: http://127.0.0.1:8080/swagger-ui/index.html
- Private demo logins: `.local/demo-credentials.json` (open locally in VS Code; do not share or commit).
- Latest verification results and demo account/transaction IDs: `.local/verification-latest.json`.

The API root is not a public landing page; use the frontend or API documentation. Two demo customers and a development admin were created. Existing database records were preserved.

## Native Windows startup

Use the installed Java 25, Node/npm, native MySQL, and the checked-in Maven Wrapper. The Java compilation target remains 21. No additional system software, Docker, WSL, global Maven, or browser automation package is needed.

The existing MySQL service must be running. Database `fraud_db` and user `fraud_app` have already been configured. Do not rerun provisioning or reset data during ordinary startup.

Backend terminal:

~~~powershell
Set-Location C:\Projects\Full-Stack\transaction-fraud-system
java -version
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-backend.ps1 -Dev
~~~

Frontend terminal:

~~~powershell
Set-Location C:\Projects\Full-Stack\transaction-fraud-system\frontend
npm.cmd ci
npm.cmd run dev
~~~

Skip starting another server if the URLs above already respond. Use Ctrl+C in the server terminal to stop it. The current verified session leaves both servers running.

For ordinary customer operation without development admin provisioning, omit `-Dev`. Existing admin records remain valid; that switch controls provisioning, not authorization.

## Configuration and private files

The root `.env` contains MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD, JWT_SECRET, and JWT_EXPIRATION_MS. Preserve this file. Optional DB_URL overrides the default localhost:3306 database URL; fraud thresholds can also be supplied using the existing FRAUD_* variables.

`start-backend.ps1` resolves files relative to itself, loads `.env`, and optionally loads `.env.development` with `-Dev`. Existing process environment variables take precedence. Variables loaded by the script are removed from its process when it exits. Use literal `NAME=value` entries, optionally surrounded by matching quotes; comments belong on their own lines. The script never executes file contents or prints values.

The wrapper honors JAVA_HOME if set, otherwise uses Java on PATH. It works from the root or backend folder. Direct Maven startup does not load `.env`; use the launcher.

Vite reads root environment files, but only VITE_-prefixed keys are exposed to frontend code. Never put credentials in VITE_ variables. Axios uses relative `/api` URLs. The dev proxy sends them to http://127.0.0.1:8080, with no CORS workarounds. Production hosting would require its own `/api` reverse proxy; nothing has been published.

`.gitignore` excludes `.env`, `.env.*` except the example, `.local/`, logs, Maven target, node_modules, and frontend dist. The project is tracked with Git; private configuration and demo credentials must remain untracked.

## Controlled development admin setup

This is already configured for the current demo. To prepare a fresh local configuration:

~~~powershell
Set-Location C:\Projects\Full-Stack\transaction-fraud-system
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\init-dev-admin.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-backend.ps1 -Dev
~~~

The initialization script generates a random password in git-ignored `.env.development`; it preserves an existing file. The backend initializer requires BOTH the `dev` Spring profile and DEV_ADMIN_ENABLED=true. It reads DEV_ADMIN_EMAIL and DEV_ADMIN_PASSWORD, creates a new ADMIN with a BCrypt hash, never resets an existing password, and refuses to promote a CUSTOMER using the same email. Choose an unused email if there is a conflict.

Public registration has no role field and always creates CUSTOMER users. Admin routes are protected by server authorization, independently of the UI.

## Implemented behavior

- Register/login with validated inputs, normalized emails, BCrypt, and expiring JWTs.
- Login persistence across refresh, logout, expired-session handling, protected customer/admin routes, and server-side profile revalidation.
- Checking/savings accounts, stored account types, zero opening balance in the UI, and balances grouped by currency.
- Deposits, withdrawals, and transfers with positive two-decimal amounts, ownership and active-account checks, sufficient funds, same-account rejection, and currency matching.
- BigDecimal money calculations and decimal-string JSON amounts; the browser sums balances using integer cents.
- Atomic transactions, pessimistic account locks, and ascending account-ID lock order for transfers.
- Incoming and outgoing transaction visibility for participating customers; unrelated transaction and fraud details return 404.
- Type/status/risk/date filters, stable server-side pagination, and detailed fraud explanations.
- Critical outgoing transactions persist as BLOCKED with no balance changes. Fraud rules remain HIGH_VALUE, RAPID_TRANSACTIONS, REPEATED_TRANSFER, BALANCE_RISK, and UNUSUAL_AMOUNT.
- Admin dashboard with real counts/charts, completed gross volume separated by currency, paginated transaction/fraud monitoring, and detail access.
- Responsive pages with loading, empty, validation, failure/retry, and blocked/completed states.

Hibernate schema validation remains enabled. The existing V1 Flyway migration was preserved. The currency entity mapping was corrected to match its existing CHAR(3) column; no schema reset was used.

## API contracts

All routes below use the `/api` prefix. Protected requests send `Authorization: Bearer <token>`. Account and transaction amounts are decimal strings in responses.

| Method | Route | Purpose |
| --- | --- | --- |
| POST | /auth/register | email, password, fullName; creates CUSTOMER |
| POST | /auth/login | email, password; returns token and profile |
| GET | /me | Current authenticated profile |
| GET / POST | /accounts | List own accounts / create account |
| POST | /transactions/deposit | accountId, amount, optional description |
| POST | /transactions/withdraw | accountId, amount, optional description |
| POST | /transactions/transfer | senderAccountId, recipientAccountId, amount, optional description |
| GET | /transactions | Customer activity, including incoming transfers |
| GET | /transactions/{id} | Participant or admin transaction details |
| GET | /transactions/{id}/fraud | Participant or admin fraud analysis |
| GET | /admin/dashboard | Counts and completedVolumeByCurrency |
| GET | /admin/transactions | All activity, paginated |
| GET | /admin/fraud | HIGH/CRITICAL activity, paginated |
| GET | /admin/transactions/{id} | Admin transaction details |
| GET | /admin/transactions/{id}/fraud | Admin fraud analysis |

Account creation takes accountType (CHECKING or SAVINGS), currency (three uppercase letters), and initialBalance; the frontend submits zero. Transaction lists accept type, status, risk, from, to, page, and size (1–100). Dates are ISO-8601 instants. Pages include content, number, size, totalElements, and totalPages. UI date filters use local time and send UTC instants.

## Tests and reproducible verification

~~~powershell
Set-Location C:\Projects\Full-Stack\transaction-fraud-system\backend
.\mvnw.cmd test
.\mvnw.cmd clean verify

Set-Location ..\frontend
npm.cmd run build

Set-Location ..
node .\scripts\verify-api.mjs
~~~

Start the backend with `-Dev` and the frontend before running the API verifier. It creates two new development customers and records on every run, uses the configured admin, never deletes data, and privately saves generated logins. Timestamped credential files preserve previous runs. It assumes the default fraud thresholds and makes the required three prior transactions within the default five-minute window. It prints only check results, not credentials or tokens.

The nine backend tests cover existing fraud rules plus ordered locking, precise transfer balances, critical blocking and analysis persistence, currency rejection, ownership/funds/inactive-account checks, recipient detail access, and stored account type. Mockito uses test-only subclass mocks to avoid its default Java 25 instrumentation incompatibility without upgrading dependencies.

### Verified on 2026-10-10

- Native MySQL connectivity, Flyway migration validation, and Hibernate schema validation passed.
- Fresh Java 25 `clean verify`: **9 tests, 0 failures/errors**, freshly compiled and packaged.
- Frontend TypeScript check and production build passed.
- **15 live API verification groups passed**: registration/role enforcement, duplicate/invalid inputs, login/profile, invalid/expired JWTs, accounts, deposits/withdrawals/transfers and exact balances, incoming visibility, unrelated access denial, rejection cases with unchanged balances, CRITICAL blocking and rules, filters/pagination, customer admin denial, successful admin monitoring, concurrent reciprocal transfers, and authenticated Vite proxy.
- The reproducible critical transfer scored **90/100**, was stored as BLOCKED, and left both balances unchanged.
- Post-restart login, proxy access, frontend HTTP delivery, and OpenAPI responses passed.
- No browser automation tool was available. Actual browser interaction and visual layout remain manual checks, not claimed automated successes.

## Short demo walkthrough and manual UI checklist

1. Open http://127.0.0.1:5173 and sign in as Alice using the private demo credential file. Refresh: the session should persist. Check the overview balances and accounts.
2. Open Move money. Deposit a small amount, withdraw, and transfer to Bob's USD account ID from `.local/verification-latest.json`. Confirm the refreshed balances and completed transaction details. Try the EUR recipient and verify the rejection message.
3. In history, filter for BLOCKED / CRITICAL and open the recorded blocked transaction. Review its score, unchanged-balance message, decision, and triggered rules. Clear filters and exercise pagination.
4. Sign out and sign in as Bob (or use a private browser window). Confirm the incoming transfer and its fraud details appear. Customer navigation should not show admin options.
5. Sign in as the development admin. Open monitoring overview, all transactions, and fraud review; filter and open a transaction. Check metric/chart values against the table.
6. Register a new customer through the UI, open a savings account, and confirm it starts at zero. Check validation errors and the no-activity empty state.
7. Check narrow/mobile layout, keyboard navigation, logout protection, and expiry after the configured token lifetime. API expiry denial is automated; the browser's expired-session presentation still needs this manual check.

For a fresh critical demo using default thresholds, use a new customer: deposit USD 30,000, withdraw USD 50, transfer USD 100.25 to a second USD account, then attempt USD 29,000 to that recipient within five minutes. High value (45) + rapid activity (25) + low remaining balance (20) yields 90 points and blocks the transfer. Use the existing blocked demo record when merely presenting the app.
