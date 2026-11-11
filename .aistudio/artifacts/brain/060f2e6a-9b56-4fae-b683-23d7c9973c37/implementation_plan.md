# Balance Sheet Net Worth, Investment Growth & Valuation Engine, Portfolio Back Navigation & 10+ Per-Module Demo Dataset

This upgrade ensures **Personal Net Worth** is strictly derived from the **Balance Sheet** (`Total Assets − Total Liabilities`) and includes the full current valuation of all investments, shares, deposits, gift cards, cash, and bank balances. It also adds a top-left **Back button** to **Investments & Portfolio**, introduces a comprehensive **Investment Valuation & Growing Rate Calculator** that captures every detail of an investment (buy/current share price, units, purchase date, growth/interest rate, compounding, charges, dividends) to compute current value and growth rates (CAGR, Absolute Return, XIRR, Projected Value), and upgrades **Demonstration Mode** (`isDemo = true` only) with **10+ records and transactions in every single dashboard and module window**.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were confirmed by the user during Phase 1 clarification:

- **Confirmed Decision 1 — Balance Sheet Net Worth & Investment Valuation Engine**: Automatically calculate the current value and growing rate of shares and investments from unit/share prices, annual growth/interest rates, or compounding parameters, and synchronize investment valuations into the Balance Sheet so Personal Net Worth always includes cash, bank balances, gift cards, and total investment wealth.
- **Confirmed Decision 2 — 10+ Demo Records Across Every Dashboard & Module**: In Demonstration Mode (`isDemo = true` only), seed at least **10 items and transactions** in every module window (Home Dashboard, Transactions, Accounts, Investments & Portfolio, Subscriptions, Gift Cards, Budgets, Savings Goals, Loans & Credit Cards, and Indian Tax Records) and automatically upgrade existing demo data if incomplete.
- **Confirmed Decision 3 — Top-Left Back Button in Investments & Portfolio**: Add the top-left Back navigation button (`Icons.AutoMirrored.Filled.ArrowBack`) and system back handler to the **Investments & Portfolio** screen header.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. **Balance Sheet-Driven Personal Net Worth**: Synchronizes the current valuation of every investment holding (whether entered directly, computed from `Shares × Current Share Price`, or compounded from `Annual Growth Rate %`) into the double-entry Balance Sheet (`ASSET_NON_CURRENT_INVESTMENT` and `EQUITY_CONTRIBUTION` valuation reserve). Personal Net Worth on the Home Dashboard and Financial Statements is always `bs.totalNetWorthPaise = bs.totalAssetsPaise - bs.totalLiabilitiesPaise`.
  2. **Comprehensive Investment Questionnaire & Growth Calculator**: Expands the Add/Edit Investment workflow to ask for all investment details (Category, Name, Purchase Date / Holding Period, Shares/Units/Grams, Buy Price per Share/Unit, Current Market Price/NAV per Share/Unit, Annual Growth/Interest Rate % p.a., Compounding Frequency, Tenure, Brokerage Charges, and Dividends/Interest Earned) and automatically calculates **Current Value (₹)**, **Annualized Growing Rate (CAGR %)**, **Absolute Return %**, **Monthly/Daily Growth Rate**, and **1-Year / Maturity Projected Value (₹)**.
  3. **Investments & Portfolio Back Button**: Adds a standard top-left Back arrow button in `InvestmentsScreen` so users can return to the previous screen with one tap.
  4. **10+ Demo Records in Every Dashboard & Window**: Populates Demo Mode (`isDemo = true`) with 10 Accounts, 10 Investments, 10 Subscriptions, 10 Gift Cards, 10 Budgets, 10 Savings Goals, 10 Loans, 10 Tax Records, and 25+ current-month Transactions.
- **Target Audience / Persona**: Users tracking complete personal wealth across cash, banks, shares, mutual funds, FDs/RDs, gold, gift cards, loans, budgets, goals, subscriptions, and taxes.
- **Key Value**: Gives users an accurate, single-source-of-truth Personal Net Worth from the Balance Sheet, automatic share/investment growth and current value calculations, consistent navigation, and a richly populated Demo Mode across all windows.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Personal Net Worth on Home Dashboard & Balance Sheet**:
     - The Hero Net Worth card displays `bs.totalNetWorthPaise` from the Balance Sheet and shows a breakdown of **Total Assets** (Cash & Bank + Investment Wealth + Gift Cards & Other Assets) minus **Total Liabilities** (Loans + Credit Card Dues).
  2. **Investments & Portfolio Navigation & Growth Calculator**:
     - The header of `InvestmentsScreen` includes a top-left Back `IconButton` alongside `"Investment Portfolio"` and `+ Add Holding`.
     - Opening **Add Holding** or **Update Value & Growth** presents structured inputs for Share/Unit Quantity, Buy Price per Share, Current Share Price (or Expected/Historical Annual Growth Rate % & Compounding Frequency), Purchase Date (Days/Months ago or `YYYY-MM-DD`), Brokerage/Charges, and Dividends/Interest.
     - A live **Valuation & Growth Preview Card** inside the dialog immediately displays the computed **Invested Capital**, **Calculated Current Value**, **Unrealized Gain/Loss**, **Growing Rate (CAGR % p.a.)**, **Absolute Return %**, and **Projected Future Value** before saving.
  3. **Exploring Demo Mode Across All Windows**:
     - Enabling Demo Mode immediately populates every tab and More Hub destination with 10+ realistic `[DEMO - FICTIONAL]` items and transactions.
- **Visual Identity & Theme**:
  - Preserves the Obsidian dark financial aesthetic (`VaultObsidianBg` `#0B0F17`, `VaultCardSurface` `#111827`, `VaultEmerald` `#10B981`, `VaultCyan` `#06B6D4`, `VaultGold` `#F59E0B`, `VaultCoral` `#F43F5E`, `VaultPurple` `#A855F7`).
  - Uses `Space Grotesk` for headers, `JetBrains Mono` for tabular currency and percentage figures, and `Plus Jakarta Sans` for labels.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Double-Entry Valuation Synchronization for Balance Sheet Net Worth**
  - *Chosen Approach*: When an investment is created or its valuation/growth is updated, post a double-entry adjustment between the investment's `ASSET_NON_CURRENT_INVESTMENT` ledger and `EQUITY_CONTRIBUTION` (Valuation & Capital Reserve) for the difference between the investment's current value and its prior ledger balance.
  - *Why*: Keeps the Trial Balance (`Total Debit = Total Credit`) and Balance Sheet (`Total Assets = Total Liabilities + Personal Net Worth`) 100% mathematically reconciled while ensuring `bs.nonCurrentInvestmentsPaise`, `bs.totalAssetsPaise`, and `bs.totalNetWorthPaise` always include the full current market/accrued value of all investments. Because no cash/bank ledger is touched by unrealized valuation adjustments, the Cash Flow Statement remains strictly cash-accurate.
- **Decision 2: Multi-Mode Investment Current Value & Growth Calculation**
  - *Chosen Approach*: Support three seamless ways to compute an investment's Current Value and Growing Rate:
    1. **Share / Unit Price Mode**: `Current Value = Units × Current Price per Unit`, `Invested = Units × Buy Price per Unit`.
    2. **Growth / Interest Rate Mode**: If Current Value is not manually typed, compute `Current Value = Invested × (1 + r/n)^(n × years)` using the entered Annual Growth/Interest Rate (% p.a.), Compounding Frequency, and elapsed holding days (or tenure).
    3. **Direct Valuation Mode**: User enters Invested Capital and Current Value directly, and the engine back-calculates the implied **Annualized Growing Rate (CAGR % p.a.)** and **Absolute Return %**.
- **Decision 3: Versioned Demo Mode Auto-Upgrade**
  - *Chosen Approach*: Check that Demo Mode contains at least 10 records in each core module (`accounts`, `investments`, `subscriptions`, `giftCards`, `budgets`, `goals`, `loans`, `taxRecords`, and `transactions`). If any module has fewer than 10 demo items, atomically refresh the isolated `isDemo = true` dataset.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI Screens                           │
│                                                                             │
│  ┌──────────────────────────┐  ┌─────────────────────────────────────────┐  │
│  │     DashboardScreen      │  │            InvestmentsScreen            │  │
│  │ • Hero Personal Net Worth│  │ • Top-left Back Button + Header         │  │
│  │   from Balance Sheet     │  │ • Full Investment Questionnaire Dialog  │  │
│  │ • Wealth Breakdown Strip │  │   (Units, Buy/Current Price, Purchase   │  │
│  └────────────┬─────────────┘  │    Date, Growth %, Compounding, Fees)   │  │
│               │                │ • Holding Cards with CAGR %, Abs %,     │  │
│               │                │   Current Value & Projected Value       │  │
│               │                └────────────────────┬────────────────────┘  │
└───────────────┼─────────────────────────────────────┼───────────────────────┘
                │                                     │
                ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   FinancialCalculators & ReportGenerator                    │
│  • computeInvestmentMetrics():                                              │
│      - Derives Current Value from (Units × CurrentPrice) or Compound Growth │
│      - Computes Annualized Growth Rate (CAGR % / Implied Annual Rate %)     │
│      - Computes Absolute Return %, Total Gain, and 1-Yr / Maturity Value    │
│  • generateBalanceSheet():                                                  │
│      - Sums Cash, Bank, Gift Cards, and Current Value of All Investments    │
│      - Computes Personal Net Worth = Total Assets - Total Liabilities       │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           FinanceVaultRepository                            │
│  • Syncs investment valuation delta to Investment Asset Ledger & Equity     │
│  • ensureDemoDatasetUpToDate(): Seeds 10+ Demo Accounts, 10+ Investments,   │
│    10+ Subscriptions, 10+ Gift Cards, 10+ Budgets, 10+ Goals, 10+ Loans,    │
│    10+ Tax Records, and 25+ Current-Month Demo Transactions (isDemo = true) │
└─────────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - `FinancialCalculators.InvestmentGrowthAnalysis`:
    - `investedPaise: Long`, `effectiveCurrentValuePaise: Long`, `purchasePricePerUnitPaise: Long`, `currentPricePerUnitPaise: Long`
    - `unrealizedGainPaise: Long`, `totalNetGainPaise: Long` (including dividends & interest minus charges)
    - `absoluteReturnPercent: Double`, `annualizedGrowthRatePercent: Double` (CAGR / effective annual growth rate even for short holding periods via annualized compounding or explicit growth rate), `monthlyGrowthRatePercent: Double`
    - `projectedOneYearValuePaise: Long`, `projectedMaturityValuePaise: Long?`
  - **Demo Mode Dataset (`isDemo = true`)**:
    - **10 Accounts**: Savings, Salary, Current, Cash, Wallet, UPI, 2 Credit Cards, Sweep FD Bank, Travel Wallet.
    - **10 Investments**: Equity Shares, ETFs, Mutual Funds, SIP, FD, RD, Gold, PPF/G-Sec with positive unrealized gains synced to the Balance Sheet.
    - **10 Subscriptions**: Streaming, Broadband, Cloud, Productivity, Fitness, and News subscriptions.
    - **10 Gift Cards**: E-commerce, Dining, Entertainment, and Retail gift cards with active balances on the Balance Sheet.
    - **10 Budgets & 10 Goals**: Overall + 9 category budgets and 10 savings/emergency/purchase goals.
    - **10 Loans & 10 Tax Records**: Home, Vehicle, Education, Personal, and Consumer loans with EMI schedules, plus 10 Indian tax challan/TDS records.
