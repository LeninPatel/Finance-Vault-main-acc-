package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AccountType
import com.example.data.local.FinanceVaultDatabase
import com.example.data.local.InvestmentCategory
import com.example.data.local.TransactionType
import com.example.data.repository.FinanceVaultRepository
import com.example.domain.reports.FinancialReportGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: FinanceVaultDatabase
    private lateinit var repository: FinanceVaultRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FinanceVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FinanceVaultRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `app name and creator credit match specification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Finance Vault", context.getString(R.string.app_name))
        assertEquals("Made by Lenin and Shaswat", context.getString(R.string.creator_credit))
    }

    @Test
    fun `repository starts empty for real user mode and commits double-entry transactions atomically`() = runBlocking {
        repository.ensureInitialized()
        // Strict user-entered policy: zero fake accounts or transactions on fresh launch
        val initialAccounts = repository.observeAccounts(isDemo = false).first()
        val initialTransactions = repository.observeTransactions(isDemo = false).first()
        assertTrue(initialAccounts.isEmpty())
        assertTrue(initialTransactions.isEmpty())

        // User adds Bank account with ₹50,000 opening balance
        val bankId = repository.createAccount(
            name = "User Savings Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 50_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 100L,
            isDemo = false
        )

        // User records ₹20,000 salary
        repository.recordTransaction(
            transactionType = TransactionType.INCOME,
            dateEpochDay = 101L,
            amountPaise = 20_000_00L,
            destinationAccountId = bankId,
            category = "Salary Income",
            description = "Monthly Salary",
            isDemo = false
        )

        // User purchases ₹3,000 investment from bank
        repository.addInvestment(
            name = "Index Fund",
            category = InvestmentCategory.MUTUAL_FUND,
            purchaseDateEpochDay = 102L,
            quantity = 1.0,
            units = 10.0,
            purchasePricePerUnitPaise = 300_00L,
            investedAmountPaise = 3_000_00L,
            paymentAccountId = bankId,
            isDemo = false
        )

        val ledgers = repository.observeLedgerAccounts(isDemo = false).first()
        val lines = repository.observeJournalLines(isDemo = false).first()

        val tb = FinancialReportGenerator.generateTrialBalance(ledgers, lines)
        val bs = FinancialReportGenerator.generateBalanceSheet(ledgers, lines)

        assertTrue(tb.isAgreed)
        assertTrue(bs.isReconciled)
        assertEquals(67_000_00L, bs.currentBankBalancesPaise)
        assertEquals(3_000_00L, bs.nonCurrentInvestmentsPaise)
        assertEquals(70_000_00L, bs.totalAssetsPaise)
        assertEquals(70_000_00L, bs.totalNetWorthPaise)
    }

    @Test
    fun `indian numbering system comma formatting formats first 3 digits then every 2 digits`() {
        assertEquals("999", com.example.domain.money.InrMoney.formatIndianInput("999"))
        assertEquals("1,000", com.example.domain.money.InrMoney.formatIndianInput("1000"))
        assertEquals("12,345", com.example.domain.money.InrMoney.formatIndianInput("12345"))
        assertEquals("1,00,000", com.example.domain.money.InrMoney.formatIndianInput("100000"))
        assertEquals("12,34,567", com.example.domain.money.InrMoney.formatIndianInput("1234567"))
        assertEquals("1,23,45,678.50", com.example.domain.money.InrMoney.formatIndianInput("12345678.50"))
    }

    @Test
    fun `document exporter creates valid PDF Excel and CSV files`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository.ensureInitialized()
        val ledgers = repository.observeLedgerAccounts(isDemo = false).first()
        val lines = repository.observeJournalLines(isDemo = false).first()
        val entries = repository.observeJournalEntries(isDemo = false).first()

        val tb = FinancialReportGenerator.generateTrialBalance(ledgers, lines)
        val bs = FinancialReportGenerator.generateBalanceSheet(ledgers, lines)
        val pnl = FinancialReportGenerator.generateProfitAndLoss(ledgers, lines)
        val jb = FinancialReportGenerator.generateJournalBook(entries, lines)

        val pdfResult = com.example.domain.export.DocumentExporter.exportFinancialReportPdf(
            context = context,
            title = "Test Balance Sheet",
            reportText = "FINANCE VAULT STATEMENT\nTotal Assets: ₹50,000.00"
        )
        assertTrue(pdfResult.file.exists() && pdfResult.file.length() > 0 && pdfResult.file.extension == "pdf")

        val xlsxResult = com.example.domain.export.DocumentExporter.exportFinancialReportCsv(
            context = context,
            financialYear = "FY 2025-26",
            balanceSheet = bs,
            profitAndLoss = pnl,
            trialBalance = tb,
            journalBook = jb,
            asExcelExtension = true
        )
        assertTrue(xlsxResult.file.exists() && xlsxResult.file.length() > 0 && xlsxResult.file.extension == "xlsx")

        val csvResult = com.example.domain.export.DocumentExporter.exportFinancialReportCsv(
            context = context,
            financialYear = "FY 2025-26",
            balanceSheet = bs,
            profitAndLoss = pnl,
            trialBalance = tb,
            journalBook = jb,
            asExcelExtension = false
        )
        assertTrue(csvResult.file.exists() && csvResult.file.length() > 0 && csvResult.file.extension == "csv")
    }

    @get:org.junit.Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createComposeRule()

    @Test
    fun `clicking Preview and Export PDF opens ExportPreviewDialog without crashing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.ui.FinanceVaultViewModel(repository)
        composeTestRule.setContent {
            com.example.ui.theme.MyApplicationTheme {
                FinanceVaultApp(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()
        vm.selectTab(com.example.ui.MainTab.MORE)
        vm.navigateMore(com.example.ui.MoreDestination.REPORTS)
        composeTestRule.waitForIdle()
        vm.preparePdfExport(context)
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            vm.exportPreviewResult.value != null
        }
        org.junit.Assert.assertNotNull(vm.exportPreviewResult.value)
    }

    @Test
    fun `search history retains last 5 unique terms and supports clear`() {
        val vm = com.example.ui.FinanceVaultViewModel(repository)
        listOf("Salary", "Groceries", "Rent", "Fuel", "Electricity", "Medical").forEach {
            vm.commitSearchQuery(it)
        }
        assertEquals(
            listOf("Medical", "Electricity", "Fuel", "Rent", "Groceries"),
            vm.searchHistory.value
        )
        vm.clearSearchHistory()
        assertTrue(vm.searchHistory.value.isEmpty())
    }

    @Test
    fun `bulk categorize and bulk delete via reversal keep double-entry ledgers balanced`() = runBlocking {
        repository.ensureInitialized()
        val bankId = repository.createAccount(
            name = "Primary Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 1_00_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 200L,
            isDemo = false
        )
        val tx1 = repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 201L,
            amountPaise = 2_000_00L,
            sourceAccountId = bankId,
            category = "Other Expense",
            description = "Supermarket",
            isDemo = false
        )
        val tx2 = repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 202L,
            amountPaise = 3_000_00L,
            sourceAccountId = bankId,
            category = "Other Expense",
            description = "Vegetable Market",
            isDemo = false
        )

        val categorizedCount = repository.categorizeTransactionsBulk(setOf(tx1, tx2), "Household & Groceries")
        assertEquals(2, categorizedCount)
        assertEquals("Household & Groceries", repository. observeTransactions(false).first().first { it.id == tx1 }.category)

        val reversedCount = repository.reverseTransactionsBulk(setOf(tx1, tx2))
        assertEquals(2, reversedCount)

        val ledgers = repository.observeLedgerAccounts(isDemo = false).first()
        val lines = repository.observeJournalLines(isDemo = false).first()
        val tb = FinancialReportGenerator.generateTrialBalance(ledgers, lines)
        val bs = FinancialReportGenerator.generateBalanceSheet(ledgers, lines)
        assertTrue(tb.isAgreed)
        assertTrue(bs.isReconciled)
        assertEquals(1_00_000_00L, bs.currentBankBalancesPaise)
    }

    @Test
    fun `editing a transaction atomically updates journal lines and keeps Trial Balance and Balance Sheet reconciled`() = runBlocking {
        repository.ensureInitialized()
        val bankId = repository.createAccount(
            name = "HDFC Savings",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 50_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 300L,
            isDemo = false
        )
        val txId = repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 301L,
            amountPaise = 1_500_00L,
            sourceAccountId = bankId,
            category = "Food & Dining",
            description = "Dinner",
            locationName = "MG Road",
            isDemo = false
        )

        repository.editTransaction(
            transactionId = txId,
            dateEpochDay = 302L,
            amountPaise = 2_500_00L,
            sourceAccountId = bankId,
            destinationAccountId = null,
            category = "Household & Groceries",
            description = "Weekly Groceries",
            locationName = "Indiranagar",
            notes = "Updated receipt"
        )

        val updatedTx = repository.observeTransactions(isDemo = false).first().first { it.id == txId }
        assertEquals(2_500_00L, updatedTx.amountPaise)
        assertEquals("Household & Groceries", updatedTx.category)
        assertEquals("Weekly Groceries", updatedTx.description)

        val ledgers = repository.observeLedgerAccounts(isDemo = false).first()
        val lines = repository.observeJournalLines(isDemo = false).first()
        val tb = FinancialReportGenerator.generateTrialBalance(ledgers, lines)
        val bs = FinancialReportGenerator.generateBalanceSheet(ledgers, lines)
        assertTrue(tb.isAgreed)
        assertTrue(bs.isReconciled)
        assertEquals(47_500_00L, bs.currentBankBalancesPaise)
    }

    @Test
    fun `legacy SHA-256 PIN automatically upgrades to PBKDF2-HMAC-SHA256 and brute-force attempts trigger lockout`() = runBlocking {
        repository.ensureInitialized()
        val legacySalt = com.example.domain.security.PinSecurityUtil.generateSaltHex()
        val legacyHash = com.example.domain.security.PinSecurityUtil.hashPinLegacySha256("1234", legacySalt)

        val currentSettings = database.dao().getSettings()!!
        database.dao().upsertSettings(
            currentSettings.copy(
                pinHashSha256 = legacyHash,
                pinSalt = legacySalt,
                pinAlgorithm = com.example.domain.security.PinSecurityUtil.ALGORITHM_LEGACY_SHA256,
                pinIterations = 1,
                failedPinAttempts = 0,
                lockoutUntilEpochMillis = 0L
            )
        )

        // Verify with correct PIN -> should succeed and transparently migrate to PBKDF2-HMAC-SHA256
        val resOk = repository.verifyAndMigratePinWithThrottling("1234")
        assertTrue(resOk is com.example.data.repository.PinVerificationResult.Success)
        val migratedSettings = database.dao().getSettings()!!
        assertEquals(
            com.example.domain.security.PinSecurityUtil.ALGORITHM_PBKDF2_SHA256,
            migratedSettings.pinAlgorithm
        )
        assertEquals(
            com.example.domain.security.PinSecurityUtil.DEFAULT_PBKDF2_ITERATIONS,
            migratedSettings.pinIterations
        )

        // Fail 4 times -> 4th failure triggers 15-second lockout
        val fail1 = repository.verifyAndMigratePinWithThrottling("0000")
        assertTrue(fail1 is com.example.data.repository.PinVerificationResult.InvalidPin)
        val fail2 = repository.verifyAndMigratePinWithThrottling("0000")
        assertTrue(fail2 is com.example.data.repository.PinVerificationResult.InvalidPin)
        val fail3 = repository.verifyAndMigratePinWithThrottling("0000")
        assertTrue(fail3 is com.example.data.repository.PinVerificationResult.InvalidPin)
        val fail4 = repository.verifyAndMigratePinWithThrottling("0000")
        assertTrue(fail4 is com.example.data.repository.PinVerificationResult.InvalidPin && fail4.lockoutDurationMillis == 15_000L)

        // Even with the right PIN during active lockout, it rejects until lockout expires or biometric resets it
        val blockedDuringLockout = repository.verifyAndMigratePinWithThrottling("1234")
        assertTrue(blockedDuringLockout is com.example.data.repository.PinVerificationResult.LockedOut)

        repository.resetPinLockoutOnBiometricSuccess()
        val unlockedAfterReset = repository.verifyAndMigratePinWithThrottling("1234")
        assertTrue(unlockedAfterReset is com.example.data.repository.PinVerificationResult.Success)
    }

    @Test
    fun `JSON backup and deduplicated restore preserve accounts, transactions, budgets, goals, and subscriptions`() = runBlocking {
        repository.ensureInitialized()
        val accId = repository.createAccount(
            name = "Backup Test Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 25_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 400L,
            isDemo = false
        )
        repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 401L,
            amountPaise = 1_200_00L,
            sourceAccountId = accId,
            category = "Utilities & Bills",
            description = "Broadband Bill",
            isDemo = false
        )
        repository.addBudget(
            name = "Utilities Budget",
            budgetPeriod = com.example.data.local.BudgetPeriod.MONTHLY,
            category = "Utilities & Bills",
            limitPaise = 5_000_00L,
            financialYear = "FY 2026-27",
            notes = "",
            isDemo = false
        )

        val backupJson = repository.createJsonBackupString()
        assertTrue(backupJson.contains("Backup Test Bank"))
        assertTrue(backupJson.contains("Broadband Bill"))

        // Clear real data and restore from JSON backup
        repository.clearAllDataForMode(isDemo = false)
        assertTrue(repository.observeAccounts(isDemo = false).first().isEmpty())

        val restoredCount = repository.restoreFromJsonBackup(backupJson)
        assertTrue(restoredCount >= 2)

        // Restoring a second time should deduplicate accounts and transactions
        repository.restoreFromJsonBackup(backupJson)

        val restoredAccounts = repository.observeAccounts(isDemo = false).first()
        val restoredTxs = repository.observeTransactions(isDemo = false).first()
        assertEquals(1, restoredAccounts.size)
        assertEquals(2, restoredTxs.size) // 1 opening balance + 1 expense
    }

    @Test
    fun `demo mode seeds 10+ items across every dashboard module and reconciles Balance Sheet Net Worth with investments`() = runBlocking {
        repository.ensureInitialized()
        repository.setDemonstrationMode(true)

        val realTxs = repository.observeTransactions(isDemo = false).first()
        val demoAccounts = repository.observeAccounts(isDemo = true).first()
        val demoInvestments = repository.observeInvestments(isDemo = true).first()
        val demoSubscriptions = repository.observeSubscriptions(isDemo = true).first()
        val demoGiftCards = repository.observeGiftCards(isDemo = true).first()
        val demoBudgets = repository.observeBudgets(isDemo = true).first()
        val demoGoals = repository.observeGoals(isDemo = true).first()
        val demoLoans = repository.observeLoans(isDemo = true).first()
        val demoTaxes = repository.observeTaxRecords(isDemo = true).first()
        val demoTxs = repository.observeTransactions(isDemo = true).first()
        val nonOpeningDemoTxs = demoTxs.filter { it.transactionType != TransactionType.OPENING_BALANCE }

        assertTrue(realTxs.isEmpty())
        assertTrue("Expected at least 10 demo accounts, got ${demoAccounts.size}", demoAccounts.size >= 10)
        assertTrue("Expected at least 10 demo investments, got ${demoInvestments.size}", demoInvestments.size >= 10)
        assertTrue("Expected at least 10 demo subscriptions, got ${demoSubscriptions.size}", demoSubscriptions.size >= 10)
        assertTrue("Expected at least 10 demo gift cards, got ${demoGiftCards.size}", demoGiftCards.size >= 10)
        assertTrue("Expected at least 10 demo budgets, got ${demoBudgets.size}", demoBudgets.size >= 10)
        assertTrue("Expected at least 10 demo goals, got ${demoGoals.size}", demoGoals.size >= 10)
        assertTrue("Expected at least 10 demo loans, got ${demoLoans.size}", demoLoans.size >= 10)
        assertTrue("Expected at least 10 demo taxes, got ${demoTaxes.size}", demoTaxes.size >= 10)
        assertTrue("Expected at least 20 non-opening demo transactions, got ${nonOpeningDemoTxs.size}", nonOpeningDemoTxs.size >= 20)

        val demoLedgers = repository.observeLedgerAccounts(isDemo = true).first()
        val demoLines = repository.observeJournalLines(isDemo = true).first()
        val tb = FinancialReportGenerator.generateTrialBalance(demoLedgers, demoLines)
        val bs = FinancialReportGenerator.generateBalanceSheet(demoLedgers, demoLines)
        assertTrue(tb.isAgreed)
        assertTrue(bs.isReconciled)

        // Personal Net Worth on Balance Sheet includes Cash + Bank + Investments + Gift Cards - Liabilities
        val totalRecordedInvestmentValue = demoInvestments.sumOf { it.currentRecordedValuePaise ?: it.investedAmountPaise }
        assertEquals(totalRecordedInvestmentValue, bs.nonCurrentInvestmentsPaise)
        assertTrue(bs.currentOtherAssetsPaise > 0L) // Includes gift cards
        assertEquals(bs.totalAssetsPaise - bs.totalLiabilitiesPaise, bs.totalNetWorthPaise)
    }

    @Test
    fun `investment growth calculator computes current share value and annualized growing rate and syncs to Balance Sheet`() = runBlocking {
        repository.ensureInitialized()
        val bankId = repository.createAccount(
            name = "Wealth Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 2_00_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 100L,
            isDemo = false
        )

        val today = com.example.domain.period.IndianFinancialYear.todayEpochDay()
        val invId = repository.addInvestment(
            name = "Growth Stock Ltd",
            category = InvestmentCategory.EQUITY_SHARES,
            purchaseDateEpochDay = today - 365L,
            quantity = 20.0,
            units = 20.0,
            purchasePricePerUnitPaise = 1_000_00L,
            investedAmountPaise = 20_000_00L,
            currentRecordedValuePaise = 25_000_00L,
            isCurrentValueRecorded = true,
            paymentAccountId = bankId,
            isDemo = false
        )

        val inv = repository.observeInvestments(isDemo = false).first().first { it.id == invId }
        assertEquals(25_000_00L, inv.currentRecordedValuePaise)
        assertEquals(25.0, inv.annualInterestRatePercent ?: 0.0, 0.5)

        // Update valuation to ₹30,000 (₹1,500 per share)
        repository.updateInvestmentRecordedValue(
            investmentId = invId,
            newRecordedValuePaise = 30_000_00L,
            updatedUnits = 20.0,
            updatedBuyPricePerUnitPaise = 1_000_00L
        )

        val ledgers = repository.observeLedgerAccounts(isDemo = false).first()
        val lines = repository.observeJournalLines(isDemo = false).first()
        val bs = FinancialReportGenerator.generateBalanceSheet(ledgers, lines)
        val tb = FinancialReportGenerator.generateTrialBalance(ledgers, lines)

        assertTrue(tb.isAgreed)
        assertTrue(bs.isReconciled)
        assertEquals(1_80_000_00L, bs.currentBankBalancesPaise)
        assertEquals(30_000_00L, bs.nonCurrentInvestmentsPaise)
        assertEquals(2_10_000_00L, bs.totalAssetsPaise)
        assertEquals(2_10_000_00L, bs.totalNetWorthPaise)
    }

    @Test
    fun `voice deletion regression - 6 mandatory scenarios never delete unrelated transactions and always require confirmation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository.ensureInitialized()
        val bankId = repository.createAccount(
            name = "HDFC Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 50_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 500L,
            isDemo = false
        )
        val groceryTxId = repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 501L,
            amountPaise = 850_00L,
            sourceAccountId = bankId,
            category = "Household & Groceries",
            description = "Reliance Fresh Groceries",
            isDemo = false
        )

        val vm = com.example.ui.FinanceVaultViewModel(repository)
        composeTestRule.setContent {
            com.example.ui.theme.MyApplicationTheme {
                FinanceVaultApp(viewModel = vm)
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            !vm.uiState.value.isInitializing && vm.uiState.value.transactions.any { it.id == groceryTxId }
        }

        // Case 1: One active transaction with a NON-matching keyword -> must NOT delete or stage reversal; asks for clarification
        var reply1 = ""
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "delete Netflix transaction",
            onFinished = { reply, _, _ -> reply1 = reply }
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { reply1.isNotBlank() }
        org.junit.Assert.assertNull(
            "Non-matching keyword must not stage a reversal preview even when activeTxs.size == 1",
            vm.pendingAiConfirmation.value
        )
        assertTrue(reply1.contains("No transaction matched", ignoreCase = true))
        org.junit.Assert.assertNotNull(vm.pendingVoiceDisambiguation.value)
        vm.dismissVoiceDisambiguation()

        // Case 2: Ambiguous delete request ("delete transaction") -> asks for clarification, never auto-reverses
        var replyAmbiguous = ""
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "delete transaction",
            onFinished = { reply, _, _ -> replyAmbiguous = reply }
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { replyAmbiguous.isNotBlank() }
        org.junit.Assert.assertNull(vm.pendingAiConfirmation.value)
        org.junit.Assert.assertNotNull(vm.pendingVoiceDisambiguation.value)
        vm.dismissVoiceDisambiguation()

        // Case 3: One active transaction with a MATCHING keyword -> stages confirmation preview first (does NOT immediately reverse)
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "delete Reliance Fresh Groceries transaction"
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { vm.pendingAiConfirmation.value != null }
        val staged = vm.pendingAiConfirmation.value!!
        assertEquals("REVERSE_TRANSACTION", staged.operationType)
        assertEquals(groceryTxId, staged.targetTransactionIdToReverse)
        // Verify transaction is still active before confirmation
        assertTrue(
            !repository.observeTransactions(isDemo = false).first().first { it.id == groceryTxId }.isReversed
        )
        vm.dismissPendingAiConfirmation()

        // Case 4: Explicitly requested latest transaction ("reverse last transaction") -> stages confirmation preview
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "reverse last transaction"
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { vm.pendingAiConfirmation.value != null }
        assertEquals(groceryTxId, vm.pendingAiConfirmation.value?.targetTransactionIdToReverse)
        vm.dismissPendingAiConfirmation()

        // Add a second grocery transaction to test multiple matching transactions
        val secondGroceryTxId = repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 502L,
            amountPaise = 1_200_00L,
            sourceAccountId = bankId,
            category = "Household & Groceries",
            description = "Reliance Fresh Weekend",
            isDemo = false
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            vm.uiState.value.transactions.any { it.id == secondGroceryTxId }
        }

        // Case 5: Multiple matching transactions -> prompts disambiguation, does not auto-stage
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "delete Reliance Fresh transaction"
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { vm.pendingVoiceDisambiguation.value != null }
        org.junit.Assert.assertNull(vm.pendingAiConfirmation.value)
        assertTrue(vm.pendingVoiceDisambiguation.value!!.options.size >= 2)

        // Selecting an option from disambiguation stages the confirmation preview, and confirming reverses via journal entry
        val chosenOpt = vm.pendingVoiceDisambiguation.value!!.options.first()
        vm.selectVoiceDisambiguationOption(chosenOpt) { _, _, _ -> }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { vm.pendingAiConfirmation.value != null }
        var confirmReply = ""
        vm.confirmPendingAiTransaction(context) { confirmReply = it }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { confirmReply.isNotBlank() }

        // Verify original history is preserved with isReversed = true and a REVERSAL journal entry
        val allTxsAfterReversal = repository.observeTransactions(isDemo = false).first()
        val reversedTx = allTxsAfterReversal.first { it.id == chosenOpt.entityId }
        assertTrue(reversedTx.isReversed)
        val journalEntries = repository.observeJournalEntries(isDemo = false).first()
        assertTrue(journalEntries.any { it.isReversal })

        // Case 6: No matching transactions when multiple transactions exist -> asks for clarification
        var replyNoMatch = ""
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "delete Uber flight transaction of 99999",
            onFinished = { reply, _, _ -> replyNoMatch = reply }
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { replyNoMatch.isNotBlank() }
        org.junit.Assert.assertNull(vm.pendingAiConfirmation.value)
        assertTrue(replyNoMatch.contains("No transaction matched", ignoreCase = true))
    }

    @Test
    fun `missing financial amounts in voice commands prompt user instead of inventing 100 rupees and require explicit confirmation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository.ensureInitialized()
        repository.createAccount(
            name = "SBI Savings",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 40_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 600L,
            isDemo = false
        )

        val vm = com.example.ui.FinanceVaultViewModel(repository)
        composeTestRule.setContent {
            com.example.ui.theme.MyApplicationTheme {
                FinanceVaultApp(viewModel = vm)
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            !vm.uiState.value.isInitializing && vm.uiState.value.accountsWithBalances.isNotEmpty()
        }

        // Voice command for gift card without an amount must NOT invent ₹100 (10000L); it must ask for the amount
        var promptReply = ""
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "add gift card Amazon",
            onFinished = { reply, _, _ -> promptReply = reply }
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { promptReply.isNotBlank() }
        assertTrue("Gift cards must remain empty when amount is missing", repository.observeGiftCards(false).first().isEmpty())
        org.junit.Assert.assertNull(vm.pendingAiConfirmation.value)
        org.junit.Assert.assertNotNull(
            "Missing amount must trigger a follow-up prompt",
            vm.pendingVoiceDisambiguation.value?.onSpokenAmountProvided
        )

        // User speaks the missing amount "2500 rupees" -> stages confirmation preview without saving yet
        vm.executeVoiceOrGeminiCommand(
            context = context,
            utterance = "2500 rupees"
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { vm.pendingAiConfirmation.value != null }
        assertTrue("Gift card must not be saved before user confirms", repository.observeGiftCards(false).first().isEmpty())
        assertEquals(2_500_00L, vm.pendingAiConfirmation.value?.amountPaise)
        assertEquals("ADD_GIFT_CARD", vm.pendingAiConfirmation.value?.operationType)

        // Confirming commits the ₹2,500 gift card
        var commitReply = ""
        vm.confirmPendingAiTransaction(context) { commitReply = it }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { commitReply.isNotBlank() }
        val savedCards = repository.observeGiftCards(false).first()
        assertEquals(1, savedCards.size)
        assertEquals(2_500_00L, savedCards.first().initialValuePaise)
    }

    @Test
    fun `demo and real data modes are strictly isolated and reject cross-mode references`() = runBlocking {
        repository.ensureInitialized()
        repository.setDemonstrationMode(true)

        val realBankId = repository.createAccount(
            name = "Real Isolated Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 10_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 700L,
            isDemo = false
        )
        val demoBankId = repository.observeAccounts(isDemo = true).first().first().id
        val demoInvId = repository.observeInvestments(isDemo = true).first().first().id
        val demoLoanId = repository.observeLoans(isDemo = true).first().first().id
        val demoGiftCardId = repository.observeGiftCards(isDemo = true).first().first().id

        // 1. Attempting to record a Real transaction using a Demo source account must be rejected
        val crossAccountFailure = runCatching {
            repository.recordTransaction(
                transactionType = TransactionType.EXPENSE,
                dateEpochDay = 701L,
                amountPaise = 500_00L,
                sourceAccountId = demoBankId,
                category = "Food & Dining",
                description = "Cross-mode attempt",
                isDemo = false
            )
        }
        assertTrue("Cross-mode account reference must be rejected", crossAccountFailure.isFailure)

        // 2. Attempting to link a Demo investment to a Real transaction must be rejected
        val crossInvestmentFailure = runCatching {
            repository.recordTransaction(
                transactionType = TransactionType.INVESTMENT_PURCHASE,
                dateEpochDay = 701L,
                amountPaise = 1_000_00L,
                sourceAccountId = realBankId,
                category = "Investments",
                description = "Cross-mode investment attempt",
                linkedInvestmentId = demoInvId,
                isDemo = false
            )
        }
        assertTrue("Cross-mode investment reference must be rejected", crossInvestmentFailure.isFailure)

        // 3. Attempting to link a Demo loan to a Real EMI transaction must be rejected
        val crossLoanFailure = runCatching {
            repository.recordTransaction(
                transactionType = TransactionType.LOAN_REPAYMENT,
                dateEpochDay = 701L,
                amountPaise = 1_500_00L,
                principalComponentPaise = 1_000_00L,
                interestComponentPaise = 500_00L,
                sourceAccountId = realBankId,
                category = "Loan EMI",
                description = "Cross-mode loan attempt",
                linkedLoanId = demoLoanId,
                isDemo = false
            )
        }
        assertTrue("Cross-mode loan reference must be rejected", crossLoanFailure.isFailure)

        // 4. Attempting to link a Demo gift card to a Real transaction must be rejected
        val crossGiftCardFailure = runCatching {
            repository.recordTransaction(
                transactionType = TransactionType.GIFT_CARD_USAGE,
                dateEpochDay = 701L,
                amountPaise = 200_00L,
                category = "Shopping",
                description = "Cross-mode gift card attempt",
                linkedGiftCardId = demoGiftCardId,
                isDemo = false
            )
        }
        assertTrue("Cross-mode gift card reference must be rejected", crossGiftCardFailure.isFailure)
    }

    @Test
    fun `versioned JSON backup supports AES-256-GCM encryption, SHA-256 integrity validation, and full multi-entity round trip`() = runBlocking {
        repository.ensureInitialized()
        repository.setSecurityPin("9876")
        val bankId = repository.createAccount(
            name = "Vault Encrypted Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 1_00_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 800L,
            isDemo = false
        )
        repository.addInvestment(
            name = "Sovereign Gold Bond",
            category = InvestmentCategory.GOLD,
            purchaseDateEpochDay = 801L,
            quantity = 5.0,
            units = 5.0,
            purchasePricePerUnitPaise = 6_000_00L,
            investedAmountPaise = 30_000_00L,
            paymentAccountId = bankId,
            isDemo = false
        )
        repository.addSubscription(
            name = "Cloud Storage Pro",
            costPaise = 499_00L,
            billingFrequency = com.example.data.local.BillingFrequency.MONTHLY,
            nextRenewalEpochDay = 830L,
            paymentAccountId = bankId,
            notes = "Annual tier",
            isDemo = false
        )
        repository.addGoal(
            name = "Emergency Reserve",
            goalType = com.example.data.local.GoalType.EMERGENCY_FUND,
            targetAmountPaise = 5_00_000_00L,
            currentSavedPaise = 25_000_00L,
            targetDateEpochDay = 1200L,
            linkedAccountId = bankId,
            notes = "6 months runway",
            isDemo = false
        )
        repository.addTaxRecord(
            taxType = com.example.data.local.TaxType.ADVANCE_TAX,
            financialYear = "FY 2026-27",
            amountPaidPaise = 15_000_00L,
            paymentDateEpochDay = 802L,
            challanNumber = "CHALLAN-99",
            paymentAccountId = bankId,
            notes = "Q1 Advance Tax",
            isDemo = false
        )

        // Export with password encryption
        val encryptedBackup = repository.createJsonBackupString(password = "StrongVaultPass!23")
        // Verify plaintext secrets and entity names are not leaked in ciphertext envelope
        assertTrue(!encryptedBackup.contains("pinHashSha256"))
        assertTrue(!encryptedBackup.contains("Sovereign Gold Bond"))

        // Inspect with wrong password -> fails cleanly
        val wrongPassInspect = runCatching {
            repository.inspectJsonBackup(encryptedBackup, password = "WrongPassword")
        }
        assertTrue(wrongPassInspect.isFailure)

        // Inspect with right password -> returns accurate preview
        val preview = repository.inspectJsonBackup(encryptedBackup, password = "StrongVaultPass!23")
        assertTrue(preview.isEncrypted)
        assertTrue(preview.checksumVerified)
        assertEquals(1, preview.accountsCount)
        assertEquals(1, preview.investmentsCount)
        assertEquals(1, preview.subscriptionsCount)
        assertEquals(1, preview.goalsCount)
        assertEquals(1, preview.taxRecordsCount)

        // Clear all real data and restore with REPLACE_EXISTING
        repository.clearAllDataForMode(isDemo = false)
        assertTrue(repository.observeInvestments(isDemo = false).first().isEmpty())

        repository.restoreFromJsonBackup(
            jsonString = encryptedBackup,
            password = "StrongVaultPass!23",
            mode = com.example.data.repository.BackupRestoreMode.REPLACE_EXISTING
        )

        assertEquals(1, repository.observeAccounts(isDemo = false).first().size)
        assertEquals(1, repository.observeInvestments(isDemo = false).first().size)
        assertEquals(1, repository.observeSubscriptions(isDemo = false).first().size)
        assertEquals(1, repository.observeGoals(isDemo = false).first().size)
        assertEquals(1, repository.observeTaxRecords(isDemo = false).first().size)
        assertEquals("Sovereign Gold Bond", repository.observeInvestments(isDemo = false).first().first().name)
        assertEquals(30_000_00L, repository.observeInvestments(isDemo = false).first().first().investedAmountPaise)

        val ledgers = repository.observeLedgerAccounts(isDemo = false).first()
        val lines = repository.observeJournalLines(isDemo = false).first()
        val tb = FinancialReportGenerator.generateTrialBalance(ledgers, lines)
        val bs = FinancialReportGenerator.generateBalanceSheet(ledgers, lines)
        assertTrue(tb.isAgreed)
        assertTrue(bs.isReconciled)
        assertEquals(55_000_00L, bs.currentBankBalancesPaise) // 1,00,000 - 30,000 inv - 15,000 tax
        assertEquals(30_000_00L, bs.nonCurrentInvestmentsPaise)
    }

    @Test
    fun `accounting validation enforces cost basis cap, cumulative refund cap, loan principal cap, and resilient enum converters`() = runBlocking {
        repository.ensureInitialized()
        val bankId = repository.createAccount(
            name = "Validation Bank",
            accountType = AccountType.SAVINGS,
            openingBalancePaise = 2_00_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = 900L,
            isDemo = false
        )
        val invId = repository.addInvestment(
            name = "Bluechip Equity",
            category = InvestmentCategory.EQUITY_SHARES,
            purchaseDateEpochDay = 901L,
            quantity = 10.0,
            units = 10.0,
            purchasePricePerUnitPaise = 1_000_00L,
            investedAmountPaise = 10_000_00L,
            paymentAccountId = bankId,
            isDemo = false
        )

        // 1. Disposing more cost basis than remaining holding cost basis must fail
        val excessiveCostBasis = runCatching {
            repository.recordTransaction(
                transactionType = TransactionType.INVESTMENT_SALE,
                dateEpochDay = 902L,
                amountPaise = 15_000_00L,
                costBasisDisposedPaise = 12_000_00L, // > 10,000_00L invested
                destinationAccountId = bankId,
                category = "Investment Sale",
                description = "Over-disposal attempt",
                linkedInvestmentId = invId,
                isDemo = false
            )
        }
        assertTrue("Disposing more than invested cost basis must fail", excessiveCostBasis.isFailure)

        // 2. Cumulative refunds exceeding original expense amount must fail
        val expenseTxId = repository.recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = 903L,
            amountPaise = 2_000_00L,
            sourceAccountId = bankId,
            category = "Shopping",
            description = "Shoes Purchase",
            isDemo = false
        )
        // First partial refund of ₹1,200 succeeds
        repository.recordTransaction(
            transactionType = TransactionType.REFUND_RECEIVED,
            dateEpochDay = 904L,
            amountPaise = 1_200_00L,
            destinationAccountId = bankId,
            category = "Refund",
            description = "Partial Refund 1",
            linkedOriginalTransactionId = expenseTxId,
            isDemo = false
        )
        // Second refund of ₹1,000 (total ₹2,200 > ₹2,000) must fail
        val excessiveRefund = runCatching {
            repository.recordTransaction(
                transactionType = TransactionType.REFUND_RECEIVED,
                dateEpochDay = 905L,
                amountPaise = 1_000_00L,
                destinationAccountId = bankId,
                category = "Refund",
                description = "Excessive Refund 2",
                linkedOriginalTransactionId = expenseTxId,
                isDemo = false
            )
        }
        assertTrue("Cumulative refunds exceeding original expense must fail", excessiveRefund.isFailure)

        // 3. Resilient TypeConverters fallback gracefully on unknown enum strings
        val converters = com.example.data.local.FinanceTypeConverters()
        assertEquals(AccountType.OTHER, converters.toAccountType("CORRUPT_UNKNOWN_TYPE"))
        assertEquals(TransactionType.EXPENSE, converters.toTransactionType("INVALID_TX_TYPE"))
    }
}


