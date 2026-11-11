# Analytics Header Refinement & Expanded Current-Month Demo Dataset

This update removes the redundant top-right "Add" transaction button from the Analytics & Financial Performance header and upgrades Demonstration Mode (`isDemo = true` only) with a rich set of 12+ current-month income, expense, transfer, credit card, and investment transactions so every dashboard and analytics window is immediately populated when exploring Demo Mode.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product decisions were confirmed during interactive clarification and govern this implementation:

- **Confirmed Decision 1 — Demo Dataset Scope**: Populate Demo Mode (`isDemo = true`) with 12+ diverse general income and expense transactions (alongside opening balances, transfers, credit card activity, and investment purchase) so both the Home Dashboard and the Transactions/Analytics windows display at least 10+ active transactions. Real user mode (`isDemo = false`) remains 100% untouched.
- **Confirmed Decision 2 — Time Distribution**: Keep all seeded Demo Mode transactions within the current calendar month so current-month KPIs (Monthly Income, Monthly Expenses, Month Spend, Savings Rate, Category Breakdown, Day-of-Week Velocity, and Place Rankings) are all populated simultaneously.
- **Confirmed Decision 3 — Automatic Demo Upgrade**: If Demo Mode is enabled (either on launch or toggled on) and existing isolated demo data has fewer than 12 user-visible transactions from an older seed version, automatically refresh the isolated `isDemo = true` dataset to the new expanded dataset.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. Streamlines the **Analytics & Financial Performance** header by removing the top-right `+ Add` button, giving the screen title full horizontal breathing room while relying on the global bottom-right `Record` floating action button.
  2. Expands the isolated **Demonstration Mode** seed dataset to include 12+ realistic `[DEMO - FICTIONAL]` transactions within the current month across multiple income streams, expense categories, accounts, weekdays, and Indian locations.
- **Target Audience / Persona**: Users evaluating Finance Vault in Demonstration Mode who want to immediately see how the Home Dashboard, Transactions list, Analytics & Financial Performance charts, and Indian Financial Statements behave with a realistic volume of current-month activity.
- **Key Value**: Eliminates header crowding in the Analytics view and ensures Demo Mode showcases all charts, KPIs, and ledgers without requiring manual data entry.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Analytics & Financial Performance Header**: Navigating to *Insights* (bottom bar) or *More → Analytics & Performance* displays a clean header with the Back navigation button and the full title `"Analytics & Financial Performance"`, free of the redundant top-right `+ Add` button.
  2. **Entering or Resuming Demonstration Mode**: Tapping *"Try Demo"* on the empty Home Dashboard or toggling *Demonstration Mode* in Settings immediately populates the Home Dashboard's *Recent Activity* list (showing 12 recent transactions) and the *Transactions*, *Accounts*, *Insights*, and *Statements* windows with reconciled double-entry records.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Obsidian luxury dark financial interface (`VaultObsidianBg` `#0B0F17`, `VaultCardSurface` `#111827`, `VaultCardElevated` `#1F2937`).
  - *Color Palette & Mood*: Emerald (`#10B981`) for inflows and positive net worth, Coral (`#F43F5E`) for outflows and liabilities, Cyan (`#06B6D4`) for liquidity and ledger accents, Gold (`#F59E0B`) for Demo Mode badges and peak weekday highlights, and Purple (`#A855F7`) for category breakdowns.
  - *Typography & Hierarchy*: Clean single-line screen headers (`titleLarge` in Space Grotesk) with tabular financial figures (`JetBrains Mono`) and Plus Jakarta Sans body labels.
  - *Component Styling & Layout*: Removing the top-right button in `AnalyticsScreen` prevents title truncation on 393dp handheld screens while keeping the global floating `Record` button accessible at the bottom right.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Removing Header Action in Analytics in Favor of Global FAB**
  - *Chosen Approach*: Remove the top-right `Button` (`analytics_add_tx_button`) from `AnalyticsScreen` and update empty-state helper text accordingly.
  - *Why*: The app already provides a persistent `Record` Extended FAB in the bottom-right corner across main screens, and removing the secondary top-right button prevents header text clipping.
  - *Alternatives Considered*: Keeping an icon-only `+` button in the header was rejected because the user explicitly requested removing the top-right add transaction button in Analytics & Financial Performance.
- **Decision 2: Clamping Demo Transactions to the Current Month**
  - *Chosen Approach*: Compute demo transaction dates within the current month (`monthStart..todayEpochDay`) distributed across different days of the week.
  - *Why*: Dashboard cards (*Monthly Income*, *Monthly Expenses*, *Month Spend*, *Monthly Savings Rate*) filter strictly by `IndianFinancialYear.currentMonthRange()`. Placing all demo transactions inside the current month guarantees every dashboard card and analytics chart reflects the demo transactions even on the 1st–5th day of a month.
  - *Alternatives Considered*: Using fixed offsets like `today - 10` without clamping to `monthStart` can push transactions into the previous month early in the calendar month, leaving current-month dashboard metrics empty.
- **Decision 3: Automatic Isolated Demo Dataset Upgrade**
  - *Chosen Approach*: Check the count of non-opening-balance demo transactions during `ensureInitialized()` (when Demo Mode is active) and `setDemonstrationMode(true)`. If fewer than 12 active demo transactions exist, clear `isDemo = true` records and re-seed the expanded dataset atomically.
  - *Why*: Ensures existing sessions that previously seeded only 6 demo records automatically receive the 12+ transaction dataset without manual reset.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         UI Layer (Jetpack Compose)                        │
│                                                                           │
│  ┌──────────────────────────────────┐  ┌───────────────────────────────┐  │
│  │         DashboardScreen          │  │        AnalyticsScreen        │  │
│  │  • Hero Net Worth & Monthly KPIs │  │  • Clean Header (Back + Title │  │
│  │  • Recent Activity (12+ Demo Txs)│  │    only, no top-right + Add)  │  │
│  └─────────────────┬────────────────┘  │  • Velocity, Category Donut,  │  │
│                    │                   │    Weekday & Place Rankings   │  │
│                    │                   └───────────────┬───────────────┘  │
└────────────────────┼───────────────────────────────────┼──────────────────┘
                     │ StateFlow<FinanceVaultUiState>    │
                     ▼                                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                           FinanceVaultViewModel                           │
│  • Observes active mode (isDemo = true vs false)                          │
│  • Computes Monthly P&L, FY P&L, Balance Sheet, Trial Balance & Velocity  │
└────────────────────────────────────┬──────────────────────────────────────┘
                                     │
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                          FinanceVaultRepository                           │
│  • ensureInitialized() / setDemonstrationMode(true)                       │
│  • Verifies isolated demo dataset completeness (>= 12 demo transactions)  │
│  • Atomic Room transaction: seeds 3 Demo Accounts + 12 Current-Month      │
│    Income/Expense/Transfer/Card/Investment vouchers via AccountingEngine  │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - **Demo Accounts (`isDemo = true`)**:
    1. `[DEMO - FICTIONAL] Primary Savings Bank` (Opening Balance: ₹1,50,000)
    2. `[DEMO - FICTIONAL] Cash in Hand` (Opening Balance: ₹10,000)
    3. `[DEMO - FICTIONAL] Platinum Credit Card` (Credit Limit: ₹1,00,000)
  - **12 Current-Month Demo Transactions (`isDemo = true`)**:
    1. *Salary Income*: ₹85,000 credited to Primary Savings Bank
    2. *Business & Freelance Income*: ₹18,500 consulting payout to Primary Savings Bank
    3. *Interest Received*: ₹1,250 savings bank quarterly interest
    4. *Housing & Rent Expense*: ₹22,000 apartment rent & maintenance (Bengaluru)
    5. *Household & Groceries Expense*: ₹4,800 monthly supermarket groceries (Connaught Place, New Delhi)
    6. *Utilities & Bills Expense (Credit Card)*: ₹2,450 fiber broadband & electricity bill (Bandra West, Mumbai)
    7. *Transportation & Fuel Expense*: ₹3,200 petrol & metro recharge (Hitech City, Hyderabad)
    8. *Food & Dining Expense (Credit Card)*: ₹1,850 family dinner & cafe (Koramangala, Bengaluru)
    9. *Healthcare & Medical Expense*: ₹1,400 pharmacy & health checkup (Adyar, Chennai)
    10. *Education Expense*: ₹3,500 professional certification course (Pune)
    11. *Account Transfer (ATM Withdrawal)*: ₹5,000 from Primary Savings Bank to Cash in Hand (Indiranagar, Bengaluru)
    12. *Credit Card Bill Repayment*: ₹2,000 partial bill payment from Primary Savings Bank to Platinum Credit Card
    13. *Investment Purchase*: ₹10,000 Nifty 50 Index Fund SIP purchase from Primary Savings Bank
- **Interactive Component & State Mapping**:
  - `AnalyticsScreen`: Header `Row` retains the Back `IconButton` and `"Analytics & Financial Performance"` title; the top-right `Button` (`analytics_add_tx_button`) is removed.
  - `DashboardScreen`: Displays up to 12 recent transactions in the *Recent Activity* list and reflects all current-month inflows and outflows in the top KPI cards.
  - `FinanceVaultRepository`: `seedIsolatedFictionalDemoData()` clamps all transaction dates inside `[monthStart, today]` so every transaction falls within the active month and financial year, keeping the Trial Balance (`Total Debit = Total Credit`) and Balance Sheet (`Assets = Liabilities + Net Worth`) 100% reconciled.
