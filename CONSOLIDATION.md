# LedgerGuard consolidation — 2026-10-26

The consolidated application lives in **`C:\Projects\Full-Stack\transaction-fraud-system`**. Open this folder in the IDE and use its launchers. The Downloads directory is an older independent source snapshot, not a Git checkout; its payment-request work has been integrated here. It was not overwritten.

## Recovery and preservation

- Inspected all fetched branches and tags: the remote exposes `main`, at `6da7299` when integration began. Scheduled Payments came from `f394cba`, merged by `6da7299`. No recurring implementation existed in the available history.
- The local `main` was still at `3ebacf0`, with uncommitted Admin Users and Windows launcher work. That state, including untracked source files, was committed on **`backup/pre-consolidation-20261026-222537`** (`9f59983`) before integration.
- Integrated on **`integration/consolidated-20261026-222537`**, retaining the remote history and the backup commit. Conflicting README and router sections were combined rather than choosing one side.
- Preserved the Downloads source/configuration archive, original canonical credential files, a native `mysqldump --single-transaction` backup, and pre-migration table counts privately under **`.local/consolidation-backup-20261026-222537/`**. Generated dependencies/build outputs were excluded from the source archive; neither original source directory nor its ignored artifacts were deleted.

## Feature comparison

| Functionality | Source | Integration / verification |
| --- | --- | --- |
| Transactions, account ownership/locking, double-entry movements, fraud explanations, idempotency | Existing Git history | Retained existing execution path; backend and live API suites |
| Password confirmation, cancellation, expiry, critical admin approval/rejection | Existing Git history | Retained; lifecycle tests, live confirmation/workspace suites and browser admin approval |
| Transaction history, audit trail, notifications | Existing Git history + local request events | Preserved private transaction notifications and extended typed resource links; live privacy/deduplication checks |
| Beneficiaries, statements/CSV/PDF, sessions/security, currency-separated insights | Existing Git history | Retained; existing workspace/API verifiers |
| One-time scheduled payments | Recovered `f394cba`, migration V4 | Restored controller/service/worker/page/tests; real MySQL concurrency and browser execution |
| Payment requests | Downloads development, migration V5 | Preserved entity/repository/API/UI, expiry, attempt history and synchronous transaction outcome handling; real MySQL and two-browser payment |
| Admin user directory | Previously uncommitted local work, backup `9f59983` | Preserved backend allowlisted fields, search/pagination, route and verifier; browser search |
| One-click native Windows start/stop | Previously uncommitted local work | Preserved process ownership/Job Objects; fixed hidden Vite worker exiting when stdin closes; startup/reuse/shutdown checked |
| MySQL password repair | Downloads development | Preserved explicit `-ResetAppPassword`; ordinary setup still preserves existing passwords |
| Recurring payment plans | Newly added to satisfy requested complete scheduling scope, migration V6 | Daily/weekly/monthly/yearly finite series; same scheduled-payment and transaction execution; timezone/date/concurrency/history checks |

Bills/reminders and shared-expense Groups were not previously implemented in either copy or the fetched history. No working implementation of those features was removed; they are outside this recovery's implemented feature set.

Subsequent UI update: Beneficiaries navigation, page and saved-recipient selectors have been removed throughout the frontend. Transfers and scheduled/recurring payment forms use direct recipient account IDs. Existing backend APIs and saved records remain available for compatibility; frontend pages no longer request them.

## Recurring payments

Open **Recurring payments** (`/recurring-payments`). Choose an owned source, compatible destination, amount, first time, recurrence, IANA time zone, and **1–120 occurrences**, then review and authorize. No funds are reserved. Each generated occurrence appears under Scheduled Payments and has its own transaction outcome and confirmation/review requirements.

- Occurrences are persisted through a rolling **45-day horizon**, at most 100 per plan-generation transaction. Unique `(recurring_plan_id, occurrence_index)` and a locked plan row prevent duplicate generation across workers/restarts.
- The original local date/time is the anchor. January 31 becomes February's last day and then March 31. February 29 yearly plans return to February 29 on later leap years. DST gaps move forward to a valid local time; later dates still derive from the original anchor. All authorized dates must precede 2038, matching the application's supported MySQL timestamp range.
- Due occurrences missed during downtime execute after restart. A failed occurrence is not retried financially; later authorized dates remain independent. Infrastructure errors roll back for worker retry. Pending/failed/rejected/cancelled transactions never count as completed payments.
- Cancelling a plan stops generation and cancels unprocessed schedules. Already processed transactions retain their outcomes and their own confirmation/cancellation controls; completed transfers are never reversed by cancelling a plan.
- Workers run automatically with the backend: recurring materialization every 30 seconds, scheduled execution every 5 seconds. Properties: `app.recurring-payments.poll-ms`, `app.recurring-payments.initial-delay-ms`, and the existing scheduled-worker properties. The recurrence calculation and generation use an injectable `Clock`.

New APIs: POST/GET `/api/recurring-payments`, GET `/{id}`, GET `/{id}/occurrences`, POST `/{id}/cancel`. Creation requires `Idempotency-Key`; retrying identical content returns the same plan, changed content conflicts. Lists are owner-only, zero-based `page` and `size` 1–100. Existing scheduled APIs remain compatible; schedule responses add nullable `recurringPlanId` and `occurrenceIndex`.

## Existing database and configuration

The live database already contained V1–V3 and V5, while V4 was missing. A one-time startup with `SPRING_FLYWAY_OUT_OF_ORDER=true` applied the **unchanged upstream V4** additively, followed by V6. All migration records succeeded and subsequent startup validates normally without that flag. Existing migrations were not edited and no schema/database was dropped. Pre-existing table row counts did not decrease. Original data is also preserved in the private SQL backup.

Both copies' original `.env` files and the canonical `.env.development` remain unchanged. The canonical application's old database password was rejected; the Downloads configuration connected successfully. A private copy is used at **`.local/runtime.env`**. The saved canonical admin login was also rejected, so the existing development bootstrap provisioned a separate development administrator from **`.local/runtime-dev.env`**, preserving every existing account/password. Open this file locally to obtain the development admin login; do not publish it.

`start-backend.ps1` uses `.local/runtime.env` when present, otherwise root `.env`; explicit `-EnvFile` selects another file. With `-Dev`, `.local/runtime-dev.env` takes precedence over `.env.development`. Existing process environment variables retain their original precedence. These private runtime files are ignored and are not pushed. Fresh clones use the normal README initialization scripts; no runtime file is required.

## Run and verify

Double-click **`start-app.cmd`** in the canonical folder, then open http://127.0.0.1:5173. Use **`stop-app.cmd`** to stop only launcher-owned processes. Native MySQL remains running. The launcher uses normal authorization lifetimes after verification (five-minute confirmation, 24-hour review).

```powershell
.\backend\mvnw.cmd clean verify
npm.cmd ci --prefix frontend
npm.cmd run build --prefix frontend
node .\scripts\verify-api.mjs
node .\scripts\verify-workspace.mjs
node .\scripts\verify-confirmations.mjs
node .\scripts\verify-outcomes.mjs
node .\scripts\verify-users.mjs

# Optional browser tooling stays outside application dependencies:
npm.cmd install --prefix .local/browser-check playwright
node .\scripts\verify-consolidated-browser.mjs
```

The MySQL suites are opt-in: provision dedicated `ledgerguard_schedule_test` and `ledgerguard_payment_test` databases, and set `SCHEDULE_TEST_DB_URL/USER/PASSWORD` and `PAYMENT_TEST_DB_URL/USER/PASSWORD` respectively before Maven. A separate native MySQL 8.4 instance on port 13307 was used for these tests. The database names are enforced by the tests; application data is never cleared. Stop the backend before a Windows `clean` build if it is running from the built JAR.

The expiry verifiers require temporary `CONFIRMATION_TTL_SECONDS=30` and `REVIEW_TTL_SECONDS=30` in the startup process. Stop/restart without these overrides afterward. Live checks create isolated simulation users/records and save reports/credentials under ignored `.local/`. Browser verification defaults to installed Edge; `LEDGER_BROWSER_EXE` may select another Chromium executable. Browser screenshots remain private under `.local/`.

To verify real restart recovery: run `node scripts/verify-scheduler-restart.mjs prepare`, stop/start the application immediately, then run `node scripts/verify-scheduler-restart.mjs check`. It verifies stable occurrence IDs, exactly-once completed transactions and exact balances, then cancels its future fixture occurrence.

Manual demonstration: create two customers and matching-currency accounts; create/pay a request between them; schedule a one-time transfer; authorize a two-occurrence recurring plan and inspect its history; follow any confirmation/review transaction links; cancel the plan and verify completed history remains. As a development administrator, search Users and review a critical payment. This is a local banking simulation, with no external payment provider.

## Executed verification results

| Check | Result |
| --- | --- |
| Maven Wrapper `clean verify` | **111 tests, 0 failures, 0 errors, 0 skips**, executable JAR built |
| Real native-MySQL suites | **29 tests** included above: payment requests 14, scheduled payments 8, recurring plans 7; migrations and Hibernate validation passed |
| Frontend `npm ci` / production build | Passed, 0 dependency audit vulnerabilities; TypeScript and Vite production assets built |
| `verify-api.mjs` | All **15** verification groups passed, including the authenticated Vite proxy |
| `verify-workspace.mjs` | All **12** groups passed: idempotency/reviews/audit/notifications/beneficiaries/statements/security/insights |
| `verify-confirmations.mjs` | All **9** groups passed, including real expiry and concurrency |
| `verify-outcomes.mjs` | All **4** output checks passed: MEDIUM/HIGH completion, CRITICAL review, retained history/rendering |
| `verify-users.mjs` | All **3** output groups passed: authorization, field allowlist/pagination, search/validation |
| `verify-consolidated-browser.mjs` | All **8** real Edge browser groups passed; no page runtime exceptions; UI-created one-time/recurring transfers executed, request paid across two sessions, admin approved a critical transfer |
| Mobile layout | Screenshot inspected; account-header overlap fixed and browser geometry assertion passed; production build rerun successfully |
| `verify-scheduler-restart.mjs` | Actual stop/start recovered both due payment types exactly once, stable occurrence IDs, exact balances; future fixture occurrence cancelled |
| Existing database | V4 reconciled and V6 added successfully after the already-applied V5; all old table counts retained or increased; next startup passed normal Flyway/Hibernate validation |
| Windows launcher | Start, reuse without duplicate server creation, owned-process stop and restart passed; normal confirmation/review lifetimes restored |
| Integrity review | No deleted files relative to upstream; V1–V4 unchanged; original configuration files byte-for-byte unchanged; no runtime secrets staged |

Live verifiers intentionally added isolated simulation users, accounts and audit/ledger records to the existing development database; they did not reset it or delete existing records. This is representative automated workflow coverage, not exhaustive security, performance or every-browser certification. The standalone password-reset option was preserved and syntax-checked, but was not used to change live account credentials during consolidation.
