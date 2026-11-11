# Implementation Plan: Finance Vault — Complete Engineering Upgrade, Security Hardening & Premium UI Redesign

## Phase 1 — Repository Audit & Findings (Grounded in Existing Code)

### 1.1 Security Findings
1. **Weak PIN Hashing (`PinSecurityUtil.kt`)**:
   - Currently uses a single round of salted `SHA-256` (`"$saltHex:$pin:finance_vault_v1"`) with no iteration count, no algorithm metadata, and zero brute-force throttling or lockout protection.
2. **Biometric Authentication Edge Cases (`BiometricAuthHelper` & `MainActivity.kt`)**:
   - Uses raw platform `android.hardware.biometrics.BiometricPrompt` without tracking background/foreground lifecycle auto-lock (`ON_STOP` / `ON_RESUME` elapsed timeout vs `autoLockMinutes`).
3. **Unrestricted Auto-Backup (`AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`)**:
   - `android:allowBackup="true"` is enabled while `backup_rules.xml` and `data_extraction_rules.xml` have all `<exclude>` tags commented out, allowing `finance_vault.db` (containing financial records and PIN hashes) to be uploaded unencrypted to cloud/device-transfer backups.
4. **Sensitive Field Protection**:
   - Gift card redemption codes (`redemptionCodeProtected`) and masked PAN (`panMasked`) should be protected with Android Keystore AES-GCM encryption at rest (`VaultCryptoHelper`) while keeping Room schema fields backward-compatible.

### 1.2 Database & Migration Findings
1. **Destructive Migration Risk (`FinanceVaultDatabase.kt` line 162)**:
   - Calls `.fallbackToDestructiveMigration()`, which could silently wipe user financial data if a future schema change occurs without a matching migration.
2. **Schema Evolution (`version = 6 -> 7`)**:
   - We will add explicit `MIGRATION_6_7` to add security and AI privacy columns to `app_settings` (`pinAlgorithm`, `pinIterations`, `failedPinAttempts`, `lockoutUntilEpochMillis`, `isAiFinancialContextConsentGranted`) and an idempotency/deduplication fingerprint index on `transactions`, and remove unconditional `.fallbackToDestructiveMigration()`.

### 1.3 Accounting Engine & Financial Accuracy Findings
1. **Investment Sale Net Proceeds Edge Case (`AccountingEngine.kt` line 211)**:
   - `val netProceedsToBank = (amountPaise - chargesPaise).coerceAtLeast(1L)` — if `chargesPaise > 0L` and `amountPaise <= chargesPaise`, `coerceAtLeast(1L)` causes `totalDebits != totalCredits`. We will validate `require(amountPaise > chargesPaise)` and ensure exact double-entry balance for all capital gain/loss + brokerage combinations.
2. **Transaction Editing & Reversal Audit Trail (`FinanceVaultRepository.kt`)**:
   - Currently, transactions can be reversed, but editing a transaction in-place or preventing duplicate rapid submissions (idempotency check within a short window) needs atomic `@Transaction` protection and full journal line synchronization.

### 1.4 AI & Voice Assistant Findings
1. **Automatic Posting Without Confirmation (`FinanceVaultViewModel.kt`)**:
   - Voice and AI text commands currently post transactions directly to Room without showing a transaction preview card for explicit user confirmation.
2. **Financial Context Privacy (`GeminiVaultAssistant.kt`)**:
   - `buildVaultContextPrompt` sends account balances and recent transactions to Gemini without an explicit user consent toggle or minimization filter.

---

## Phase 2 — Critical Security, Database & Accounting Fixes

### 2.1 PBKDF2-HMAC-SHA256 PIN Hashing, Auto-Migration & Exponential Lockout (`PinSecurityUtil.kt`, `Entities.kt`, `FinanceVaultRepository.kt`)
- Upgrade `PinSecurityUtil` to use **`PBKDF2WithHmacSHA256`** (120,000 iterations, 256-bit derived key, 16-byte `SecureRandom` salt) with versioned hash format (`pbkdf2_sha256$120000$<saltHex>$<hashHex>`).
- **Seamless Legacy Migration**: In `verifyPin`, if the stored hash is a legacy 64-char SHA-256 hex string and matches the entered PIN, automatically re-hash with `PBKDF2-HMAC-SHA256` and persist the upgraded hash in `app_settings`.
- **Exponential Retry Throttling**: Track `failedPinAttempts` and `lockoutUntilEpochMillis` in `AppSettingsEntity`:
  - Attempts 1–3: immediate retry allowed.
  - Attempt 4: 15-second cooldown.
  - Attempt 5: 30-second cooldown.
  - Attempt 6+: 60-second doubling exponential cooldown (`60s`, `120s`, `300s`).
  - Reset counters to `0` upon successful PIN or biometric verification.

### 2.2 Biometric Lifecycle Hardening & Android Keystore Encryption (`PinSecurityUtil.kt`, `MainActivity.kt`)
- Add lifecycle-aware auto-lock in `MainActivity` (`LifecycleEventObserver`) that records background timestamp on `ON_STOP` and re-locks the session on `ON_START` if elapsed time exceeds `settings.autoLockMinutes`.
- Add `VaultKeystoreCipher` (using Android Keystore `AES/GCM/NoPadding`) to encrypt/decrypt sensitive fields such as gift card redemption codes at rest while gracefully reading existing plaintext records.

### 2.3 Secure Backup & Data Extraction Rules (`backup_rules.xml`, `data_extraction_rules.xml`)
- Configure `backup_rules.xml` and `data_extraction_rules.xml` to explicitly `<exclude domain="database" path="finance_vault.db"/>`, `<exclude domain="database" path="finance_vault.db-wal"/>`, `<exclude domain="database" path="finance_vault.db-shm"/>`, and `<exclude domain="sharedpref" path="."/>` from unencrypted automatic cloud backup and device transfer, while preserving user-initiated Export/Restore in Settings.

### 2.4 Database Migration `MIGRATION_6_7` & Integrity Verification (`FinanceVaultDatabase.kt`)
- Implement explicit `MIGRATION_6_7` adding `pinAlgorithm`, `pinIterations`, `failedPinAttempts`, `lockoutUntilEpochMillis`, and `isAiFinancialContextConsentGranted` to `app_settings`.
- Remove `.fallbackToDestructiveMigration()` so existing user records are never wiped on upgrade, and add a `verifyDatabaseIntegrity()` check that validates `Total Debits == Total Credits` across posted journal lines.

### 2.5 Accounting Engine & Transaction Editing (`AccountingEngine.kt`, `FinanceVaultRepository.kt`)
- Fix `INVESTMENT_SALE` brokerage/gain/loss validation so `totalDebitPaise == totalCreditPaise` holds strictly without `coerceAtLeast(1L)` distortion.
- Add `updateTransaction(...)` in `FinanceVaultRepository` (atomic `@Transaction` that reverses/updates the original journal entry & lines and records an audit log entry) so users can edit existing transactions cleanly.
- Add duplicate transaction guard to prevent accidental double-posting of identical transactions within 3 seconds.

---

## Phase 3 — AI Privacy Controls & Explicit Transaction Confirmation

### 3.1 AI Financial Privacy Consent & Minimization (`GeminiVaultAssistant.kt`, `MoreModulesScreens.kt`)
- Add an **AI Financial Context Consent** toggle (`isAiFinancialContextConsentGranted`) in Settings and inside the Voice/AI Assistant dialog.
- Inform the user clearly what aggregate categories/account names are shared when enabled, strip account numbers/PAN/notes from AI prompts, and when disabled, use strictly on-device deterministic NLP parsing or zero-PII general queries.

### 3.2 Mandatory AI Transaction Preview & Confirmation Workflow (`FinanceVaultViewModel.kt`, `VoiceAndGeminiComponents.kt`)
- Introduce `PendingAiTransactionConfirmation` state in `FinanceVaultViewModel`.
- Whenever a voice or text command produces a complete transaction (`Amount`, `Category`, `Account`, `Date`, `Type`, `Location`), instead of auto-saving immediately:
  1. Display a **Confirm AI Transaction Preview Card** showing Transaction Type, Amount (`₹`), Source/Destination Account, Category, Date, Description, and GPS Location.
  2. Allow the user to review, edit any field inline, or tap/say **"Confirm & Post"** or **"Cancel"**.
  3. Commit to `FinanceVaultRepository.recordTransaction` only after explicit confirmation, then display a clear success banner.

---

## Phase 4 & Phase 5 — Modular Architecture & Revolut / Linear Minimal Slate UI/UX Redesign

### 4.1 Design System (`Color.kt`, `Theme.kt`, `Type.kt`, `VaultCommonComponents.kt`)
- Redesign the color and surface system around a **Revolut & Linear Minimal Slate** aesthetic:
  - **Dark Mode**: Deep carbon-slate background (`#090D16`), refined elevated slate cards (`#111827` / `#182234`) with hairline `1.dp` borders (`#1F2D46`), crisp typography (`Plus Jakarta Sans` headings/body + `JetBrains Mono` tabular figures for INR currency), and restrained emerald (`#10B981`) / indigo-cyan (`#38BDF8`) semantic accents.
  - **Light Mode**: Pure alabaster-slate background (`#F8FAFC`), crisp white cards (`#FFFFFF`) with subtle slate borders (`#E2E8F0`), deep ink text (`#0F172A`), and high-contrast semantic indicators.

### 4.2 5-Tab Bottom Navigation (`MainTab` in `FinanceVaultViewModel.kt` & `MainActivity.kt`)
- Organize primary navigation into the 5 clean tabs selected:
  1. **Home**: Executive Net Worth hero card with privacy toggle, Assets vs. Liabilities pill strip, Monthly Income vs. Expense cash flow summary, Upcoming Subscriptions/Bills alert strip, Budget Progress overview, Quick Actions (`+ Add`, `Transfer`, `Statements`, `Voice AI`), and Recent Transactions.
  2. **Transactions**: Date-grouped transaction timeline (`Today`, `Yesterday`, formatted dates) with instant search, type/category/account filter chips, clear `+` (Income) / `-` (Expense) tabular amounts, transaction detail & edit sheet, and confirmation dialog before reversal/deletion.
  3. **Accounts**: Unified Accounts & Portfolio view with segmented tabs/cards for Bank Accounts, Cash & UPI Wallets, Credit Cards (with credit utilization bar), and Investments (Mutual Funds, Equity, FD/RD, PPF, Gold, NPS) with per-account ledger drill-down.
  4. **Insights**: Direct bottom-bar access to the rich Analytics & Financial Performance dashboard (Spending Velocity, Category Donut & Percentages, 6-Month Cash Flow Trend, Weekday Velocity, Account Outflow Comparison, Solvency Gauges, and Link to Full Financial Statements).
  5. **More**: Clean hub for Indian Financial Statements & Ledgers (Balance Sheet, P&L, Cash Flow, Trial Balance, Journal Book), Budgets & Goals, Loans & EMIs, Subscriptions, Gift Cards, Tax Records, Security & Privacy Settings, and Backup/Export.

---

## Phase 6 — Automated Unit & Robolectric Testing (`app/src/test/`)
- Expand unit and Robolectric tests (`ExampleUnitTest.kt` and `ExampleRobolectricTest.kt`) to verify:
  - `AccountingEngine`: Double-entry invariants across all transaction types, transfers (zero net worth distortion), credit card purchases/repayments, loan disbursements/EMI splits, investment purchases/sales with brokerage & capital gains/losses, refunds, and reversals.
  - `FinancialReportGenerator`: Independent reconciliation of Trial Balance (`totalDebit == totalCredit`), Balance Sheet (`Assets == Liabilities + Net Worth`), and Cash Flow Statement (`Opening Cash + Net Change == Closing Cash`).
  - `PinSecurityUtil`: PBKDF2-HMAC-SHA256 hashing, salt uniqueness, legacy SHA-256 verification & auto-migration detection, and exponential lockout calculation.
  - Demo mode isolation (`isDemo = true` vs `isDemo = false`) and AI transaction confirmation state gating.
- Run `compile_applet` and `gradle :app:testDebugUnitTest` to verify all builds and tests pass.
