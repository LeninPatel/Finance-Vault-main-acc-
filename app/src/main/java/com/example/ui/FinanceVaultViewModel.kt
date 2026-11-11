package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.AppSettingsEntity
import com.example.data.local.AuditLogEntity
import com.example.data.local.BillingFrequency
import com.example.data.local.BudgetEntity
import com.example.data.local.BudgetPeriod
import com.example.data.local.CompoundingFrequency
import com.example.data.local.GiftCardEntity
import com.example.data.local.GoalEntity
import com.example.data.local.GoalType
import com.example.data.local.InvestmentCategory
import com.example.data.local.InvestmentEntity
import com.example.data.local.JournalEntryEntity
import com.example.data.local.JournalLineEntity
import com.example.data.local.LedgerAccountEntity
import com.example.data.local.LoanEntity
import com.example.data.local.LoanPaymentEntity
import com.example.data.local.LoanType
import com.example.data.local.SubscriptionEntity
import com.example.data.local.TaxRecordEntity
import com.example.data.local.TaxType
import com.example.data.local.ThemeMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.repository.FinanceVaultRepository
import com.example.domain.ai.GeminiVaultAssistant
import com.example.domain.ai.ParsedVoiceTransaction
import com.example.domain.ai.TransactionPatternAnalyzer
import com.example.domain.calculators.FinancialCalculators
import com.example.domain.export.DocumentExporter
import com.example.domain.location.LocationHelper
import com.example.domain.money.InrMoney
import com.example.domain.period.IndianFinancialYear
import com.example.domain.reminder.TransactionRecallReminderManager
import com.example.domain.reports.FinancialReportGenerator
import com.example.domain.security.PinSecurityUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import com.example.data.repository.PinVerificationResult

enum class MainTab(val title: String, val testTag: String, val showInBottomBar: Boolean = true) {
    HOME("Home", "tab_home", true),
    ACTIVITY("Transactions", "tab_activity", true),
    ACCOUNTS("Accounts", "tab_accounts", true),
    INSIGHTS("Insights", "tab_insights", true),
    MORE("More", "tab_more", true),
    INVESTMENTS("Investments", "tab_investments", false)
}

enum class MoreDestination(val title: String, val subtitle: String, val testTag: String) {
    HUB("More Hub", "", "more_hub"),
    REPORTS("Financial Statements & Ledgers", "", "more_reports"),
    ANALYTICS("Analytics & Performance", "", "more_analytics"),
    INVESTMENTS("Investments & Portfolio", "", "more_investments"),
    LOANS("Loans & Credit Cards", "", "more_loans"),
    BUDGETS_GOALS("Budgets & Savings Goals", "", "more_budgets_goals"),
    GIFT_CARDS("Gift Card", "", "more_gift_cards"),
    SUBSCRIPTIONS("Subscriptions & Renewals", "", "more_subscriptions"),
    TAXES("Indian Tax Records", "", "more_taxes"),
    SETTINGS("Settings, Backup & About", "", "more_settings")
}

enum class ReportTab(val userLabel: String, val accountingSubtitle: String) {
    BALANCE_SHEET("Balance Sheet", ""),
    PROFIT_AND_LOSS("Profit and Loss Account", ""),
    CASH_FLOW("Cash Flow Statement", ""),
    TRIAL_BALANCE("Trial Balance", ""),
    ACCOUNT_LEDGER("General Ledger", ""),
    JOURNAL_BOOK("Journal Book", ""),
    SCHEDULES("Schedules to Accounts", "")
}

enum class BudgetAlertTier(val thresholdLevel: Int, val badgeLabel: String) {
    NORMAL(0, "On Track"),
    REACHED_80(80, "80% Threshold Reached"),
    REACHED_90(90, "90% Critical Alert"),
    EXCEEDED_100(100, "Budget Exceeded")
}

@Immutable
data class BudgetThresholdStatus(
    val budget: BudgetEntity,
    val isOverall: Boolean,
    val spentPaise: Long,
    val limitPaise: Long,
    val usagePercent: Double,
    val tier: BudgetAlertTier
)

@Immutable
data class AccountWithBalance(
    val account: AccountEntity,
    val ledgerAccount: LedgerAccountEntity?,
    val closingBalancePaise: Long,
    val totalDebitsPaise: Long,
    val totalCreditsPaise: Long
)

private val EMPTY_BALANCE_SHEET = FinancialReportGenerator.generateBalanceSheet(emptyList(), emptyList())
private val EMPTY_PROFIT_AND_LOSS = FinancialReportGenerator.generateProfitAndLoss(emptyList(), emptyList())
private val EMPTY_CASH_FLOW = FinancialReportGenerator.generateCashFlowStatement(emptyList(), emptyList())
private val EMPTY_TRIAL_BALANCE = FinancialReportGenerator.generateTrialBalance(emptyList(), emptyList())
private val EMPTY_JOURNAL_BOOK = FinancialReportGenerator.generateJournalBook(emptyList(), emptyList())

@Immutable
data class FinanceVaultUiState(
    val isInitializing: Boolean = true,
    val settings: AppSettingsEntity = AppSettingsEntity(),
    val isLockedByPin: Boolean = false,
    val accountsWithBalances: List<AccountWithBalance> = emptyList(),
    val ledgerAccounts: List<LedgerAccountEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val journalEntries: List<JournalEntryEntity> = emptyList(),
    val journalLines: List<JournalLineEntity> = emptyList(),
    val investments: List<InvestmentEntity> = emptyList(),
    val loans: List<LoanEntity> = emptyList(),
    val loanPayments: List<LoanPaymentEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val budgetStatuses: List<BudgetThresholdStatus> = emptyList(),
    val overallMonthlyBudgetStatus: BudgetThresholdStatus? = null,
    val currentMonthTotalSpentPaise: Long = 0L,
    val customOptionsMap: Map<String, List<String>> = emptyMap(),
    val goals: List<GoalEntity> = emptyList(),
    val giftCards: List<GiftCardEntity> = emptyList(),
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val taxRecords: List<TaxRecordEntity> = emptyList(),
    val auditLogs: List<AuditLogEntity> = emptyList(),
    // Derived Reports from Single Source of Truth (Journal & Ledgers)
    val balanceSheet: FinancialReportGenerator.BalanceSheetReport = EMPTY_BALANCE_SHEET,
    val fyProfitAndLoss: FinancialReportGenerator.ProfitAndLossReport = EMPTY_PROFIT_AND_LOSS,
    val monthlyProfitAndLoss: FinancialReportGenerator.ProfitAndLossReport = EMPTY_PROFIT_AND_LOSS,
    val fyCashFlow: FinancialReportGenerator.CashFlowReport = EMPTY_CASH_FLOW,
    val trialBalance: FinancialReportGenerator.TrialBalanceReport = EMPTY_TRIAL_BALANCE,
    val journalBook: FinancialReportGenerator.JournalBookReport = EMPTY_JOURNAL_BOOK,
    val monthlySavingsRatePercent: Double = 0.0,
    val fySavingsRatePercent: Double = 0.0,
    val hasAnyUserRecords: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceVaultViewModel(
    private val repository: FinanceVaultRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(MainTab.HOME)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _moreDestination = MutableStateFlow(MoreDestination.HUB)
    val moreDestination: StateFlow<MoreDestination> = _moreDestination.asStateFlow()

    private val _selectedReportTab = MutableStateFlow<ReportTab?>(null)
    val selectedReportTab: StateFlow<ReportTab?> = _selectedReportTab.asStateFlow()

    private val _selectedLedgerAccountId = MutableStateFlow<Long?>(null)
    val selectedLedgerAccountId: StateFlow<Long?> = _selectedLedgerAccountId.asStateFlow()

    private val _showAddTransactionSheet = MutableStateFlow(false)
    val showAddTransactionSheet: StateFlow<Boolean> = _showAddTransactionSheet.asStateFlow()

    private val _showGeminiAssistantDialog = MutableStateFlow(false)
    val showGeminiAssistantDialog: StateFlow<Boolean> = _showGeminiAssistantDialog.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _exportPreviewResult = MutableStateFlow<DocumentExporter.ExportedDocumentResult?>(null)
    val exportPreviewResult: StateFlow<DocumentExporter.ExportedDocumentResult?> = _exportPreviewResult.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _isSearchExpanded = MutableStateFlow(false)
    val isSearchExpanded: StateFlow<Boolean> = _isSearchExpanded.asStateFlow()

    @Immutable
    data class VoiceDisambiguationOption(
        val index: Int,
        val label: String,
        val subtitle: String = "",
        val entityId: Long = 0L
    )

    @Immutable
    data class PendingVoiceDisambiguation(
        val title: String,
        val questionPrompt: String,
        val options: List<VoiceDisambiguationOption>,
        val onSelectOption: suspend (VoiceDisambiguationOption) -> String,
        val onSpokenAmountProvided: (suspend (Long) -> String)? = null
    )

    private sealed interface UndoableVoiceAction {
        val description: String
        data class CreatedTransaction(val transactionId: Long, override val description: String) : UndoableVoiceAction
        data class ReversedTransaction(val snapshot: TransactionEntity, override val description: String) : UndoableVoiceAction
        data class CreatedSubscription(val subscriptionId: Long, override val description: String) : UndoableVoiceAction
        data class DeletedSubscription(val snapshot: SubscriptionEntity, override val description: String) : UndoableVoiceAction
        data class CreatedBudget(val budgetId: Long, override val description: String) : UndoableVoiceAction
        data class DeletedBudget(val snapshot: BudgetEntity, override val description: String) : UndoableVoiceAction
        data class CreatedGoal(val goalId: Long, override val description: String) : UndoableVoiceAction
        data class DeletedGoal(val snapshot: GoalEntity, override val description: String) : UndoableVoiceAction
        data class ArchivedAccount(val accountId: Long, override val description: String) : UndoableVoiceAction
    }

    @Immutable
    data class PendingAiTransactionConfirmation(
        val transactionType: TransactionType = TransactionType.EXPENSE,
        val dateEpochDay: Long = IndianFinancialYear.todayEpochDay(),
        val amountPaise: Long = 0L,
        val sourceAccountId: Long? = null,
        val sourceAccountName: String = "",
        val destinationAccountId: Long? = null,
        val destinationAccountName: String = "",
        val category: String = "",
        val description: String = "",
        val locationName: String = "",
        val latitude: Double? = null,
        val longitude: Double? = null,
        val isDemo: Boolean = false,
        val operationType: String = "RECORD_TRANSACTION",
        val operationTitle: String = "Confirm AI Transaction",
        val targetTransactionIdToReverse: Long? = null,
        val customCommitAction: (suspend () -> String)? = null
    )

    private val _pendingVoiceDisambiguation = MutableStateFlow<PendingVoiceDisambiguation?>(null)
    val pendingVoiceDisambiguation: StateFlow<PendingVoiceDisambiguation?> = _pendingVoiceDisambiguation.asStateFlow()

    private val _pendingAiConfirmation = MutableStateFlow<PendingAiTransactionConfirmation?>(null)
    val pendingAiConfirmation: StateFlow<PendingAiTransactionConfirmation?> = _pendingAiConfirmation.asStateFlow()

    private val _pendingBackupRestorePreview = MutableStateFlow<com.example.data.repository.BackupRestorePreview?>(null)
    val pendingBackupRestorePreview: StateFlow<com.example.data.repository.BackupRestorePreview?> = _pendingBackupRestorePreview.asStateFlow()

    private val _pinLockoutErrorMessage = MutableStateFlow<String?>(null)
    val pinLockoutErrorMessage: StateFlow<String?> = _pinLockoutErrorMessage.asStateFlow()

    private var lastUndoableVoiceAction: UndoableVoiceAction? = null
    private var activeVoiceCommandJob: Job? = null

    private val _isPinUnlockedSession = MutableStateFlow(false)
    private val lastUserInteractionMillis = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private val backgroundEnteredAtMillis = java.util.concurrent.atomic.AtomicLong(0L)
    private val notifiedBudgetTiers = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureInitialized()
        }
    }

    private val settingsFlow = repository.observeSettings()
        .map { it ?: AppSettingsEntity() }
        .distinctUntilChanged()

    private val isDemoFlow = settingsFlow
        .map { it.isDemoModeEnabled }
        .distinctUntilChanged()

    private val coreAccountingFlow = isDemoFlow.flatMapLatest { isDemo ->
        combine(
            repository.observeAccounts(isDemo),
            repository.observeLedgerAccounts(isDemo),
            repository.observeTransactions(isDemo),
            repository.observeJournalEntries(isDemo),
            repository.observeJournalLines(isDemo)
        ) { accounts, ledgers, txs, entries, lines ->
            CoreAccountingBundle(accounts, ledgers, txs, entries, lines)
        }
    }

    private val secondaryEntitiesFlow = isDemoFlow.flatMapLatest { isDemo ->
        combine(
            repository.observeInvestments(isDemo),
            repository.observeLoans(isDemo),
            repository.observeLoanPayments(isDemo),
            repository.observeBudgets(isDemo),
            repository.observeGoals(isDemo)
        ) { investments, loans, loanPayments, budgets, goals ->
            SecondaryBundle1(investments, loans, loanPayments, budgets, goals)
        }
    }

    private val tertiaryEntitiesFlow = isDemoFlow.flatMapLatest { isDemo ->
        combine(
            repository.observeGiftCards(isDemo),
            repository.observeSubscriptions(isDemo),
            repository.observeTaxRecords(isDemo),
            repository.observeAuditLogs(isDemo)
        ) { giftCards, subscriptions, taxes, logs ->
            SecondaryBundle2(giftCards, subscriptions, taxes, logs)
        }
    }

    val uiState: StateFlow<FinanceVaultUiState> = combine(
        settingsFlow,
        _isPinUnlockedSession,
        coreAccountingFlow,
        secondaryEntitiesFlow,
        tertiaryEntitiesFlow
    ) { rawSettings, unlocked, core, sec1, sec2 ->
        val systemFy = IndianFinancialYear.currentFyLabel()
        val settings = rawSettings.copy(activeFinancialYear = systemFy)
        val (fyStart, fyEnd) = IndianFinancialYear.getRangeForFyLabel(systemFy)
        val (monthStart, monthEnd) = IndianFinancialYear.currentMonthRange()

        val linesByLedgerId = core.lines.groupBy { it.ledgerAccountId }
        val ledgerByUserAccId = core.ledgers
            .filter { it.linkedUserAccountId != null }
            .associateBy { it.linkedUserAccountId!! }

        val accountsWithBalances = core.accounts.map { acc ->
            val ledger = ledgerByUserAccId[acc.id]
            val accLines = if (ledger != null) linesByLedgerId[ledger.id].orEmpty() else emptyList()
            val debits = accLines.sumOf { it.debitPaise }
            val credits = accLines.sumOf { it.creditPaise }
            val closing = com.example.domain.accounting.AccountingEngine.calculateClosingBalance(
                normalBalance = acc.accountType.normalBalance,
                openingBalancePaise = 0L,
                totalDebitsPaise = debits,
                totalCreditsPaise = credits
            )
            AccountWithBalance(
                account = acc,
                ledgerAccount = ledger,
                closingBalancePaise = closing,
                totalDebitsPaise = debits,
                totalCreditsPaise = credits
            )
        }

        val balanceSheet = FinancialReportGenerator.generateBalanceSheet(
            ledgerAccounts = core.ledgers,
            allLines = core.lines
        )
        val fyPnl = FinancialReportGenerator.generateProfitAndLoss(
            ledgerAccounts = core.ledgers,
            allLines = core.lines,
            fromEpochDay = fyStart,
            toEpochDay = fyEnd
        )
        val monthlyPnl = FinancialReportGenerator.generateProfitAndLoss(
            ledgerAccounts = core.ledgers,
            allLines = core.lines,
            fromEpochDay = monthStart,
            toEpochDay = monthEnd
        )
        val fyCashFlow = FinancialReportGenerator.generateCashFlowStatement(
            ledgerAccounts = core.ledgers,
            allLines = core.lines,
            fromEpochDay = fyStart,
            toEpochDay = fyEnd
        )
        val trialBalance = FinancialReportGenerator.generateTrialBalance(
            ledgerAccounts = core.ledgers,
            allLines = core.lines
        )
        val journalBook = FinancialReportGenerator.generateJournalBook(
            entries = core.entries,
            lines = core.lines,
            fromEpochDay = fyStart,
            toEpochDay = fyEnd
        )

        val monthlySavingsRate = FinancialCalculators.calculateSavingsRate(
            incomePaise = monthlyPnl.totalIncomePaise,
            expensesPaise = monthlyPnl.totalExpensesPaise
        )
        val fySavingsRate = FinancialCalculators.calculateSavingsRate(
            incomePaise = fyPnl.totalIncomePaise,
            expensesPaise = fyPnl.totalExpensesPaise
        )

        val hasPin = !settings.pinHashSha256.isNullOrBlank()
        val hasSecurityLock = hasPin || settings.isBiometricEnabled
        val hasRecords = core.accounts.isNotEmpty() ||
            core.txs.isNotEmpty() ||
            sec1.investments.isNotEmpty() ||
            sec1.loans.isNotEmpty() ||
            sec2.giftCards.isNotEmpty()

        val expenseTypes = setOf(
            TransactionType.EXPENSE,
            TransactionType.CREDIT_CARD_PURCHASE,
            TransactionType.GIFT_CARD_USAGE
        )
        val activeMonthExpenseTxs = core.txs.filter {
            !it.isReversed && it.dateEpochDay in monthStart..monthEnd && it.transactionType in expenseTypes
        }
        val activeFyExpenseTxs = core.txs.filter {
            !it.isReversed && it.dateEpochDay in fyStart..fyEnd && it.transactionType in expenseTypes
        }
        val currentMonthTotalSpentPaise = activeMonthExpenseTxs.sumOf { it.amountPaise }
        val currentFyTotalSpentPaise = activeFyExpenseTxs.sumOf { it.amountPaise }

        val allBudgetStatuses = sec1.budgets.map { b ->
            val isOverall = b.category.equals("OVERALL", ignoreCase = true)
            val spentPaise = if (isOverall) {
                if (b.budgetPeriod == BudgetPeriod.MONTHLY) currentMonthTotalSpentPaise else currentFyTotalSpentPaise
            } else {
                val pool = if (b.budgetPeriod == BudgetPeriod.MONTHLY) activeMonthExpenseTxs else activeFyExpenseTxs
                pool.filter { it.category.equals(b.category, ignoreCase = true) }.sumOf { it.amountPaise }
            }
            val pct = if (b.limitPaise > 0L) (spentPaise.toDouble() / b.limitPaise.toDouble()) * 100.0 else 0.0
            val tier = when {
                pct >= 100.0 -> BudgetAlertTier.EXCEEDED_100
                pct >= 90.0 -> BudgetAlertTier.REACHED_90
                pct >= 80.0 -> BudgetAlertTier.REACHED_80
                else -> BudgetAlertTier.NORMAL
            }
            BudgetThresholdStatus(
                budget = b,
                isOverall = isOverall,
                spentPaise = spentPaise,
                limitPaise = b.limitPaise,
                usagePercent = pct,
                tier = tier
            )
        }

        // Trigger non-intrusive 80% and 90% threshold notifications for monthly budgets (Overall & Category)
        for (status in allBudgetStatuses.filter { it.budget.budgetPeriod == BudgetPeriod.MONTHLY }) {
            if (status.usagePercent >= 80.0) {
                val alertLevel = if (status.usagePercent >= 90.0) 90 else 80
                val key = "${settings.isDemoModeEnabled}_${status.budget.id}_${status.budget.category.uppercase()}"
                val prevLevel = notifiedBudgetTiers[key] ?: 0
                if (alertLevel > prevLevel) {
                    notifiedBudgetTiers[key] = alertLevel
                    val pctStr = String.format(java.util.Locale.US, "%.0f", status.usagePercent)
                    val targetName = if (status.isOverall) "Overall Monthly Budget" else status.budget.category
                    _statusBannerMessage.value = "Budget Alert ($alertLevel%): $targetName reached $pctStr% of monthly budget."
                }
            }
        }

        val overallMonthlyStatus = allBudgetStatuses.firstOrNull {
            it.isOverall && it.budget.budgetPeriod == BudgetPeriod.MONTHLY
        }
        val categoryBudgetStatuses = allBudgetStatuses.filter { !it.isOverall }
        val customOptionsMap = FinanceVaultRepository.parseCustomOptionsMap(settings.customCategoriesJson)

        FinanceVaultUiState(
            isInitializing = false,
            settings = settings,
            isLockedByPin = hasSecurityLock && !unlocked,
            accountsWithBalances = accountsWithBalances,
            ledgerAccounts = core.ledgers,
            transactions = core.txs,
            journalEntries = core.entries,
            journalLines = core.lines,
            investments = sec1.investments,
            loans = sec1.loans,
            loanPayments = sec1.loanPayments,
            budgets = sec1.budgets,
            budgetStatuses = categoryBudgetStatuses,
            overallMonthlyBudgetStatus = overallMonthlyStatus,
            currentMonthTotalSpentPaise = currentMonthTotalSpentPaise,
            customOptionsMap = customOptionsMap,
            goals = sec1.goals,
            giftCards = sec2.giftCards,
            subscriptions = sec2.subscriptions,
            taxRecords = sec2.taxes,
            auditLogs = sec2.logs,
            balanceSheet = balanceSheet,
            fyProfitAndLoss = fyPnl,
            monthlyProfitAndLoss = monthlyPnl,
            fyCashFlow = fyCashFlow,
            trialBalance = trialBalance,
            journalBook = journalBook,
            monthlySavingsRatePercent = monthlySavingsRate,
            fySavingsRatePercent = fySavingsRate,
            hasAnyUserRecords = hasRecords
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FinanceVaultUiState(isInitializing = true)
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun commitSearchQuery(rawQuery: String = _searchQuery.value) {
        val clean = rawQuery.trim()
        if (clean.isEmpty()) return
        _searchQuery.value = clean
        val updated = listOf(clean) + _searchHistory.value.filterNot { it.equals(clean, ignoreCase = true) }
        _searchHistory.value = updated.take(5)
    }

    fun selectSearchHistoryTerm(term: String) {
        val clean = term.trim()
        if (clean.isEmpty()) return
        _searchQuery.value = clean
        val updated = listOf(clean) + _searchHistory.value.filterNot { it.equals(clean, ignoreCase = true) }
        _searchHistory.value = updated.take(5)
    }

    fun removeSearchHistoryTerm(term: String) {
        _searchHistory.value = _searchHistory.value.filterNot { it.equals(term, ignoreCase = true) }
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
    }

    fun toggleSearchExpanded(expanded: Boolean? = null) {
        val next = expanded ?: !_isSearchExpanded.value
        _isSearchExpanded.value = next
        if (!next && _searchQuery.value.isNotBlank()) {
            commitSearchQuery(_searchQuery.value)
        }
    }

    /**
     * Auto-closes the search bar and dropdown menu while preserving the active search query
     * and search results on screen until the user navigates elsewhere in the app.
     */
    fun collapseSearchBar() {
        if (_searchQuery.value.isNotBlank()) {
            commitSearchQuery(_searchQuery.value)
        }
        _isSearchExpanded.value = false
    }

    /**
     * Completely closes the search bar and clears the active search results (called on navigation).
     */
    fun closeAndResetSearch() {
        if (_searchQuery.value.isNotBlank()) {
            commitSearchQuery(_searchQuery.value)
        }
        _isSearchExpanded.value = false
        _searchQuery.value = ""
    }

    fun selectTab(tab: MainTab) {
        if (_isSearchExpanded.value || _searchQuery.value.isNotBlank()) {
            closeAndResetSearch()
        }
        _selectedTab.value = tab
        if (tab != MainTab.MORE) {
            _moreDestination.value = MoreDestination.HUB
        }
    }

    fun navigateMore(destination: MoreDestination) {
        if (_isSearchExpanded.value || _searchQuery.value.isNotBlank()) {
            closeAndResetSearch()
        }
        _selectedTab.value = MainTab.MORE
        _moreDestination.value = destination
    }

    fun selectReportTab(tab: ReportTab?) {
        _selectedReportTab.value = tab
    }

    fun selectLedgerAccount(id: Long?) {
        _selectedLedgerAccountId.value = id
    }

    fun openAddTransactionSheet(open: Boolean) {
        if (open && (_isSearchExpanded.value || _searchQuery.value.isNotBlank())) {
            closeAndResetSearch()
        }
        _showAddTransactionSheet.value = open
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun dismissExportPreview() {
        _exportPreviewResult.value = null
    }

    fun attemptPinUnlock(enteredPin: String): Boolean {
        val s = uiState.value.settings
        val nowMs = System.currentTimeMillis()
        if (s.lockoutUntilEpochMillis > nowMs) {
            val remSec = ((s.lockoutUntilEpochMillis - nowMs + 999L) / 1000L).coerceAtLeast(1L)
            _pinLockoutErrorMessage.value = "Too many failed attempts. Try again in ${remSec}s."
            return false
        }
        val valid = PinSecurityUtil.verifyPin(enteredPin, s.pinHashSha256, s.pinSalt)
        viewModelScope.launch(Dispatchers.IO) {
            when (val res = repository.verifyAndMigratePinWithThrottling(enteredPin)) {
                is PinVerificationResult.Success -> {
                    _pinLockoutErrorMessage.value = null
                    _isPinUnlockedSession.value = true
                }
                is PinVerificationResult.LockedOut -> {
                    _pinLockoutErrorMessage.value = "PIN locked for ${res.remainingSeconds}s due to repeated failed attempts."
                }
                is PinVerificationResult.InvalidPin -> {
                    val waitSec = res.lockoutDurationMillis / 1000L
                    _pinLockoutErrorMessage.value = if (waitSec > 0L) {
                        "Incorrect PIN (${res.failedAttempts} failed attempts). Locked for ${waitSec}s."
                    } else {
                        "Incorrect PIN. Please try again (${res.failedAttempts}/3 before temporary lockout)."
                    }
                }
            }
        }
        if (valid) {
            _pinLockoutErrorMessage.value = null
            lastUserInteractionMillis.set(System.currentTimeMillis())
            backgroundEnteredAtMillis.set(0L)
            _isPinUnlockedSession.value = true
        }
        return valid
    }

    fun verifyCurrentPin(enteredPin: String): Boolean {
        val s = uiState.value.settings
        return PinSecurityUtil.verifyPin(enteredPin, s.pinHashSha256, s.pinSalt)
    }

    fun lockAppNow() {
        val s = uiState.value.settings
        if (!s.pinHashSha256.isNullOrBlank() || s.isBiometricEnabled) {
            _isPinUnlockedSession.value = false
        }
    }

    fun recordUserInteraction(nowMillis: Long = System.currentTimeMillis()) {
        lastUserInteractionMillis.set(nowMillis)
    }

    fun onAppBackgrounded(isChangingConfigurations: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (!isChangingConfigurations) {
            backgroundEnteredAtMillis.set(nowMillis)
        }
    }

    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        val bgAt = backgroundEnteredAtMillis.getAndSet(0L)
        val s = uiState.value.settings
        val hasSecurityLock = !s.pinHashSha256.isNullOrBlank() || s.isBiometricEnabled
        if (bgAt > 0L && hasSecurityLock && _isPinUnlockedSession.value) {
            val timeoutMillis = s.autoLockMinutes.coerceAtLeast(1) * 60_000L
            if (nowMillis - bgAt >= timeoutMillis) {
                lockAppNow()
                return
            }
        }
        lastUserInteractionMillis.set(nowMillis)
    }

    fun checkInactivityTimeout(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val s = uiState.value.settings
        val hasSecurityLock = !s.pinHashSha256.isNullOrBlank() || s.isBiometricEnabled
        if (!hasSecurityLock || !_isPinUnlockedSession.value) return false
        val timeoutMillis = s.autoLockMinutes.coerceAtLeast(1) * 60_000L
        val elapsed = nowMillis - lastUserInteractionMillis.get()
        if (elapsed >= timeoutMillis) {
            lockAppNow()
            return true
        }
        return false
    }

    fun togglePrivacyMode() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = uiState.value.settings.isPrivacyModeEnabled
            repository.togglePrivacyMode(!current)
        }
    }

    fun toggleDemoMode(enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setDemonstrationMode(enable)
            _statusBannerMessage.value = if (enable) {
                "Demonstration Mode enabled: Showing isolated [DEMO - FICTIONAL] records."
            } else {
                "Switched to your real Finance Vault records."
            }
        }
    }

    fun createAccount(
        name: String,
        accountType: AccountType,
        bankName: String,
        branchName: String,
        accountNumber: String,
        ifscCode: String,
        accountHolder: String,
        upiId: String,
        openingBalancePaise: Long,
        isOpeningBalanceRecorded: Boolean,
        creditLimitPaise: Long?,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.createAccount(
                    name = name,
                    accountType = accountType,
                    bankName = bankName,
                    branchName = branchName,
                    accountNumber = accountNumber,
                    ifscCode = ifscCode,
                    accountHolder = accountHolder,
                    upiId = upiId,
                    openingBalancePaise = openingBalancePaise,
                    isOpeningBalanceRecorded = isOpeningBalanceRecorded,
                    creditLimitPaise = creditLimitPaise,
                    notes = notes,
                    isDemo = uiState.value.settings.isDemoModeEnabled
                )
            }.onSuccess {
                _statusBannerMessage.value = "Account '$name' added and Chart of Accounts updated."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not add account: ${e.message}"
            }
        }
    }

    fun archiveAccount(accountId: Long, archive: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.archiveAccount(accountId, archive)
        }
    }

    fun recordTransaction(
        transactionType: TransactionType,
        dateEpochDay: Long,
        amountPaise: Long,
        principalComponentPaise: Long = 0L,
        interestComponentPaise: Long = 0L,
        chargesPaise: Long = 0L,
        costBasisDisposedPaise: Long = 0L,
        sourceAccountId: Long? = null,
        destinationAccountId: Long? = null,
        category: String,
        description: String,
        referenceNumber: String = "",
        notes: String = "",
        locationName: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        linkedInvestmentId: Long? = null,
        linkedLoanId: Long? = null,
        linkedGiftCardId: Long? = null,
        linkedOriginalTransactionId: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.recordTransaction(
                    transactionType = transactionType,
                    dateEpochDay = dateEpochDay,
                    amountPaise = amountPaise,
                    principalComponentPaise = principalComponentPaise,
                    interestComponentPaise = interestComponentPaise,
                    chargesPaise = chargesPaise,
                    costBasisDisposedPaise = costBasisDisposedPaise,
                    sourceAccountId = sourceAccountId,
                    destinationAccountId = destinationAccountId,
                    category = category,
                    description = description,
                    referenceNumber = referenceNumber,
                    notes = notes,
                    locationName = locationName,
                    latitude = latitude,
                    longitude = longitude,
                    linkedInvestmentId = linkedInvestmentId,
                    linkedLoanId = linkedLoanId,
                    linkedGiftCardId = linkedGiftCardId,
                    linkedOriginalTransactionId = linkedOriginalTransactionId,
                    isDemo = uiState.value.settings.isDemoModeEnabled
                )
            }.onSuccess {
                _showAddTransactionSheet.value = false
                _statusBannerMessage.value = "Transaction recorded & double-entry ledgers updated."
            }.onFailure { e ->
                _statusBannerMessage.value = "Validation error: ${e.message}"
            }
        }
    }

    fun reverseTransaction(transactionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.reverseTransaction(transactionId)
            }.onSuccess {
                _statusBannerMessage.value = "Reversal entry posted and balances updated."
            }.onFailure { e ->
                _statusBannerMessage.value = "Reversal failed: ${e.message}"
            }
        }
    }

    fun reverseTransactionsBulk(transactionIds: Set<Long>) {
        if (transactionIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.reverseTransactionsBulk(transactionIds)
            }.onSuccess { count ->
                _statusBannerMessage.value = if (count > 0) {
                    "Reversed $count transaction(s) in ledger history and updated balances."
                } else {
                    "Selected transaction(s) are already reversed."
                }
            }.onFailure { e ->
                _statusBannerMessage.value = "Bulk reversal failed: ${e.message}"
            }
        }
    }

    fun categorizeTransactionsBulk(transactionIds: Set<Long>, newCategory: String) {
        if (transactionIds.isEmpty() || newCategory.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.categorizeTransactionsBulk(transactionIds, newCategory)
            }.onSuccess { count ->
                _statusBannerMessage.value = "Categorized $count transaction(s) under '$newCategory'."
            }.onFailure { e ->
                _statusBannerMessage.value = "Bulk categorization failed: ${e.message}"
            }
        }
    }

    fun editTransaction(
        transactionId: Long,
        dateEpochDay: Long,
        amountPaise: Long,
        sourceAccountId: Long?,
        destinationAccountId: Long?,
        category: String,
        description: String,
        locationName: String = "",
        notes: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.editTransaction(
                    transactionId = transactionId,
                    dateEpochDay = dateEpochDay,
                    amountPaise = amountPaise,
                    sourceAccountId = sourceAccountId,
                    destinationAccountId = destinationAccountId,
                    category = category,
                    description = description,
                    locationName = locationName,
                    notes = notes
                )
            }.onSuccess {
                _statusBannerMessage.value = "Transaction updated & double-entry ledger reconciled."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not update transaction: ${e.message}"
            }
        }
    }

    fun addInvestment(
        name: String,
        category: InvestmentCategory,
        purchaseDateEpochDay: Long,
        quantity: Double,
        units: Double,
        purchasePricePerUnitPaise: Long,
        investedAmountPaise: Long,
        chargesPaise: Long,
        currentRecordedValuePaise: Long?,
        isCurrentValueRecorded: Boolean,
        paymentAccountId: Long?,
        annualInterestRatePercent: Double?,
        compoundingFrequency: CompoundingFrequency,
        durationMonths: Int?,
        notes: String,
        dividendsReceivedPaise: Long = 0L,
        interestEarnedPaise: Long = 0L
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val maturityEpoch = if (durationMonths != null && durationMonths > 0) {
                    purchaseDateEpochDay + (durationMonths * 30L)
                } else null
                repository.addInvestment(
                    name = name,
                    category = category,
                    purchaseDateEpochDay = purchaseDateEpochDay,
                    quantity = quantity,
                    units = units,
                    purchasePricePerUnitPaise = purchasePricePerUnitPaise,
                    investedAmountPaise = investedAmountPaise,
                    chargesPaise = chargesPaise,
                    currentRecordedValuePaise = currentRecordedValuePaise,
                    isCurrentValueRecorded = isCurrentValueRecorded,
                    paymentAccountId = paymentAccountId,
                    maturityDateEpochDay = maturityEpoch,
                    annualInterestRatePercent = annualInterestRatePercent,
                    compoundingFrequency = compoundingFrequency,
                    durationMonths = durationMonths,
                    notes = notes,
                    isDemo = uiState.value.settings.isDemoModeEnabled,
                    dividendsReceivedPaise = dividendsReceivedPaise,
                    interestEarnedPaise = interestEarnedPaise
                )
            }.onSuccess {
                _statusBannerMessage.value = "Investment '$name' recorded in portfolio & Balance Sheet."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not add investment: ${e.message}"
            }
        }
    }

    fun updateInvestmentValuation(
        investmentId: Long,
        recordedValuePaise: Long?,
        updatedUnits: Double? = null,
        updatedBuyPricePerUnitPaise: Long? = null,
        updatedAnnualRatePercent: Double? = null,
        updatedCompounding: CompoundingFrequency? = null,
        updatedDurationMonths: Int? = null,
        updatedDividendsPaise: Long? = null,
        updatedInterestPaise: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateInvestmentRecordedValue(
                investmentId = investmentId,
                newRecordedValuePaise = recordedValuePaise,
                updatedUnits = updatedUnits,
                updatedBuyPricePerUnitPaise = updatedBuyPricePerUnitPaise,
                updatedAnnualRatePercent = updatedAnnualRatePercent,
                updatedCompounding = updatedCompounding,
                updatedDurationMonths = updatedDurationMonths,
                updatedDividendsPaise = updatedDividendsPaise,
                updatedInterestPaise = updatedInterestPaise
            )
            _statusBannerMessage.value = "Investment valuation & growth rate synced to Balance Sheet."
        }
    }

    fun addLoan(
        name: String,
        loanType: LoanType,
        lenderName: String,
        principalPaise: Long,
        annualInterestRatePercent: Double,
        tenureMonths: Int,
        startDateEpochDay: Long,
        disbursedToAccountId: Long?,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.addLoan(
                    name = name,
                    loanType = loanType,
                    lenderName = lenderName,
                    principalPaise = principalPaise,
                    annualInterestRatePercent = annualInterestRatePercent,
                    tenureMonths = tenureMonths,
                    startDateEpochDay = startDateEpochDay,
                    disbursedToAccountId = disbursedToAccountId,
                    notes = notes,
                    isDemo = uiState.value.settings.isDemoModeEnabled
                )
            }.onSuccess {
                _statusBannerMessage.value = "Loan '$name' & EMI schedule added to liabilities."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not add loan: ${e.message}"
            }
        }
    }

    fun addBudget(
        name: String,
        budgetPeriod: BudgetPeriod,
        category: String,
        limitPaise: Long,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addBudget(
                name = name,
                budgetPeriod = budgetPeriod,
                category = category,
                limitPaise = limitPaise,
                financialYear = uiState.value.settings.activeFinancialYear,
                notes = notes,
                isDemo = uiState.value.settings.isDemoModeEnabled
            )
            _statusBannerMessage.value = "Budget '$name' saved."
        }
    }

    fun saveOverallMonthlyBudget(limitPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addBudget(
                name = "Overall Monthly Budget",
                budgetPeriod = BudgetPeriod.MONTHLY,
                category = "OVERALL",
                limitPaise = limitPaise,
                financialYear = uiState.value.settings.activeFinancialYear,
                notes = "",
                isDemo = uiState.value.settings.isDemoModeEnabled
            )
            _statusBannerMessage.value = "Overall monthly budget updated."
        }
    }

    fun addCustomOption(scopeKey: String, customValue: String) {
        val clean = customValue.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.addCustomCategoryOption(scopeKey, clean)
            _statusBannerMessage.value = "Added '$clean' to options."
        }
    }

    fun deleteBudget(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteBudget(id) }
    }

    fun addGoal(
        name: String,
        goalType: GoalType,
        targetAmountPaise: Long,
        currentSavedPaise: Long,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addGoal(
                name = name,
                goalType = goalType,
                targetAmountPaise = targetAmountPaise,
                currentSavedPaise = currentSavedPaise,
                notes = notes,
                isDemo = uiState.value.settings.isDemoModeEnabled
            )
            _statusBannerMessage.value = "Goal '$name' created."
        }
    }

    fun contributeToGoal(goalId: Long, amountPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.contributeToGoal(goalId, amountPaise)
            _statusBannerMessage.value = "Goal progress updated."
        }
    }

    fun deleteGoal(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteGoal(id) }
    }

    fun addGiftCard(
        brand: String,
        name: String,
        initialValuePaise: Long,
        remainingValuePaise: Long,
        cardNumberRaw: String,
        redemptionPinRaw: String,
        purchasedFromAccountId: Long?,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.addGiftCard(
                    brand = brand,
                    name = name,
                    initialValuePaise = initialValuePaise,
                    remainingValuePaise = remainingValuePaise,
                    purchaseDateEpochDay = IndianFinancialYear.todayEpochDay(),
                    cardNumberRaw = cardNumberRaw,
                    redemptionPinRaw = redemptionPinRaw,
                    purchasedFromAccountId = purchasedFromAccountId,
                    notes = notes,
                    isDemo = uiState.value.settings.isDemoModeEnabled
                )
            }.onSuccess {
                _statusBannerMessage.value = "Gift card '$brand' added to assets."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not add gift card: ${e.message}"
            }
        }
    }

    fun addSubscription(
        name: String,
        costPaise: Long,
        billingFrequency: BillingFrequency,
        nextRenewalEpochDay: Long,
        paymentAccountId: Long?,
        notes: String,
        customIntervalCount: Int = 1,
        customIntervalUnit: String = "MONTHS"
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addSubscription(
                name = name,
                costPaise = costPaise,
                billingFrequency = billingFrequency,
                nextRenewalEpochDay = nextRenewalEpochDay,
                paymentAccountId = paymentAccountId,
                notes = notes,
                isDemo = uiState.value.settings.isDemoModeEnabled,
                customIntervalCount = customIntervalCount,
                customIntervalUnit = customIntervalUnit
            )
            _statusBannerMessage.value = "Subscription '$name' scheduled for ${IndianFinancialYear.formatEpochDay(nextRenewalEpochDay)}."
        }
    }

    fun updateSubscriptionRenewalSchedule(
        subscriptionId: Long,
        nextRenewalEpochDay: Long,
        billingFrequency: BillingFrequency,
        customIntervalCount: Int = 1,
        customIntervalUnit: String = "MONTHS"
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSubscriptionRenewalSchedule(
                subscriptionId = subscriptionId,
                nextRenewalEpochDay = nextRenewalEpochDay,
                billingFrequency = billingFrequency,
                customIntervalCount = customIntervalCount,
                customIntervalUnit = customIntervalUnit
            )
            _statusBannerMessage.value = "Updated renewal date to ${IndianFinancialYear.formatEpochDay(nextRenewalEpochDay)}."
        }
    }

    fun recordSubscriptionPaymentNow(
        subscriptionId: Long,
        sourceAccountId: Long,
        nextCustomRenewalEpochDay: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.recordSubscriptionPaymentNow(
                    subscriptionId = subscriptionId,
                    sourceAccountId = sourceAccountId,
                    nextCustomRenewalEpochDay = nextCustomRenewalEpochDay
                )
            }.onSuccess {
                _statusBannerMessage.value = "Subscription payment recorded & next renewal updated."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not record payment: ${e.message}"
            }
        }
    }

    fun deleteSubscription(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteSubscription(id) }
    }

    fun openGeminiAssistantDialog(show: Boolean) {
        _showGeminiAssistantDialog.value = show
    }

    @Immutable
    data class AssistantFulfillmentPayload(
        val spokenReply: String,
        val netWorthFormatted: String,
        val totalBalanceFormatted: String,
        val monthlySpendingFormatted: String,
        val monthlyIncomeFormatted: String,
        val recordedTransactionsCount: Int,
        val errorMessage: String? = null
    )

    /**
     * Builds a Schema.org JSON-LD payload and populates Android AssistData Bundle so Google Assistant
     * can read relevant financial information directly when invoked by the user.
     */
    fun buildAssistContentJsonLd(): String {
        val s = uiState.value
        val isHidden = s.settings.isPrivacyModeEnabled
        val totalBalancePaise = s.accountsWithBalances
            .filter { !it.account.isArchived }
            .sumOf { it.closingBalancePaise }
        val totalBalanceStr = InrMoney.formatPaise(totalBalancePaise, isHidden = isHidden)
        val netWorthStr = InrMoney.formatPaise(s.balanceSheet.totalNetWorthPaise, isHidden = isHidden)
        val monthSpentStr = InrMoney.formatPaise(s.currentMonthTotalSpentPaise, isHidden = isHidden)
        val monthIncomeStr = InrMoney.formatPaise(s.monthlyProfitAndLoss.totalIncomePaise, isHidden = isHidden)

        val accountsList = s.accountsWithBalances
            .filter { !it.account.isArchived }
            .joinToString(", ") {
                "${it.account.name}: ${InrMoney.formatPaise(it.closingBalancePaise, isHidden = isHidden)}"
            }
            .ifBlank { "No active accounts" }

        return org.json.JSONObject().apply {
            put("@context", "https://schema.org")
            put("@type", "AccountingService")
            put("name", "Finance Vault")
            put("description", "Personal Financial Summary for ${s.settings.activeFinancialYear}")
            put("netWorth", netWorthStr)
            put("totalAccountBalance", totalBalanceStr)
            put("currentMonthSpending", monthSpentStr)
            put("currentMonthIncome", monthIncomeStr)
            put("accountsSummary", accountsList)
            put("activeSubscriptionsCount", s.subscriptions.size)
        }.toString()
    }

    fun populateAssistBundle(bundle: android.os.Bundle) {
        val s = uiState.value
        val isHidden = s.settings.isPrivacyModeEnabled
        val totalBalancePaise = s.accountsWithBalances
            .filter { !it.account.isArchived }
            .sumOf { it.closingBalancePaise }
        bundle.putString("app_name", "Finance Vault")
        bundle.putString("financial_year", s.settings.activeFinancialYear)
        bundle.putString("net_worth_inr", InrMoney.formatPaise(s.balanceSheet.totalNetWorthPaise, isHidden = isHidden))
        bundle.putString("total_balance_inr", InrMoney.formatPaise(totalBalancePaise, isHidden = isHidden))
        bundle.putString("monthly_spending_inr", InrMoney.formatPaise(s.currentMonthTotalSpentPaise, isHidden = isHidden))
        bundle.putString("monthly_income_inr", InrMoney.formatPaise(s.monthlyProfitAndLoss.totalIncomePaise, isHidden = isHidden))
        bundle.putInt("active_subscriptions_count", s.subscriptions.size)
    }

    fun dismissVoiceDisambiguation() {
        _pendingVoiceDisambiguation.value = null
    }

    fun dismissPendingAiConfirmation() {
        _pendingAiConfirmation.value = null
        _statusBannerMessage.value = "Cancelled pending AI operation."
    }

    private suspend fun executePendingConfirmationCommit(
        context: Context?,
        pending: PendingAiTransactionConfirmation
    ): String {
        if (pending.customCommitAction != null) {
            return pending.customCommitAction.invoke()
        }
        if (pending.operationType == "REVERSE_TRANSACTION" && pending.targetTransactionIdToReverse != null) {
            val targetTx = uiState.value.transactions.firstOrNull { it.id == pending.targetTransactionIdToReverse }
            repository.reverseTransaction(pending.targetTransactionIdToReverse)
            if (targetTx != null) {
                lastUndoableVoiceAction = UndoableVoiceAction.ReversedTransaction(
                    snapshot = targetTx,
                    description = "${InrMoney.formatPaise(targetTx.amountPaise)} (${targetTx.category})"
                )
            }
            return "Confirmed and reversed transaction of ${InrMoney.formatPaise(pending.amountPaise)} for ${pending.category}. Say Undo to restore it."
        }

        val resolvedSrcId = when {
            pending.sourceAccountId != null -> pending.sourceAccountId
            pending.transactionType in setOf(TransactionType.EXPENSE, TransactionType.TRANSFER) ->
                repository.resolveOrCreateAccountForVoice(pending.sourceAccountName, pending.isDemo).id
            else -> null
        }
        val resolvedDstId = when {
            pending.destinationAccountId != null && pending.destinationAccountId != resolvedSrcId -> pending.destinationAccountId
            pending.transactionType == TransactionType.INCOME ->
                repository.resolveOrCreateAccountForVoice(pending.destinationAccountName, pending.isDemo).id
            pending.transactionType == TransactionType.TRANSFER -> {
                val candidate = repository.resolveOrCreateAccountForVoice(
                    pending.destinationAccountName.ifBlank { "Cash Wallet" },
                    pending.isDemo
                )
                if (candidate.id == resolvedSrcId) {
                    repository.createAccount(
                        name = pending.destinationAccountName.ifBlank { "Secondary Account" },
                        accountType = AccountType.CASH,
                        openingBalancePaise = 0L,
                        isOpeningBalanceRecorded = false,
                        openingDateEpochDay = IndianFinancialYear.todayEpochDay(),
                        isDemo = pending.isDemo
                    )
                } else {
                    candidate.id
                }
            }
            else -> null
        }

        val txId = repository.recordTransaction(
            transactionType = pending.transactionType,
            dateEpochDay = pending.dateEpochDay,
            amountPaise = pending.amountPaise,
            sourceAccountId = resolvedSrcId,
            destinationAccountId = resolvedDstId,
            category = pending.category,
            description = pending.description,
            locationName = pending.locationName,
            latitude = pending.latitude,
            longitude = pending.longitude,
            isDemo = pending.isDemo
        )
        lastUndoableVoiceAction = UndoableVoiceAction.CreatedTransaction(
            transactionId = txId,
            description = "${InrMoney.formatPaise(pending.amountPaise)} in ${pending.category}"
        )
        if (context != null) {
            TransactionRecallReminderManager.dismissInAppRecallPrompt(context)
        }
        val accLabel = when (pending.transactionType) {
            TransactionType.INCOME -> "into ${pending.destinationAccountName}"
            TransactionType.TRANSFER -> "from ${pending.sourceAccountName} to ${pending.destinationAccountName}"
            else -> "from ${pending.sourceAccountName}"
        }
        return "Confirmed & posted ${pending.transactionType.userLabel.lowercase()} of ${InrMoney.formatPaise(pending.amountPaise)} (${pending.category}) $accLabel."
    }

    fun confirmPendingAiTransaction(
        context: Context,
        onFinished: (String) -> Unit = {}
    ) {
        val pending = _pendingAiConfirmation.value ?: return
        _pendingAiConfirmation.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                executePendingConfirmationCommit(context, pending)
            }.onSuccess { msg ->
                _statusBannerMessage.value = msg
                onFinished(msg)
            }.onFailure { e ->
                val err = "Could not complete operation: ${e.message}"
                _statusBannerMessage.value = err
                onFinished(err)
            }
        }
    }

    private fun stageTransactionReversalPreview(targetTx: TransactionEntity): String {
        val currentState = uiState.value
        val srcAccName = targetTx.sourceAccountId?.let { id ->
            currentState.accountsWithBalances.firstOrNull { it.account.id == id }?.account?.name
        }.orEmpty()
        val dstAccName = targetTx.destinationAccountId?.let { id ->
            currentState.accountsWithBalances.firstOrNull { it.account.id == id }?.account?.name
        }.orEmpty()
        _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
            transactionType = targetTx.transactionType,
            dateEpochDay = targetTx.dateEpochDay,
            amountPaise = targetTx.amountPaise,
            sourceAccountId = targetTx.sourceAccountId,
            sourceAccountName = srcAccName,
            destinationAccountId = targetTx.destinationAccountId,
            destinationAccountName = dstAccName,
            category = targetTx.category,
            description = targetTx.description,
            locationName = targetTx.locationName,
            latitude = targetTx.latitude,
            longitude = targetTx.longitude,
            isDemo = targetTx.isDemo,
            operationType = "REVERSE_TRANSACTION",
            operationTitle = "Confirm Transaction Reversal",
            targetTransactionIdToReverse = targetTx.id
        )
        return "Prepared reversal for ${InrMoney.formatPaise(targetTx.amountPaise)} (${targetTx.category}). Please confirm on screen or say Confirm."
    }

    private fun promptForMissingVoiceAmount(
        title: String,
        questionPrompt: String,
        quickAmountsPaise: List<Long> = listOf(100_00L, 500_00L, 1_000_00L, 5_000_00L, 10_000_00L),
        onAmountProvided: suspend (Long) -> String
    ): Pair<String, Boolean> {
        val options = quickAmountsPaise.mapIndexed { idx, paise ->
            VoiceDisambiguationOption(
                index = idx + 1,
                label = InrMoney.formatPaise(paise),
                subtitle = "Quick amount option",
                entityId = paise
            )
        }
        _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
            title = title,
            questionPrompt = questionPrompt,
            options = options,
            onSelectOption = { chosen ->
                onAmountProvided(chosen.entityId)
            },
            onSpokenAmountProvided = { spokenPaise ->
                onAmountProvided(spokenPaise)
            }
        )
        return questionPrompt to true
    }

    private fun resolveAccountCandidateForPreview(
        spokenHint: String,
        currentState: FinanceVaultUiState
    ): Pair<Long?, String> {
        val activeAccs = currentState.accountsWithBalances.map { it.account }.filter { !it.isArchived }
        val cleanHint = spokenHint.trim()
        if (cleanHint.isNotBlank()) {
            val matched = activeAccs.firstOrNull {
                it.name.contains(cleanHint, ignoreCase = true) ||
                    it.bankName.contains(cleanHint, ignoreCase = true) ||
                    it.accountType.displayName.contains(cleanHint, ignoreCase = true)
            }
            if (matched != null) return matched.id to matched.name
            return null to cleanHint
        }
        val first = activeAccs.firstOrNull()
        return if (first != null) first.id to first.name else null to "Primary Account"
    }

    fun cancelActiveVoiceCommand() {
        activeVoiceCommandJob?.cancel()
        activeVoiceCommandJob = null
        _pendingVoiceDisambiguation.value = null
    }

    fun selectVoiceDisambiguationOption(
        option: VoiceDisambiguationOption,
        onFinished: (String, String?, Boolean) -> Unit = { _, _, _ -> }
    ) {
        val pending = _pendingVoiceDisambiguation.value ?: return
        _pendingVoiceDisambiguation.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                pending.onSelectOption(option)
            }.onSuccess { reply ->
                _statusBannerMessage.value = reply
                val hasNextQuestion = _pendingVoiceDisambiguation.value != null
                onFinished(reply, null, hasNextQuestion)
            }.onFailure { e ->
                val err = "Could not complete selected option: ${e.message}"
                _statusBannerMessage.value = err
                onFinished(err, e.localizedMessage, false)
            }
        }
    }

    private fun matchDisambiguationOptionFromSpeech(
        utterance: String,
        options: List<VoiceDisambiguationOption>
    ): VoiceDisambiguationOption? {
        val lower = utterance.lowercase(java.util.Locale.US).trim()
        // Check explicit option numbers or ordinals ("1", "option 1", "first", "number one")
        val numberWords = mapOf(
            "1" to 1, "one" to 1, "first" to 1, "1st" to 1,
            "2" to 2, "two" to 2, "second" to 2, "2nd" to 2,
            "3" to 3, "three" to 3, "third" to 3, "3rd" to 3,
            "4" to 4, "four" to 4, "fourth" to 4, "4th" to 4,
            "5" to 5, "five" to 5, "fifth" to 5, "5th" to 5
        )
        for ((word, idx) in numberWords) {
            if (lower == word || lower == "option $word" || lower == "number $word" || lower == "the $word one") {
                options.firstOrNull { it.index == idx }?.let { return it }
            }
        }
        // Check label substring match
        for (opt in options) {
            val optLower = opt.label.lowercase(java.util.Locale.US)
            if (lower.contains(optLower) || optLower.contains(lower)) {
                return opt
            }
            val optTokens = optLower.split(" ").filter { it.length >= 3 }
            if (optTokens.any { token -> lower.contains(token) }) {
                return opt
            }
        }
        return null
    }

    private fun applyNavigationTargets(
        tab: MainTab?,
        more: MoreDestination?,
        report: ReportTab?,
        ledgerHint: String,
        currentState: FinanceVaultUiState
    ) {
        if (report != null) {
            _selectedTab.value = MainTab.MORE
            _moreDestination.value = MoreDestination.REPORTS
            _selectedReportTab.value = report
            if (report == ReportTab.ACCOUNT_LEDGER && ledgerHint.isNotBlank()) {
                val matchedLedger = currentState.ledgerAccounts.firstOrNull {
                    it.name.contains(ledgerHint, ignoreCase = true)
                }
                if (matchedLedger != null) {
                    _selectedLedgerAccountId.value = matchedLedger.id
                }
            }
        } else if (more != null) {
            _selectedTab.value = MainTab.MORE
            _moreDestination.value = more
        } else if (tab != null) {
            _selectedTab.value = tab
        }
    }

    /**
     * Evaluates a parsed voice transaction and sequentially prompts the user for any missing
     * required slot (1. Amount, 2. Category, 3. Account when multiple options exist) before
     * committing the transaction with live high-precision GPS street, area, and coordinates.
     *
     * @return Pair(spokenReply, needsFollowUpMicrophone)
     */
    private suspend fun processVoiceTransactionWithSlotFilling(
        context: Context,
        utterance: String,
        tx: com.example.domain.ai.ParsedVoiceTransaction,
        capturedGps: com.example.domain.location.CapturedLocation?,
        isDemo: Boolean
    ): Pair<String, Boolean> {
        val currentState = uiState.value
        val activeAccounts = currentState.accountsWithBalances
            .map { it.account }
            .filter { !it.isArchived }

        val pattern = TransactionPatternAnalyzer.analyzeForTransaction(
            utterance = utterance,
            fallbackType = tx.transactionType,
            fallbackCategory = tx.category,
            explicitLocationName = tx.locationName,
            explicitSourceAccountHint = tx.sourceAccountHint,
            explicitDestinationAccountHint = tx.destinationAccountHint,
            transactions = currentState.transactions,
            activeAccounts = activeAccounts
        )

        val resolvedType = pattern.inferredTransactionType
        val isCategoryFulfilled = tx.isCategoryExplicit ||
            pattern.isCategoryAutoSelectedFromHistory ||
            resolvedType == TransactionType.TRANSFER
        val resolvedCategory = pattern.inferredCategory
        val merchantOrPlace = tx.locationName.ifBlank { pattern.inferredLocationName }
        val resolvedLocation = LocationHelper.mergeLocationWithLiveGps(merchantOrPlace, capturedGps)

        // Clean description without any voice transcript clutter
        val cleanDescription = if (tx.description.isBlank() || tx.description.startsWith("Voice ", ignoreCase = true)) {
            GeminiVaultAssistant.buildCleanNarration(utterance, resolvedCategory, merchantOrPlace, resolvedType)
        } else {
            tx.description
        }

        // SLOT 1: Missing Amount -> Ask the user how much the transaction was for!
        if (tx.amountPaise <= 0L) {
            val quickAmounts = listOf(100_00L, 250_00L, 500_00L, 1_000_00L, 2_500_00L)
            val options = quickAmounts.mapIndexed { idx, paise ->
                VoiceDisambiguationOption(
                    index = idx + 1,
                    label = InrMoney.formatPaise(paise),
                    subtitle = "Quick amount option",
                    entityId = paise
                )
            }
            val typeWord = when (resolvedType) {
                TransactionType.INCOME -> "income"
                TransactionType.TRANSFER -> "transfer"
                else -> "expense"
            }
            val catHint = if (isCategoryFulfilled) " for $resolvedCategory" else ""
            val question = "How much was this $typeWord$catHint? Please say the amount in rupees or pick an option."

            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                title = "Enter Amount for ${resolvedType.userLabel}",
                questionPrompt = question,
                options = options,
                onSelectOption = { chosen ->
                    val updatedTx = tx.copy(amountPaise = chosen.entityId)
                    processVoiceTransactionWithSlotFilling(
                        context = context,
                        utterance = utterance,
                        tx = updatedTx,
                        capturedGps = capturedGps,
                        isDemo = isDemo
                    ).first
                },
                onSpokenAmountProvided = { spokenPaise ->
                    val updatedTx = tx.copy(amountPaise = spokenPaise)
                    processVoiceTransactionWithSlotFilling(
                        context = context,
                        utterance = utterance,
                        tx = updatedTx,
                        capturedGps = capturedGps,
                        isDemo = isDemo
                    ).first
                }
            )
            return question to true
        }

        // SLOT 2: Missing / Unfulfilled Category -> Ask the user which category to use!
        if (!isCategoryFulfilled) {
            val customCats = currentState.customOptionsMap["TX_CATEGORY"].orEmpty()
            val candidateCategories = if (resolvedType == TransactionType.INCOME) {
                (listOf("Salary Income", "Business & Freelance", "Rental Income", "Interest Income", "Refund & Cashback") + customCats).distinct().take(6)
            } else {
                (listOf(
                    "Food & Dining",
                    "Household & Groceries",
                    "Transportation & Fuel",
                    "Utilities & Recharge",
                    "Shopping & Lifestyle",
                    "Healthcare & Medical"
                ) + customCats).distinct().take(6)
            }
            val options = candidateCategories.mapIndexed { idx, cat ->
                VoiceDisambiguationOption(
                    index = idx + 1,
                    label = cat,
                    subtitle = "${resolvedType.userLabel} Category",
                    entityId = (idx + 1).toLong()
                )
            }
            val amtFormatted = InrMoney.formatPaise(tx.amountPaise)
            val optionsSpoken = options.take(5).joinToString(", or ") { "${it.index}. ${it.label}" }
            val question = "Which category should I use for $amtFormatted: $optionsSpoken?"

            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                title = "Select Category for $amtFormatted",
                questionPrompt = question,
                options = options,
                onSelectOption = { chosen ->
                    val chosenCat = chosen.label
                    val updatedDescription = GeminiVaultAssistant.buildCleanNarration(
                        utterance,
                        chosenCat,
                        merchantOrPlace,
                        resolvedType
                    )
                    val updatedTx = tx.copy(
                        category = chosenCat,
                        description = updatedDescription,
                        isCategoryExplicit = true
                    )
                    processVoiceTransactionWithSlotFilling(
                        context = context,
                        utterance = utterance,
                        tx = updatedTx,
                        capturedGps = capturedGps,
                        isDemo = isDemo
                    ).first
                }
            )
            return question to true
        }

        // SLOT 3: Multiple Account Options with no single learned/explicit account -> Ask which account to use!
        if (pattern.requiresAccountDisambiguation && pattern.candidateAccounts.size >= 2) {
            val options = pattern.candidateAccounts.take(5).mapIndexed { idx, acc ->
                val bal = currentState.accountsWithBalances.firstOrNull { it.account.id == acc.id }?.closingBalancePaise ?: 0L
                VoiceDisambiguationOption(
                    index = idx + 1,
                    label = acc.name,
                    subtitle = "${acc.accountType.displayName} • ${InrMoney.formatPaise(bal)}",
                    entityId = acc.id
                )
            }
            val optionsSpokenList = options.joinToString(", or ") { "${it.index}. ${it.label}" }
            val amtFormatted = InrMoney.formatPaise(tx.amountPaise)
            val question = "Which account should I use for $amtFormatted ($resolvedCategory): $optionsSpokenList?"

            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                title = "Select Account for $amtFormatted ($resolvedCategory)",
                questionPrompt = question,
                options = options,
                onSelectOption = { chosen ->
                    val liveGpsNow = if (uiState.value.settings.isLocationTaggingEnabled) {
                        capturedGps ?: withTimeoutOrNull(2_500L) {
                            LocationHelper.fetchCurrentLocation(context)
                        }
                    } else null
                    val finalLocation = LocationHelper.mergeLocationWithLiveGps(merchantOrPlace, liveGpsNow)
                    val pendingPreview = if (resolvedType == TransactionType.INCOME) {
                        PendingAiTransactionConfirmation(
                            transactionType = TransactionType.INCOME,
                            dateEpochDay = IndianFinancialYear.todayEpochDay(),
                            amountPaise = tx.amountPaise,
                            sourceAccountId = null,
                            sourceAccountName = "",
                            destinationAccountId = chosen.entityId,
                            destinationAccountName = chosen.label,
                            category = resolvedCategory,
                            description = cleanDescription,
                            locationName = finalLocation,
                            latitude = liveGpsNow?.latitude,
                            longitude = liveGpsNow?.longitude,
                            isDemo = isDemo
                        )
                    } else {
                        PendingAiTransactionConfirmation(
                            transactionType = TransactionType.EXPENSE,
                            dateEpochDay = IndianFinancialYear.todayEpochDay(),
                            amountPaise = tx.amountPaise,
                            sourceAccountId = chosen.entityId,
                            sourceAccountName = chosen.label,
                            destinationAccountId = null,
                            destinationAccountName = "",
                            category = resolvedCategory,
                            description = cleanDescription,
                            locationName = finalLocation,
                            latitude = liveGpsNow?.latitude,
                            longitude = liveGpsNow?.longitude,
                            isDemo = isDemo
                        )
                    }
                    _pendingAiConfirmation.value = pendingPreview
                    "Ready to record ${if (resolvedType == TransactionType.INCOME) "income" else "expense"} of $amtFormatted for $resolvedCategory (${chosen.label}). Tap Confirm on screen or say Confirm."
                }
            )
            return question to true
        }

        // ALL SLOTS FULFILLED: Present the AI Transaction Confirmation Preview Card for explicit user verification!
        val liveGpsNow = if (currentState.settings.isLocationTaggingEnabled) {
            capturedGps ?: withTimeoutOrNull(2_500L) {
                LocationHelper.fetchCurrentLocation(context)
            }
        } else null
        val finalLocation = LocationHelper.mergeLocationWithLiveGps(merchantOrPlace, liveGpsNow)

        val confirmation = runCatching {
            when (resolvedType) {
                TransactionType.INCOME -> {
                    val (dstId, dstName) = pattern.preferredDestinationAccount?.let { it.id to it.name }
                        ?: resolveAccountCandidateForPreview(
                            spokenHint = tx.destinationAccountHint.ifBlank { tx.sourceAccountHint },
                            currentState = currentState
                        )
                    _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                        transactionType = TransactionType.INCOME,
                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                        amountPaise = tx.amountPaise,
                        sourceAccountId = null,
                        sourceAccountName = "",
                        destinationAccountId = dstId,
                        destinationAccountName = dstName,
                        category = resolvedCategory,
                        description = cleanDescription,
                        locationName = finalLocation,
                        latitude = liveGpsNow?.latitude,
                        longitude = liveGpsNow?.longitude,
                        isDemo = isDemo
                    )
                    "Prepared income of ${InrMoney.formatPaise(tx.amountPaise)} for $resolvedCategory into $dstName. Please confirm on screen or say Confirm."
                }

                TransactionType.TRANSFER -> {
                    val (srcId, srcName) = pattern.preferredSourceAccount?.let { it.id to it.name }
                        ?: resolveAccountCandidateForPreview(tx.sourceAccountHint, currentState)
                    val (rawDstId, rawDstName) = pattern.preferredDestinationAccount?.let { it.id to it.name }
                        ?: resolveAccountCandidateForPreview(tx.destinationAccountHint.ifBlank { "Cash Wallet" }, currentState)
                    val finalDstId = if (rawDstId != null && rawDstId == srcId) null else rawDstId
                    val finalDstName = if (rawDstId != null && rawDstId == srcId) {
                        tx.destinationAccountHint.ifBlank { "Secondary Account" }
                    } else {
                        rawDstName
                    }
                    _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                        transactionType = TransactionType.TRANSFER,
                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                        amountPaise = tx.amountPaise,
                        sourceAccountId = srcId,
                        sourceAccountName = srcName,
                        destinationAccountId = finalDstId,
                        destinationAccountName = finalDstName,
                        category = "Account Transfer",
                        description = cleanDescription,
                        locationName = finalLocation,
                        latitude = liveGpsNow?.latitude,
                        longitude = liveGpsNow?.longitude,
                        isDemo = isDemo
                    )
                    "Prepared transfer of ${InrMoney.formatPaise(tx.amountPaise)} from $srcName to $finalDstName. Please confirm on screen or say Confirm."
                }

                else -> {
                    val (srcId, srcName) = pattern.preferredSourceAccount?.let { it.id to it.name }
                        ?: resolveAccountCandidateForPreview(tx.sourceAccountHint, currentState)
                    _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                        transactionType = TransactionType.EXPENSE,
                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                        amountPaise = tx.amountPaise,
                        sourceAccountId = srcId,
                        sourceAccountName = srcName,
                        destinationAccountId = null,
                        destinationAccountName = "",
                        category = resolvedCategory,
                        description = cleanDescription,
                        locationName = finalLocation,
                        latitude = liveGpsNow?.latitude,
                        longitude = liveGpsNow?.longitude,
                        isDemo = isDemo
                    )
                    "Prepared expense of ${InrMoney.formatPaise(tx.amountPaise)} for $resolvedCategory from $srcName. Please confirm on screen or say Confirm."
                }
            }
        }.getOrElse { e ->
            "Could not prepare transaction: ${e.message}"
        }

        return confirmation to false
    }

    /**
     * Full-App Omni-Voice & Google Assistant Command Executor.
     * Supports:
     * 1. Resolving pending multi-option disambiguation questions by voice.
     * 2. Smart Historical Transaction Pattern Learning (auto-selecting account, category, and location
     *    when similar transactions occurred previously at different amounts/times).
     * 3. Interactive Multi-Option Disambiguation when multiple accounts/subscriptions/loans/goals exist.
     * 4. Full navigation and entity creation across every screen and module, plus instant Voice Undo.
     */
    fun executeVoiceOrGeminiCommand(
        context: Context,
        utterance: String,
        onFinished: (String, String?, Boolean) -> Unit = { _, _, _ -> },
        onAssistantFulfillment: (AssistantFulfillmentPayload) -> Unit = {}
    ) {
        activeVoiceCommandJob?.cancel()
        activeVoiceCommandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmedUtterance = utterance.trim()
                val lowerUtterance = trimmedUtterance.lowercase(java.util.Locale.US)

                // STEP 0: Check if there is a pending AI Transaction Confirmation Card and the user spoke "Confirm" or "Cancel"
                val activeConfirmation = _pendingAiConfirmation.value
                if (activeConfirmation != null) {
                    if (lowerUtterance == "confirm" || lowerUtterance == "yes" || lowerUtterance == "post" || lowerUtterance == "post it" || lowerUtterance == "save" || lowerUtterance == "confirm transaction") {
                        _pendingAiConfirmation.value = null
                        val msg = executePendingConfirmationCommit(context, activeConfirmation)
                        _statusBannerMessage.value = msg
                        onFinished(msg, null, false)
                        return@launch
                    } else if (lowerUtterance == "cancel" || lowerUtterance == "no" || lowerUtterance == "discard") {
                        _pendingAiConfirmation.value = null
                        val cancelMsg = "Cancelled pending AI operation."
                        _statusBannerMessage.value = cancelMsg
                        onFinished(cancelMsg, null, false)
                        return@launch
                    }
                }

                // STEP 1: Check if we are waiting for the user to answer a Multi-Option or Missing-Slot question!
                val activeDisambiguation = _pendingVoiceDisambiguation.value
                if (activeDisambiguation != null) {
                    if (lowerUtterance == "cancel" || lowerUtterance == "never mind" || lowerUtterance == "stop") {
                        _pendingVoiceDisambiguation.value = null
                        val cancelMsg = "Cancelled pending selection."
                        _statusBannerMessage.value = cancelMsg
                        onFinished(cancelMsg, null, false)
                        return@launch
                    }

                    // If we are waiting for a missing amount and the user spoke any rupee amount, fulfill it directly!
                    val spokenAmtPaise = GeminiVaultAssistant.extractAmountPaiseFromEnglish(trimmedUtterance)
                    if (activeDisambiguation.onSpokenAmountProvided != null && spokenAmtPaise != null && spokenAmtPaise > 0L) {
                        _pendingVoiceDisambiguation.value = null
                        val reply = runCatching {
                            activeDisambiguation.onSpokenAmountProvided.invoke(spokenAmtPaise)
                        }.getOrElse { e ->
                            "Could not record amount: ${e.message}"
                        }
                        _statusBannerMessage.value = reply
                        val hasNextFollowUp = _pendingVoiceDisambiguation.value != null
                        onFinished(reply, null, hasNextFollowUp)
                        return@launch
                    }

                    val matchedOption = matchDisambiguationOptionFromSpeech(trimmedUtterance, activeDisambiguation.options)
                    if (matchedOption != null) {
                        _pendingVoiceDisambiguation.value = null
                        val reply = runCatching {
                            activeDisambiguation.onSelectOption(matchedOption)
                        }.getOrElse { e ->
                            "Could not complete option: ${e.message}"
                        }
                        _statusBannerMessage.value = reply
                        val hasNextFollowUp = _pendingVoiceDisambiguation.value != null
                        onFinished(reply, null, hasNextFollowUp)
                        return@launch
                    } else {
                        val wordsCount = trimmedUtterance.split(Regex("\\s+")).size
                        if (wordsCount <= 4 && spokenAmtPaise == null &&
                            !Regex("""\b(?:delete|remove|open|show|go|create|add|undo)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerUtterance)
                        ) {
                            val retryPrompt = if (activeDisambiguation.onSpokenAmountProvided != null) {
                                "Please say the amount in rupees, or pick one of the options: " +
                                    activeDisambiguation.options.joinToString(", ") { it.label } + "."
                            } else {
                                "Please choose one of the options: " +
                                    activeDisambiguation.options.joinToString(", ") { "${it.index}. ${it.label}" } +
                                    ", or say Cancel."
                            }
                            _statusBannerMessage.value = retryPrompt
                            onFinished(retryPrompt, null, true)
                            return@launch
                        } else {
                            _pendingVoiceDisambiguation.value = null
                        }
                    }
                }

                // STEP 2: Interpret the Omni-Voice command
                val currentState = uiState.value
                val isDemo = currentState.settings.isDemoModeEnabled
                val result = GeminiVaultAssistant.interpretVoiceOrGeminiCommand(
                    utterance = trimmedUtterance,
                    uiState = currentState
                )

                // Apply any screen navigation requested or implied by the command
                applyNavigationTargets(
                    tab = result.targetTab,
                    more = result.targetMore,
                    report = result.targetReport,
                    ledgerHint = result.targetLedgerAccountHint,
                    currentState = currentState
                )

                var finalSpokenReply = result.spokenReply
                var listenForFollowUp = false

                when (result.action) {
                    "RECORD_TRANSACTION" -> {
                        val capturedGps = if (currentState.settings.isLocationTaggingEnabled) {
                            withTimeoutOrNull(4_200L) {
                                LocationHelper.fetchCurrentLocation(context)
                            }
                        } else null
                        for (tx in result.transactionsToRecord) {
                            val (replyText, needsFollowUp) = processVoiceTransactionWithSlotFilling(
                                context = context,
                                utterance = trimmedUtterance,
                                tx = tx,
                                capturedGps = capturedGps,
                                isDemo = isDemo
                            )
                            finalSpokenReply = replyText
                            if (needsFollowUp) {
                                listenForFollowUp = true
                                break
                            }
                        }
                    }

                    "DELETE_TRANSACTION", "REVERSE_LAST_TRANSACTION" -> {
                        val activeTxs = currentState.transactions.filter { !it.isReversed && it.isDemo == isDemo }
                        if (activeTxs.isEmpty()) {
                            finalSpokenReply = "There are no active transactions to delete."
                        } else {
                            val keyword = result.entityName.trim()
                            val amtFilter = result.primaryAmountPaise
                            val isExplicitLatest = result.action == "REVERSE_LAST_TRANSACTION" ||
                                result.frequencyOrTypeHint.equals("LATEST", ignoreCase = true)

                            if (isExplicitLatest && keyword.isBlank() && amtFilter <= 0L) {
                                val targetTx = activeTxs.first()
                                finalSpokenReply = stageTransactionReversalPreview(targetTx)
                            } else if (keyword.isBlank() && amtFilter <= 0L) {
                                // Ambiguous delete request without keyword, amount, or explicit "latest"
                                val options = activeTxs.take(5).mapIndexed { idx, tx ->
                                    val place = if (tx.locationName.isNotBlank()) " @ ${tx.locationName}" else ""
                                    VoiceDisambiguationOption(
                                        index = idx + 1,
                                        label = "${InrMoney.formatPaise(tx.amountPaise)} • ${tx.category}$place",
                                        subtitle = IndianFinancialYear.formatEpochDay(tx.dateEpochDay),
                                        entityId = tx.id
                                    )
                                }
                                val q = "Which transaction would you like to reverse: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                                _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                    title = "Select Transaction to Reverse",
                                    questionPrompt = q,
                                    options = options,
                                    onSelectOption = { chosen ->
                                        val tx = uiState.value.transactions.firstOrNull { it.id == chosen.entityId && !it.isReversed }
                                            ?: throw IllegalArgumentException("Transaction not found.")
                                        stageTransactionReversalPreview(tx)
                                    }
                                )
                                finalSpokenReply = q
                                listenForFollowUp = true
                            } else {
                                // Filter transactions BEFORE deciding whether a match is unique!
                                val matchedTxs = activeTxs.filter { tx ->
                                    val amtMatches = amtFilter <= 0L || tx.amountPaise == amtFilter
                                    val keywordMatches = keyword.isBlank() ||
                                        tx.category.contains(keyword, ignoreCase = true) ||
                                        tx.description.contains(keyword, ignoreCase = true) ||
                                        tx.locationName.contains(keyword, ignoreCase = true) ||
                                        keyword.split(" ").filter { it.length >= 3 }.any { tok ->
                                            tx.category.contains(tok, ignoreCase = true) ||
                                                tx.description.contains(tok, ignoreCase = true) ||
                                                tx.locationName.contains(tok, ignoreCase = true)
                                        }
                                    amtMatches && keywordMatches
                                }

                                when {
                                    matchedTxs.size == 1 -> {
                                        val targetTx = matchedTxs.first()
                                        finalSpokenReply = stageTransactionReversalPreview(targetTx)
                                    }
                                    matchedTxs.size > 1 -> {
                                        if (isExplicitLatest) {
                                            val targetTx = matchedTxs.first()
                                            finalSpokenReply = stageTransactionReversalPreview(targetTx)
                                        } else {
                                            val options = matchedTxs.take(5).mapIndexed { idx, tx ->
                                                val place = if (tx.locationName.isNotBlank()) " @ ${tx.locationName}" else ""
                                                VoiceDisambiguationOption(
                                                    index = idx + 1,
                                                    label = "${InrMoney.formatPaise(tx.amountPaise)} • ${tx.category}$place",
                                                    subtitle = IndianFinancialYear.formatEpochDay(tx.dateEpochDay),
                                                    entityId = tx.id
                                                )
                                            }
                                            val q = "Multiple transactions match. Which one should I reverse: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                                title = "Select Transaction to Reverse",
                                                questionPrompt = q,
                                                options = options,
                                                onSelectOption = { chosen ->
                                                    val tx = uiState.value.transactions.firstOrNull { it.id == chosen.entityId && !it.isReversed }
                                                        ?: throw IllegalArgumentException("Transaction not found.")
                                                    stageTransactionReversalPreview(tx)
                                                }
                                            )
                                            finalSpokenReply = q
                                            listenForFollowUp = true
                                        }
                                    }
                                    else -> {
                                        // No reliable match exists: NEVER delete an unrelated transaction! Ask for clarification.
                                        val options = activeTxs.take(5).mapIndexed { idx, tx ->
                                            val place = if (tx.locationName.isNotBlank()) " @ ${tx.locationName}" else ""
                                            VoiceDisambiguationOption(
                                                index = idx + 1,
                                                label = "${InrMoney.formatPaise(tx.amountPaise)} • ${tx.category}$place",
                                                subtitle = IndianFinancialYear.formatEpochDay(tx.dateEpochDay),
                                                entityId = tx.id
                                            )
                                        }
                                        val mismatchCriterion = when {
                                            keyword.isNotBlank() && amtFilter > 0L -> "'$keyword' for ${InrMoney.formatPaise(amtFilter)}"
                                            keyword.isNotBlank() -> "'$keyword'"
                                            else -> InrMoney.formatPaise(amtFilter)
                                        }
                                        val q = "No transaction matched $mismatchCriterion. Which transaction would you like to reverse: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                                        _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                            title = "No Match for $mismatchCriterion — Select Transaction",
                                            questionPrompt = q,
                                            options = options,
                                            onSelectOption = { chosen ->
                                                val tx = uiState.value.transactions.firstOrNull { it.id == chosen.entityId && !it.isReversed }
                                                    ?: throw IllegalArgumentException("Transaction not found.")
                                                stageTransactionReversalPreview(tx)
                                            }
                                        )
                                        finalSpokenReply = q
                                        listenForFollowUp = true
                                    }
                                }
                            }
                        }
                    }

                    "UNDO_LAST_ACTION" -> {
                        val undoable = lastUndoableVoiceAction
                        lastUndoableVoiceAction = null
                        when (undoable) {
                            is UndoableVoiceAction.CreatedTransaction -> {
                                repository.reverseTransaction(undoable.transactionId)
                                finalSpokenReply = "Undid transaction: ${undoable.description}."
                            }
                            is UndoableVoiceAction.ReversedTransaction -> {
                                val snap = undoable.snapshot
                                repository.recordTransaction(
                                    transactionType = snap.transactionType,
                                    dateEpochDay = snap.dateEpochDay,
                                    amountPaise = snap.amountPaise,
                                    principalComponentPaise = snap.principalComponentPaise,
                                    interestComponentPaise = snap.interestComponentPaise,
                                    chargesPaise = snap.chargesPaise,
                                    costBasisDisposedPaise = snap.costBasisDisposedPaise,
                                    sourceAccountId = snap.sourceAccountId,
                                    destinationAccountId = snap.destinationAccountId,
                                    category = snap.category,
                                    description = snap.description,
                                    referenceNumber = snap.referenceNumber,
                                    notes = snap.notes,
                                    locationName = snap.locationName,
                                    latitude = snap.latitude,
                                    longitude = snap.longitude,
                                    isDemo = snap.isDemo
                                )
                                finalSpokenReply = "Restored transaction: ${undoable.description}."
                            }
                            is UndoableVoiceAction.CreatedSubscription -> {
                                repository.deleteSubscription(undoable.subscriptionId)
                                finalSpokenReply = "Removed subscription: ${undoable.description}."
                            }
                            is UndoableVoiceAction.DeletedSubscription -> {
                                val sub = undoable.snapshot
                                repository.addSubscription(
                                    name = sub.name,
                                    costPaise = sub.costPaise,
                                    billingFrequency = sub.billingFrequency,
                                    nextRenewalEpochDay = sub.nextRenewalEpochDay,
                                    paymentAccountId = sub.paymentAccountId,
                                    notes = sub.notes,
                                    isDemo = sub.isDemo,
                                    customIntervalCount = sub.customIntervalCount,
                                    customIntervalUnit = sub.customIntervalUnit
                                )
                                finalSpokenReply = "Restored subscription: ${undoable.description}."
                            }
                            is UndoableVoiceAction.CreatedBudget -> {
                                repository.deleteBudget(undoable.budgetId)
                                finalSpokenReply = "Removed budget: ${undoable.description}."
                            }
                            is UndoableVoiceAction.DeletedBudget -> {
                                val b = undoable.snapshot
                                repository.addBudget(
                                    name = b.name,
                                    budgetPeriod = b.budgetPeriod,
                                    category = b.category,
                                    limitPaise = b.limitPaise,
                                    financialYear = b.financialYear,
                                    notes = b.notes,
                                    isDemo = b.isDemo
                                )
                                finalSpokenReply = "Restored budget: ${undoable.description}."
                            }
                            is UndoableVoiceAction.CreatedGoal -> {
                                repository.deleteGoal(undoable.goalId)
                                finalSpokenReply = "Removed goal: ${undoable.description}."
                            }
                            is UndoableVoiceAction.DeletedGoal -> {
                                val g = undoable.snapshot
                                repository.addGoal(
                                    name = g.name,
                                    goalType = g.goalType,
                                    targetAmountPaise = g.targetAmountPaise,
                                    currentSavedPaise = g.currentSavedPaise,
                                    targetDateEpochDay = g.targetDateEpochDay,
                                    linkedAccountId = g.linkedAccountId,
                                    notes = g.notes,
                                    isDemo = g.isDemo
                                )
                                finalSpokenReply = "Restored savings goal: ${undoable.description}."
                            }
                            is UndoableVoiceAction.ArchivedAccount -> {
                                repository.archiveAccount(undoable.accountId, false)
                                finalSpokenReply = "Restored account: ${undoable.description}."
                            }
                            null -> {
                                finalSpokenReply = "There is no recent voice action to undo."
                            }
                        }
                    }

                    "CREATE_ACCOUNT" -> {
                        val accType = when (result.frequencyOrTypeHint.uppercase(java.util.Locale.US)) {
                            "CASH" -> AccountType.CASH
                            "UPI" -> AccountType.UPI
                            "UPI_WALLET", "WALLET", "DIGITAL_WALLET" -> AccountType.DIGITAL_WALLET
                            "CREDIT_CARD" -> AccountType.CREDIT_CARD
                            "SAVINGS" -> AccountType.SAVINGS
                            "CURRENT" -> AccountType.CURRENT
                            "DEMAT", "OTHER" -> AccountType.OTHER
                            else -> AccountType.BANK
                        }
                        val accName = result.entityName.ifBlank { "New ${accType.displayName}" }
                        val openBal = result.primaryAmountPaise.coerceAtLeast(0L)
                        _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                            transactionType = TransactionType.OPENING_BALANCE,
                            dateEpochDay = IndianFinancialYear.todayEpochDay(),
                            amountPaise = openBal,
                            destinationAccountName = accName,
                            category = accType.displayName,
                            description = "Create Account: $accName",
                            isDemo = isDemo,
                            operationType = "CREATE_ACCOUNT",
                            operationTitle = "Confirm Account Creation",
                            customCommitAction = {
                                repository.createAccount(
                                    name = accName,
                                    accountType = accType,
                                    bankName = if (accType == AccountType.BANK) accName else "",
                                    openingBalancePaise = openBal,
                                    isOpeningBalanceRecorded = openBal > 0L,
                                    openingDateEpochDay = IndianFinancialYear.todayEpochDay(),
                                    isDemo = isDemo
                                )
                                "Confirmed and created account '$accName' (${accType.displayName})."
                            }
                        )
                        finalSpokenReply = "Prepared new account $accName (${accType.displayName}) with opening balance ${InrMoney.formatPaise(openBal)}. Please confirm on screen or say Confirm."
                    }

                    "ARCHIVE_ACCOUNT" -> {
                        val activeAccs = currentState.accountsWithBalances.map { it.account }.filter { !it.isArchived }
                        val matched = activeAccs.filter {
                            result.entityName.isNotBlank() && it.name.contains(result.entityName, ignoreCase = true)
                        }
                        fun stageArchiveAccount(target: AccountEntity): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = 0L,
                                sourceAccountId = target.id,
                                sourceAccountName = target.name,
                                category = target.accountType.displayName,
                                description = "Archive Account: ${target.name}",
                                isDemo = isDemo,
                                operationType = "ARCHIVE_ACCOUNT",
                                operationTitle = "Confirm Account Archive",
                                customCommitAction = {
                                    repository.archiveAccount(target.id, true)
                                    lastUndoableVoiceAction = UndoableVoiceAction.ArchivedAccount(target.id, target.name)
                                    "Confirmed and archived account ${target.name}. Say Undo to restore it."
                                }
                            )
                            return "Prepared to archive account ${target.name}. Please confirm on screen or say Confirm."
                        }
                        if (activeAccs.isEmpty()) {
                            finalSpokenReply = "There are no active accounts to delete or archive."
                        } else if (matched.size == 1) {
                            finalSpokenReply = stageArchiveAccount(matched.first())
                        } else {
                            val candidates = if (matched.isNotEmpty()) matched else activeAccs
                            val options = candidates.take(5).mapIndexed { idx, acc ->
                                VoiceDisambiguationOption(idx + 1, acc.name, acc.accountType.displayName, acc.id)
                            }
                            val q = "Which account would you like to archive: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Account to Archive",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val acc = uiState.value.accountsWithBalances.firstOrNull { it.account.id == chosen.entityId }?.account
                                        ?: throw IllegalArgumentException("Account not found.")
                                    stageArchiveAccount(acc)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "ADD_SUBSCRIPTION" -> {
                        val upperHint = result.frequencyOrTypeHint.uppercase(java.util.Locale.US)
                        val freq = when (upperHint) {
                            "BIMONTHLY" -> BillingFrequency.BIMONTHLY
                            "FOUR_MONTHLY" -> BillingFrequency.FOUR_MONTHLY
                            "QUARTERLY" -> BillingFrequency.QUARTERLY
                            "HALF_YEARLY" -> BillingFrequency.HALF_YEARLY
                            "YEARLY", "ANNUALLY" -> BillingFrequency.ANNUALLY
                            "WEEKLY", "CUSTOM" -> BillingFrequency.CUSTOM
                            else -> BillingFrequency.MONTHLY
                        }
                        val customUnit = if (upperHint == "WEEKLY") "DAYS" else "MONTHS"
                        val customCount = if (upperHint == "WEEKLY") 7 else result.tenureOrIntervalCount.coerceAtLeast(1)
                        val subName = result.entityName.ifBlank { "Subscription" }
                        fun stageSubscriptionPreview(costPaise: Long): String {
                            val today = IndianFinancialYear.todayEpochDay()
                            val nextRen = IndianFinancialYear.advanceSubscriptionRenewalEpochDay(
                                fromEpochDay = today,
                                frequency = freq,
                                customIntervalUnit = customUnit,
                                customIntervalCount = customCount
                            )
                            val defaultAcc = currentState.accountsWithBalances.firstOrNull { !it.account.isArchived }?.account
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                transactionType = TransactionType.EXPENSE,
                                dateEpochDay = nextRen,
                                amountPaise = costPaise,
                                sourceAccountId = defaultAcc?.id,
                                sourceAccountName = defaultAcc?.name.orEmpty(),
                                category = "Subscription (${freq.displayName})",
                                description = "Add Subscription: $subName",
                                isDemo = isDemo,
                                operationType = "ADD_SUBSCRIPTION",
                                operationTitle = "Confirm Subscription",
                                customCommitAction = {
                                    val subId = repository.addSubscription(
                                        name = subName,
                                        costPaise = costPaise,
                                        billingFrequency = freq,
                                        nextRenewalEpochDay = nextRen,
                                        paymentAccountId = defaultAcc?.id,
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo,
                                        customIntervalUnit = customUnit,
                                        customIntervalCount = customCount
                                    )
                                    lastUndoableVoiceAction = UndoableVoiceAction.CreatedSubscription(subId, subName)
                                    "Confirmed and added $subName subscription for ${InrMoney.formatPaise(costPaise)}."
                                }
                            )
                            return "Prepared $subName subscription for ${InrMoney.formatPaise(costPaise)} (${freq.displayName}). Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Amount for $subName",
                                questionPrompt = "How much is the $subName subscription in rupees?",
                                quickAmountsPaise = listOf(199_00L, 299_00L, 499_00L, 649_00L, 1_499_00L),
                                onAmountProvided = { amt -> stageSubscriptionPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageSubscriptionPreview(result.primaryAmountPaise)
                        }
                    }

                    "PAY_SUBSCRIPTION" -> {
                        val subs = currentState.subscriptions
                        val matched = subs.filter {
                            result.entityName.isNotBlank() && it.name.contains(result.entityName, ignoreCase = true)
                        }
                        val (previewAccId, previewAccName) = resolveAccountCandidateForPreview(result.accountHint, currentState)
                        fun stagePaySubscription(sub: SubscriptionEntity): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                transactionType = TransactionType.EXPENSE,
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = sub.costPaise,
                                sourceAccountId = sub.paymentAccountId ?: previewAccId,
                                sourceAccountName = previewAccName,
                                category = "Subscription & Renewals",
                                description = "Subscription Renewal: ${sub.name}",
                                isDemo = isDemo,
                                operationType = "PAY_SUBSCRIPTION",
                                operationTitle = "Confirm Subscription Payment",
                                customCommitAction = {
                                    val accId = sub.paymentAccountId
                                        ?: previewAccId
                                        ?: repository.resolveOrCreateAccountForVoice(result.accountHint, isDemo).id
                                    repository.recordSubscriptionPaymentNow(sub.id, accId)
                                    "Confirmed payment of ${InrMoney.formatPaise(sub.costPaise)} for ${sub.name}."
                                }
                            )
                            return "Prepared subscription payment of ${InrMoney.formatPaise(sub.costPaise)} for ${sub.name}. Please confirm on screen or say Confirm."
                        }
                        if (subs.isEmpty()) {
                            finalSpokenReply = "You have no active subscriptions to pay."
                        } else if (matched.size == 1) {
                            finalSpokenReply = stagePaySubscription(matched.first())
                        } else {
                            val candidates = if (matched.isNotEmpty()) matched else subs
                            val options = candidates.take(5).mapIndexed { idx, sub ->
                                VoiceDisambiguationOption(
                                    index = idx + 1,
                                    label = sub.name,
                                    subtitle = "${InrMoney.formatPaise(sub.costPaise)} • ${sub.cycleLabel()}",
                                    entityId = sub.id
                                )
                            }
                            val q = "Which subscription would you like to pay: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Subscription to Pay",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val sub = uiState.value.subscriptions.firstOrNull { it.id == chosen.entityId }
                                        ?: throw IllegalArgumentException("Subscription not found.")
                                    stagePaySubscription(sub)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "DELETE_SUBSCRIPTION" -> {
                        val subs = currentState.subscriptions
                        val matched = subs.filter {
                            result.entityName.isNotBlank() && it.name.contains(result.entityName, ignoreCase = true)
                        }
                        fun stageDeleteSubscription(sub: SubscriptionEntity): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = sub.nextRenewalEpochDay,
                                amountPaise = sub.costPaise,
                                category = sub.cycleLabel(),
                                description = "Delete Subscription: ${sub.name}",
                                isDemo = isDemo,
                                operationType = "DELETE_SUBSCRIPTION",
                                operationTitle = "Confirm Subscription Deletion",
                                customCommitAction = {
                                    repository.deleteSubscription(sub.id)
                                    lastUndoableVoiceAction = UndoableVoiceAction.DeletedSubscription(sub, sub.name)
                                    "Confirmed and deleted ${sub.name} subscription. Say Undo to restore it."
                                }
                            )
                            return "Prepared to delete ${sub.name} subscription. Please confirm on screen or say Confirm."
                        }
                        if (subs.isEmpty()) {
                            finalSpokenReply = "You have no active subscriptions to delete."
                        } else if (matched.size == 1) {
                            finalSpokenReply = stageDeleteSubscription(matched.first())
                        } else {
                            val candidates = if (matched.isNotEmpty()) matched else subs
                            val options = candidates.take(5).mapIndexed { idx, sub ->
                                VoiceDisambiguationOption(idx + 1, sub.name, InrMoney.formatPaise(sub.costPaise), sub.id)
                            }
                            val q = "Which subscription should I delete: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Subscription to Delete",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val sub = uiState.value.subscriptions.firstOrNull { it.id == chosen.entityId }
                                        ?: throw IllegalArgumentException("Subscription not found.")
                                    stageDeleteSubscription(sub)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "ADD_INVESTMENT" -> {
                        val invCat = when (result.frequencyOrTypeHint.uppercase(java.util.Locale.US)) {
                            "EQUITY_SHARES", "STOCK", "EQUITY" -> InvestmentCategory.EQUITY_SHARES
                            "ETF" -> InvestmentCategory.ETF
                            "SIP" -> InvestmentCategory.SIP
                            "FIXED_DEPOSIT", "FD" -> InvestmentCategory.FIXED_DEPOSIT
                            "RECURRING_DEPOSIT", "RD" -> InvestmentCategory.RECURRING_DEPOSIT
                            "PPF", "NPS", "GOVT_SECURITIES" -> InvestmentCategory.GOVT_SECURITIES
                            "GOLD" -> InvestmentCategory.GOLD
                            "SILVER" -> InvestmentCategory.SILVER
                            "BONDS" -> InvestmentCategory.BONDS
                            "REAL_ESTATE", "OTHER" -> InvestmentCategory.OTHER
                            else -> InvestmentCategory.MUTUAL_FUND
                        }
                        val invName = result.entityName.ifBlank { "Portfolio Investment" }
                        val (previewAccId, previewAccName) = resolveAccountCandidateForPreview(result.accountHint, currentState)
                        fun stageInvestmentPreview(amtPaise: Long): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                transactionType = TransactionType.INVESTMENT_PURCHASE,
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = amtPaise,
                                sourceAccountId = previewAccId,
                                sourceAccountName = previewAccName,
                                category = invCat.displayName,
                                description = "Investment in $invName",
                                isDemo = isDemo,
                                operationType = "ADD_INVESTMENT",
                                operationTitle = "Confirm Investment Entry",
                                customCommitAction = {
                                    val payAcc = repository.resolveOrCreateAccountForVoice(result.accountHint, isDemo)
                                    repository.addInvestment(
                                        name = invName,
                                        category = invCat,
                                        purchaseDateEpochDay = IndianFinancialYear.todayEpochDay(),
                                        quantity = 1.0,
                                        units = 1.0,
                                        purchasePricePerUnitPaise = amtPaise,
                                        investedAmountPaise = amtPaise,
                                        chargesPaise = 0L,
                                        currentRecordedValuePaise = amtPaise,
                                        isCurrentValueRecorded = true,
                                        paymentAccountId = payAcc.id,
                                        maturityDateEpochDay = null,
                                        annualInterestRatePercent = result.ratePercent.takeIf { it > 0.0 },
                                        compoundingFrequency = CompoundingFrequency.ANNUALLY,
                                        durationMonths = result.tenureOrIntervalCount.takeIf { it > 0 },
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo
                                    )
                                    "Confirmed and recorded investment of ${InrMoney.formatPaise(amtPaise)} in $invName."
                                }
                            )
                            return "Prepared investment of ${InrMoney.formatPaise(amtPaise)} in $invName (${invCat.displayName}). Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Investment Amount for $invName",
                                questionPrompt = "How much did you invest in $invName? Please say the amount in rupees.",
                                quickAmountsPaise = listOf(1_000_00L, 5_000_00L, 10_000_00L, 25_000_00L, 50_000_00L),
                                onAmountProvided = { amt -> stageInvestmentPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageInvestmentPreview(result.primaryAmountPaise)
                        }
                    }

                    "ADD_LOAN" -> {
                        val lType = when (result.frequencyOrTypeHint.uppercase(java.util.Locale.US)) {
                            "HOME" -> LoanType.HOME
                            "VEHICLE", "CAR" -> LoanType.VEHICLE
                            "EDUCATION" -> LoanType.EDUCATION
                            "GOLD", "CREDIT_CARD", "OTHER" -> LoanType.OTHER
                            else -> LoanType.PERSONAL
                        }
                        val loanName = result.entityName.ifBlank { lType.displayName }
                        val (previewAccId, previewAccName) = resolveAccountCandidateForPreview(result.accountHint, currentState)
                        fun stageLoanPreview(principalPaise: Long): String {
                            val rate = result.ratePercent.coerceAtLeast(0.0)
                            val tenure = if (result.tenureOrIntervalCount > 0) result.tenureOrIntervalCount else 12
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                transactionType = TransactionType.LOAN_RECEIVED,
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = principalPaise,
                                destinationAccountId = previewAccId,
                                destinationAccountName = previewAccName,
                                category = lType.displayName,
                                description = "Add Loan: $loanName (${rate}% for $tenure months)",
                                isDemo = isDemo,
                                operationType = "ADD_LOAN",
                                operationTitle = "Confirm Loan Record",
                                customCommitAction = {
                                    val acc = repository.resolveOrCreateAccountForVoice(result.accountHint, isDemo)
                                    repository.addLoan(
                                        name = loanName,
                                        loanType = lType,
                                        lenderName = loanName,
                                        principalPaise = principalPaise,
                                        annualInterestRatePercent = rate,
                                        tenureMonths = tenure,
                                        startDateEpochDay = IndianFinancialYear.todayEpochDay(),
                                        disbursedToAccountId = acc.id,
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo
                                    )
                                    "Confirmed and added loan '$loanName' of ${InrMoney.formatPaise(principalPaise)}."
                                }
                            )
                            return "Prepared loan $loanName of ${InrMoney.formatPaise(principalPaise)}. Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Principal Amount for $loanName",
                                questionPrompt = "What is the loan principal amount in rupees for $loanName?",
                                quickAmountsPaise = listOf(50_000_00L, 1_00_000_00L, 5_00_000_00L, 10_00_000_00L, 25_00_000_00L),
                                onAmountProvided = { amt -> stageLoanPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageLoanPreview(result.primaryAmountPaise)
                        }
                    }

                    "PAY_LOAN_EMI" -> {
                        val activeLoans = currentState.loans.filter { !it.isClosed }
                        val matched = activeLoans.filter {
                            result.entityName.isNotBlank() && it.name.contains(result.entityName, ignoreCase = true)
                        }
                        val (previewAccId, previewAccName) = resolveAccountCandidateForPreview(result.accountHint, currentState)
                        fun stageLoanEmiPreview(loan: LoanEntity): String {
                            val emiPaise = loan.emiAmountPaise.coerceAtMost(loan.remainingPrincipalPaise).coerceAtLeast(1L)
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                transactionType = TransactionType.LOAN_REPAYMENT,
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = emiPaise,
                                sourceAccountId = previewAccId,
                                sourceAccountName = previewAccName,
                                category = "Loan EMI Repayment",
                                description = "EMI Repayment for ${loan.name}",
                                isDemo = isDemo,
                                operationType = "PAY_LOAN_EMI",
                                operationTitle = "Confirm Loan EMI Payment",
                                customCommitAction = {
                                    val srcAcc = repository.resolveOrCreateAccountForVoice(result.accountHint, isDemo)
                                    repository.recordTransaction(
                                        transactionType = TransactionType.LOAN_REPAYMENT,
                                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                        amountPaise = emiPaise,
                                        principalComponentPaise = emiPaise,
                                        interestComponentPaise = 0L,
                                        sourceAccountId = srcAcc.id,
                                        category = "Loan EMI Repayment",
                                        description = "EMI Repayment for ${loan.name}",
                                        linkedLoanId = loan.id,
                                        isDemo = isDemo
                                    )
                                    "Confirmed EMI payment of ${InrMoney.formatPaise(emiPaise)} for ${loan.name}."
                                }
                            )
                            return "Prepared EMI payment of ${InrMoney.formatPaise(emiPaise)} for ${loan.name}. Please confirm on screen or say Confirm."
                        }
                        if (activeLoans.isEmpty()) {
                            finalSpokenReply = "You have no active loans for EMI repayment."
                        } else if (matched.size == 1) {
                            finalSpokenReply = stageLoanEmiPreview(matched.first())
                        } else {
                            val candidates = if (matched.isNotEmpty()) matched else activeLoans
                            val options = candidates.take(5).mapIndexed { idx, l ->
                                VoiceDisambiguationOption(
                                    index = idx + 1,
                                    label = l.name,
                                    subtitle = "EMI: ${InrMoney.formatPaise(l.emiAmountPaise)}",
                                    entityId = l.id
                                )
                            }
                            val q = "Which loan EMI would you like to pay: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Loan EMI to Pay",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val loan = uiState.value.loans.firstOrNull { it.id == chosen.entityId }
                                        ?: throw IllegalArgumentException("Loan not found.")
                                    stageLoanEmiPreview(loan)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "ADD_BUDGET" -> {
                        val cat = result.categoryOrScope.ifBlank { "OVERALL" }
                        val bName = result.entityName.ifBlank { if (cat == "OVERALL") "Overall Monthly Budget" else "$cat Budget" }
                        fun stageBudgetPreview(limitPaise: Long): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = limitPaise,
                                category = cat,
                                description = "Set Budget: $bName",
                                isDemo = isDemo,
                                operationType = "ADD_BUDGET",
                                operationTitle = "Confirm Monthly Budget",
                                customCommitAction = {
                                    val budgetId = repository.addBudget(
                                        name = bName,
                                        budgetPeriod = BudgetPeriod.MONTHLY,
                                        category = cat,
                                        limitPaise = limitPaise,
                                        financialYear = uiState.value.settings.activeFinancialYear,
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo
                                    )
                                    lastUndoableVoiceAction = UndoableVoiceAction.CreatedBudget(budgetId, bName)
                                    "Confirmed and saved $bName of ${InrMoney.formatPaise(limitPaise)}."
                                }
                            )
                            return "Prepared $bName with limit ${InrMoney.formatPaise(limitPaise)}. Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Limit for $bName",
                                questionPrompt = "What is the monthly limit in rupees for $bName?",
                                quickAmountsPaise = listOf(5_000_00L, 10_000_00L, 15_000_00L, 25_000_00L, 50_000_00L),
                                onAmountProvided = { amt -> stageBudgetPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageBudgetPreview(result.primaryAmountPaise)
                        }
                    }

                    "DELETE_BUDGET" -> {
                        val budgets = currentState.budgets
                        val matched = budgets.filter {
                            result.entityName.isNotBlank() && (
                                it.name.contains(result.entityName, ignoreCase = true) ||
                                    it.category.contains(result.entityName, ignoreCase = true)
                                )
                        }
                        fun stageDeleteBudget(b: BudgetEntity): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = b.limitPaise,
                                category = b.category,
                                description = "Delete Budget: ${b.name}",
                                isDemo = isDemo,
                                operationType = "DELETE_BUDGET",
                                operationTitle = "Confirm Budget Deletion",
                                customCommitAction = {
                                    repository.deleteBudget(b.id)
                                    lastUndoableVoiceAction = UndoableVoiceAction.DeletedBudget(b, b.name)
                                    "Confirmed and deleted ${b.name}. Say Undo to restore it."
                                }
                            )
                            return "Prepared to delete budget ${b.name}. Please confirm on screen or say Confirm."
                        }
                        if (budgets.isEmpty()) {
                            finalSpokenReply = "You have no active budgets to delete."
                        } else if (matched.size == 1) {
                            finalSpokenReply = stageDeleteBudget(matched.first())
                        } else {
                            val candidates = if (matched.isNotEmpty()) matched else budgets
                            val options = candidates.take(5).mapIndexed { idx, b ->
                                VoiceDisambiguationOption(idx + 1, b.name, InrMoney.formatPaise(b.limitPaise), b.id)
                            }
                            val q = "Which budget should I delete: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Budget to Delete",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val b = uiState.value.budgets.firstOrNull { it.id == chosen.entityId }
                                        ?: throw IllegalArgumentException("Budget not found.")
                                    stageDeleteBudget(b)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "ADD_GOAL" -> {
                        val goalName = result.entityName.ifBlank { "Savings Goal" }
                        fun stageGoalPreview(targetPaise: Long): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = targetPaise,
                                category = GoalType.SAVINGS.displayName,
                                description = "Create Goal: $goalName",
                                isDemo = isDemo,
                                operationType = "ADD_GOAL",
                                operationTitle = "Confirm Savings Goal",
                                customCommitAction = {
                                    val goalId = repository.addGoal(
                                        name = goalName,
                                        goalType = GoalType.SAVINGS,
                                        targetAmountPaise = targetPaise,
                                        currentSavedPaise = result.secondaryAmountPaise.coerceAtLeast(0L),
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo
                                    )
                                    lastUndoableVoiceAction = UndoableVoiceAction.CreatedGoal(goalId, goalName)
                                    "Confirmed and created savings goal '$goalName' with target ${InrMoney.formatPaise(targetPaise)}."
                                }
                            )
                            return "Prepared savings goal $goalName with target ${InrMoney.formatPaise(targetPaise)}. Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Target Amount for $goalName",
                                questionPrompt = "What is the target amount in rupees for your $goalName goal?",
                                quickAmountsPaise = listOf(10_000_00L, 25_000_00L, 50_000_00L, 1_00_000_00L, 5_00_000_00L),
                                onAmountProvided = { amt -> stageGoalPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageGoalPreview(result.primaryAmountPaise)
                        }
                    }

                    "DELETE_GOAL" -> {
                        val goals = currentState.goals
                        val matched = goals.filter {
                            result.entityName.isNotBlank() && it.name.contains(result.entityName, ignoreCase = true)
                        }
                        fun stageDeleteGoal(g: GoalEntity): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = g.targetAmountPaise,
                                category = g.goalType.displayName,
                                description = "Delete Goal: ${g.name}",
                                isDemo = isDemo,
                                operationType = "DELETE_GOAL",
                                operationTitle = "Confirm Goal Deletion",
                                customCommitAction = {
                                    repository.deleteGoal(g.id)
                                    lastUndoableVoiceAction = UndoableVoiceAction.DeletedGoal(g, g.name)
                                    "Confirmed and deleted savings goal ${g.name}. Say Undo to restore it."
                                }
                            )
                            return "Prepared to delete savings goal ${g.name}. Please confirm on screen or say Confirm."
                        }
                        if (goals.isEmpty()) {
                            finalSpokenReply = "You have no savings goals to delete."
                        } else if (matched.size == 1) {
                            finalSpokenReply = stageDeleteGoal(matched.first())
                        } else {
                            val candidates = if (matched.isNotEmpty()) matched else goals
                            val options = candidates.take(5).mapIndexed { idx, g ->
                                VoiceDisambiguationOption(
                                    idx + 1,
                                    g.name,
                                    "${InrMoney.formatPaise(g.currentSavedPaise)} / ${InrMoney.formatPaise(g.targetAmountPaise)}",
                                    g.id
                                )
                            }
                            val q = "Which savings goal should I delete: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Savings Goal to Delete",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val g = uiState.value.goals.firstOrNull { it.id == chosen.entityId }
                                        ?: throw IllegalArgumentException("Goal not found.")
                                    stageDeleteGoal(g)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "CONTRIBUTE_GOAL" -> {
                        val goals = currentState.goals
                        val matched = goals.filter {
                            result.entityName.isNotBlank() && it.name.contains(result.entityName, ignoreCase = true)
                        }
                        fun stageGoalContribution(g: GoalEntity, amtPaise: Long): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = amtPaise,
                                destinationAccountName = g.name,
                                category = g.goalType.displayName,
                                description = "Contribute to Goal: ${g.name}",
                                isDemo = isDemo,
                                operationType = "CONTRIBUTE_GOAL",
                                operationTitle = "Confirm Goal Contribution",
                                customCommitAction = {
                                    repository.contributeToGoal(g.id, amtPaise)
                                    "Confirmed and added ${InrMoney.formatPaise(amtPaise)} towards your ${g.name} goal."
                                }
                            )
                            return "Prepared contribution of ${InrMoney.formatPaise(amtPaise)} towards ${g.name}. Please confirm on screen or say Confirm."
                        }
                        if (goals.isEmpty()) {
                            finalSpokenReply = "You have no savings goals yet. Say Create a savings goal first."
                        } else if (result.primaryAmountPaise <= 0L) {
                            val targetGoal = matched.firstOrNull() ?: goals.first()
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Contribution for ${targetGoal.name}",
                                questionPrompt = "How much in rupees would you like to add towards ${targetGoal.name}?",
                                quickAmountsPaise = listOf(500_00L, 1_000_00L, 2_500_00L, 5_000_00L, 10_000_00L),
                                onAmountProvided = { amt -> stageGoalContribution(targetGoal, amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else if (matched.size == 1 || (goals.size == 1 && result.entityName.isBlank())) {
                            val g = matched.firstOrNull() ?: goals.first()
                            finalSpokenReply = stageGoalContribution(g, result.primaryAmountPaise)
                        } else {
                            val amt = result.primaryAmountPaise
                            val options = goals.take(5).mapIndexed { idx, g ->
                                VoiceDisambiguationOption(
                                    index = idx + 1,
                                    label = g.name,
                                    subtitle = "${InrMoney.formatPaise(g.currentSavedPaise)} / ${InrMoney.formatPaise(g.targetAmountPaise)}",
                                    entityId = g.id
                                )
                            }
                            val q = "Which savings goal should I add ${InrMoney.formatPaise(amt)} to: ${options.joinToString(", or ") { "${it.index}. ${it.label}" }}?"
                            _pendingVoiceDisambiguation.value = PendingVoiceDisambiguation(
                                title = "Select Goal for ${InrMoney.formatPaise(amt)}",
                                questionPrompt = q,
                                options = options,
                                onSelectOption = { chosen ->
                                    val g = uiState.value.goals.firstOrNull { it.id == chosen.entityId }
                                        ?: throw IllegalArgumentException("Goal not found.")
                                    stageGoalContribution(g, amt)
                                }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = true
                        }
                    }

                    "ADD_GIFT_CARD" -> {
                        val brand = result.entityName.ifBlank { "Gift Card" }
                        fun stageGiftCardPreview(valPaise: Long): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = valPaise,
                                destinationAccountName = "$brand Voucher",
                                category = "Gift Card",
                                description = "Add $brand Gift Card",
                                isDemo = isDemo,
                                operationType = "ADD_GIFT_CARD",
                                operationTitle = "Confirm Gift Card",
                                customCommitAction = {
                                    repository.addGiftCard(
                                        brand = brand,
                                        name = "$brand Voucher",
                                        initialValuePaise = valPaise,
                                        remainingValuePaise = valPaise,
                                        purchaseDateEpochDay = IndianFinancialYear.todayEpochDay(),
                                        cardNumberRaw = "XXXX-1001",
                                        redemptionPinRaw = "0000",
                                        purchasedFromAccountId = null,
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo
                                    )
                                    "Confirmed and added $brand gift card worth ${InrMoney.formatPaise(valPaise)}."
                                }
                            )
                            return "Prepared $brand gift card worth ${InrMoney.formatPaise(valPaise)}. Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Value for $brand Gift Card",
                                questionPrompt = "What is the value in rupees of the $brand gift card?",
                                quickAmountsPaise = listOf(500_00L, 1_000_00L, 2_000_00L, 5_000_00L, 10_000_00L),
                                onAmountProvided = { amt -> stageGiftCardPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageGiftCardPreview(result.primaryAmountPaise)
                        }
                    }

                    "ADD_TAX_RECORD" -> {
                        val challanRef = result.entityName.ifBlank { "VOICE-TAX" }
                        val (previewAccId, previewAccName) = resolveAccountCandidateForPreview(result.accountHint, currentState)
                        fun stageTaxRecordPreview(amtPaise: Long): String {
                            _pendingAiConfirmation.value = PendingAiTransactionConfirmation(
                                transactionType = TransactionType.TAX_PAYMENT,
                                dateEpochDay = IndianFinancialYear.todayEpochDay(),
                                amountPaise = amtPaise,
                                sourceAccountId = previewAccId,
                                sourceAccountName = previewAccName,
                                category = TaxType.ADVANCE_TAX.displayName,
                                description = "Tax Record ($challanRef)",
                                isDemo = isDemo,
                                operationType = "ADD_TAX_RECORD",
                                operationTitle = "Confirm Tax Record",
                                customCommitAction = {
                                    val acc = repository.resolveOrCreateAccountForVoice(result.accountHint, isDemo)
                                    repository.addTaxRecord(
                                        taxType = TaxType.ADVANCE_TAX,
                                        financialYear = uiState.value.settings.activeFinancialYear,
                                        amountPaidPaise = amtPaise,
                                        paymentDateEpochDay = IndianFinancialYear.todayEpochDay(),
                                        challanNumber = challanRef,
                                        paymentAccountId = acc.id,
                                        supportingDocLabel = "Voice Entry",
                                        notes = "Added via Voice Command",
                                        isDemo = isDemo
                                    )
                                    "Confirmed and recorded tax payment of ${InrMoney.formatPaise(amtPaise)}."
                                }
                            )
                            return "Prepared tax record of ${InrMoney.formatPaise(amtPaise)} from $previewAccName. Please confirm on screen or say Confirm."
                        }
                        if (result.primaryAmountPaise <= 0L) {
                            val (q, followUp) = promptForMissingVoiceAmount(
                                title = "Enter Tax Payment Amount",
                                questionPrompt = "How much tax was paid in rupees?",
                                quickAmountsPaise = listOf(2_500_00L, 5_000_00L, 10_000_00L, 25_000_00L, 50_000_00L),
                                onAmountProvided = { amt -> stageTaxRecordPreview(amt) }
                            )
                            finalSpokenReply = q
                            listenForFollowUp = followUp
                        } else {
                            finalSpokenReply = stageTaxRecordPreview(result.primaryAmountPaise)
                        }
                    }

                    "SEARCH" -> {
                        val q = result.searchQuery.trim()
                        if (q.isNotBlank()) {
                            updateSearchQuery(q)
                            commitSearchQuery(q)
                            collapseSearchBar()
                        }
                    }

                    "CLEAR_SEARCH" -> {
                        closeAndResetSearch()
                    }

                    "EXPORT_REPORT" -> {
                        when (result.exportFormat.uppercase(java.util.Locale.US)) {
                            "PDF" -> preparePdfExport(context)
                            "EXCEL" -> prepareCsvOrExcelExport(context, asExcel = true)
                            "CSV" -> prepareCsvOrExcelExport(context, asExcel = false)
                            "JSON" -> prepareJsonBackupExport(context)
                            else -> preparePdfExport(context)
                        }
                    }

                    "UPDATE_SETTING" -> {
                        when (result.settingKey.uppercase(java.util.Locale.US)) {
                            "PRIVACY_MODE" -> {
                                val enable = result.settingValue.equals("ON", ignoreCase = true)
                                repository.togglePrivacyMode(enable)
                            }
                            "THEME_MODE" -> {
                                val mode = when (result.settingValue.uppercase(java.util.Locale.US)) {
                                    "LIGHT" -> ThemeMode.LIGHT
                                    "SYSTEM" -> ThemeMode.SYSTEM
                                    else -> ThemeMode.DARK
                                }
                                repository.updateThemeMode(mode)
                            }
                            "DEMO_MODE" -> {
                                val enable = result.settingValue.equals("ON", ignoreCase = true)
                                repository.setDemonstrationMode(enable)
                            }
                            "LOCK_APP" -> {
                                lockAppNow()
                            }
                        }
                    }
                }

                val updatedState = uiState.value
                val totalBalPaise = updatedState.accountsWithBalances
                    .filter { !it.account.isArchived }
                    .sumOf { it.closingBalancePaise }
                val payload = AssistantFulfillmentPayload(
                    spokenReply = finalSpokenReply,
                    netWorthFormatted = InrMoney.formatPaise(updatedState.balanceSheet.totalNetWorthPaise),
                    totalBalanceFormatted = InrMoney.formatPaise(totalBalPaise),
                    monthlySpendingFormatted = InrMoney.formatPaise(updatedState.currentMonthTotalSpentPaise),
                    monthlyIncomeFormatted = InrMoney.formatPaise(updatedState.monthlyProfitAndLoss.totalIncomePaise),
                    recordedTransactionsCount = if (result.action == "RECORD_TRANSACTION" && !listenForFollowUp) result.transactionsToRecord.size else 0,
                    errorMessage = result.errorMessage
                )

                _statusBannerMessage.value = finalSpokenReply
                onAssistantFulfillment(payload)
                onFinished(finalSpokenReply, result.errorMessage, listenForFollowUp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val fallbackMsg = "Could not complete voice command: ${e.localizedMessage ?: "Please try again."}"
                _statusBannerMessage.value = fallbackMsg
                onFinished(fallbackMsg, e.localizedMessage, false)
            }
        }
    }

    /**
     * Handles incoming intents from Google Assistant, Google Gemini App Actions,
     * Android Voice Shortcuts, and 6-Hour / Custom Transaction Recall Notifications,
     * returning relevant financial information back to Google Assistant and the user.
     */
    fun handleExternalGeminiOrVoiceIntent(
        context: Context,
        intent: Intent?,
        onStartVoiceListening: () -> Unit,
        onFinishedReply: (String, String?, Boolean) -> Unit,
        onAssistantFulfillment: (AssistantFulfillmentPayload) -> Unit = {}
    ) {
        if (intent == null) return

        if (intent.getBooleanExtra(TransactionRecallReminderManager.EXTRA_OPEN_RECORD_SHEET, false)) {
            intent.removeExtra(TransactionRecallReminderManager.EXTRA_OPEN_RECORD_SHEET)
            openAddTransactionSheet(true)
            return
        }

        if (intent.getBooleanExtra(TransactionRecallReminderManager.EXTRA_START_VOICE_RECORD, false)) {
            intent.removeExtra(TransactionRecallReminderManager.EXTRA_START_VOICE_RECORD)
            onStartVoiceListening()
            return
        }

        val dataUri = intent.data
        if (dataUri != null && dataUri.scheme.equals("vault", ignoreCase = true)) {
            val host = dataUri.host?.lowercase().orEmpty()
            val queryParam = dataUri.getQueryParameter("query").orEmpty()
            val featureParam = dataUri.getQueryParameter("feature").orEmpty()
            val amountParam = dataUri.getQueryParameter("amount").orEmpty()
            val categoryParam = dataUri.getQueryParameter("category").orEmpty()
            val merchantParam = dataUri.getQueryParameter("merchant").orEmpty()
            val typeParam = dataUri.getQueryParameter("type").orEmpty()
            val accountParam = dataUri.getQueryParameter("account").orEmpty()
            intent.data = null

            when {
                host == "voice-record" || featureParam.contains("voice", ignoreCase = true) -> {
                    if (queryParam.isNotBlank()) {
                        executeVoiceOrGeminiCommand(
                            context = context,
                            utterance = queryParam,
                            onFinished = onFinishedReply,
                            onAssistantFulfillment = onAssistantFulfillment
                        )
                    } else {
                        onStartVoiceListening()
                    }
                }

                host == "record-transaction" || amountParam.isNotBlank() -> {
                    val constructedSentence = if (queryParam.isNotBlank()) {
                        queryParam
                    } else if (amountParam.isNotBlank()) {
                        val actionWord = if (typeParam.equals("INCOME", ignoreCase = true)) "Received" else "Spent"
                        val catPart = if (categoryParam.isNotBlank()) " on $categoryParam" else ""
                        val merchPart = if (merchantParam.isNotBlank()) " at $merchantParam" else ""
                        val accPart = if (accountParam.isNotBlank()) " from $accountParam" else ""
                        "$actionWord $amountParam rupees$catPart$merchPart$accPart"
                    } else ""

                    if (constructedSentence.isNotBlank()) {
                        executeVoiceOrGeminiCommand(
                            context = context,
                            utterance = constructedSentence,
                            onFinished = onFinishedReply,
                            onAssistantFulfillment = onAssistantFulfillment
                        )
                    } else {
                        onStartVoiceListening()
                    }
                }

                host == "query-finance" || host == "assistant" -> {
                    val q = queryParam.ifBlank {
                        when {
                            accountParam.isNotBlank() -> "What is my $accountParam balance?"
                            featureParam.isNotBlank() -> "Tell me about my $featureParam"
                            else -> "Give me a summary of my balances, net worth, and monthly spending"
                        }
                    }
                    _showGeminiAssistantDialog.value = true
                    executeVoiceOrGeminiCommand(
                        context = context,
                        utterance = q,
                        onFinished = onFinishedReply,
                        onAssistantFulfillment = onAssistantFulfillment
                    )
                }
            }
            return
        }

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
            if (sharedText.isNotBlank()) {
                intent.removeExtra(Intent.EXTRA_TEXT)
                executeVoiceOrGeminiCommand(
                    context = context,
                    utterance = sharedText,
                    onFinished = onFinishedReply,
                    onAssistantFulfillment = onAssistantFulfillment
                )
            }
            return
        }

        if (
            intent.action == "com.google.android.gms.actions.SEARCH_ACTION" ||
            intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.action == Intent.ACTION_ASSIST
        ) {
            val voiceQuery = intent.getStringExtra(android.app.SearchManager.QUERY)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra("query")
                ?: ""
            intent.removeExtra(android.app.SearchManager.QUERY)
            val trimmed = voiceQuery.trim()
            if (trimmed.isNotBlank()) {
                _showGeminiAssistantDialog.value = true
                executeVoiceOrGeminiCommand(
                    context = context,
                    utterance = trimmed,
                    onFinished = onFinishedReply,
                    onAssistantFulfillment = onAssistantFulfillment
                )
            } else {
                // If Google Assistant launched VOICE_COMMAND or ASSIST without a query string, return summary & start listening
                val summaryReply = GeminiVaultAssistant.answerFinancialQueryLocally("summary", uiState.value)
                val s = uiState.value
                val totalBalPaise = s.accountsWithBalances
                    .filter { !it.account.isArchived }
                    .sumOf { it.closingBalancePaise }
                onAssistantFulfillment(
                    AssistantFulfillmentPayload(
                        spokenReply = summaryReply,
                        netWorthFormatted = InrMoney.formatPaise(s.balanceSheet.totalNetWorthPaise),
                        totalBalanceFormatted = InrMoney.formatPaise(totalBalPaise),
                        monthlySpendingFormatted = InrMoney.formatPaise(s.currentMonthTotalSpentPaise),
                        monthlyIncomeFormatted = InrMoney.formatPaise(s.monthlyProfitAndLoss.totalIncomePaise),
                        recordedTransactionsCount = 0
                    )
                )
                onStartVoiceListening()
            }
        }
    }

    fun addTaxRecord(
        taxType: TaxType,
        financialYear: String,
        amountPaidPaise: Long,
        challanNumber: String,
        paymentAccountId: Long?,
        supportingDocLabel: String,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.addTaxRecord(
                    taxType = taxType,
                    financialYear = financialYear,
                    amountPaidPaise = amountPaidPaise,
                    paymentDateEpochDay = IndianFinancialYear.todayEpochDay(),
                    challanNumber = challanNumber,
                    paymentAccountId = paymentAccountId,
                    supportingDocLabel = supportingDocLabel,
                    notes = notes,
                    isDemo = uiState.value.settings.isDemoModeEnabled
                )
            }.onSuccess {
                _statusBannerMessage.value = "Tax record saved for $financialYear."
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not save tax record: ${e.message}"
            }
        }
    }

    fun updateProfileAndFy(userName: String, panMasked: String, activeFy: String = IndianFinancialYear.currentFyLabel()) {
        viewModelScope.launch(Dispatchers.IO) {
            val systemFy = IndianFinancialYear.currentFyLabel()
            repository.updateProfileAndFy(userName, panMasked, systemFy)
            _statusBannerMessage.value = "Profile updated ($systemFy)."
        }
    }

    fun updateSecurityPin(newPin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSecurityPin(newPin)
            _pinLockoutErrorMessage.value = null
            _isPinUnlockedSession.value = true
            _statusBannerMessage.value = if (newPin.isNullOrBlank()) {
                "PIN & Biometric lock removed."
            } else {
                "PIN lock enabled (PBKDF2-HMAC-SHA256, 120,000 iterations)."
            }
        }
    }

    fun setAiFinancialContextConsent(granted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setAiFinancialContextConsent(granted)
            _statusBannerMessage.value = if (granted) {
                "AI financial context sharing enabled for personalized insights."
            } else {
                "AI financial context sharing disabled (local-only privacy mode)."
            }
        }
    }

    fun updateAutoLockMinutes(minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateAutoLockMinutes(minutes)
            _statusBannerMessage.value = "Auto-lock timer set to $minutes minute(s)."
        }
    }

    fun updateThemeMode(themeMode: com.example.data.local.ThemeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateThemeMode(themeMode)
            _statusBannerMessage.value = "Appearance updated to ${themeMode.displayName}."
        }
    }

    fun toggleBiometricLock(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled && uiState.value.settings.pinHashSha256.isNullOrBlank()) {
                _statusBannerMessage.value = "Please set a 4–8 digit backup PIN first before enabling Biometrics."
                return@launch
            }
            repository.setBiometricEnabled(enabled)
            _statusBannerMessage.value = if (enabled) {
                "Biometric Unlock (Fingerprint / Face ID) enabled."
            } else {
                "Biometric Unlock disabled (PIN lock remains active)."
            }
        }
    }

    fun setLocationTaggingEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setLocationTaggingEnabled(enabled)
            _statusBannerMessage.value = if (enabled) {
                "Optional GPS location tagging enabled for transactions."
            } else {
                "GPS location tagging disabled (opt-in privacy mode)."
            }
        }
    }

    fun unlockWithBiometrics() {
        _pinLockoutErrorMessage.value = null
        lastUserInteractionMillis.set(System.currentTimeMillis())
        backgroundEnteredAtMillis.set(0L)
        _isPinUnlockedSession.value = true
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetPinLockoutOnBiometricSuccess()
        }
    }

    fun clearCurrentModeData() {
        viewModelScope.launch(Dispatchers.IO) {
            val isDemo = uiState.value.settings.isDemoModeEnabled
            repository.clearAllDataForMode(isDemo)
            _statusBannerMessage.value = if (isDemo) {
                "Demonstration records reset."
            } else {
                "All user financial records permanently deleted."
            }
        }
    }

    fun preparePdfExport(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val s = uiState.value
                val preview = DocumentExporter.buildFullReportPreviewText(
                    userName = s.settings.userName,
                    financialYear = s.settings.activeFinancialYear,
                    periodLabel = s.settings.activeFinancialYear,
                    generationDate = IndianFinancialYear.formatEpochDay(IndianFinancialYear.todayEpochDay()),
                    balanceSheet = s.balanceSheet,
                    profitAndLoss = s.fyProfitAndLoss,
                    cashFlow = s.fyCashFlow,
                    trialBalance = s.trialBalance,
                    journalBook = s.journalBook
                )
                DocumentExporter.exportFinancialReportPdf(
                    context = context,
                    title = "Finance Vault Financial Statements (${s.settings.activeFinancialYear})",
                    reportText = preview
                )
            }.onSuccess { res ->
                _exportPreviewResult.value = res
                _statusBannerMessage.value = "Downloaded PDF: ${res.downloadedLocationLabel}"
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not export PDF: ${e.message}"
            }
        }
    }

    fun prepareCsvOrExcelExport(context: Context, asExcel: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val s = uiState.value
                DocumentExporter.exportFinancialReportCsv(
                    context = context,
                    financialYear = s.settings.activeFinancialYear,
                    balanceSheet = s.balanceSheet,
                    profitAndLoss = s.fyProfitAndLoss,
                    trialBalance = s.trialBalance,
                    journalBook = s.journalBook,
                    asExcelExtension = asExcel
                )
            }.onSuccess { res ->
                _exportPreviewResult.value = res
                _statusBannerMessage.value = if (asExcel) {
                    "Downloaded Excel (.xlsx): ${res.downloadedLocationLabel}"
                } else {
                    "Downloaded CSV (.csv): ${res.downloadedLocationLabel}"
                }
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not export spreadsheet: ${e.message}"
            }
        }
    }

    fun prepareJsonBackupExport(context: Context, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = repository.createJsonBackupString(password)
                DocumentExporter.exportJsonBackupFile(context, json)
            }.onSuccess { res ->
                _exportPreviewResult.value = res
                val encNote = if (!password.isNullOrBlank()) " (AES-256-GCM encrypted)" else ""
                _statusBannerMessage.value = "Downloaded JSON Backup$encNote: ${res.downloadedLocationLabel}"
            }.onFailure { e ->
                _statusBannerMessage.value = "Could not export JSON backup: ${e.message}"
            }
        }
    }

    fun inspectBackupBeforeRestore(jsonText: String, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.inspectJsonBackup(jsonText, password)
            }.onSuccess { preview ->
                _pendingBackupRestorePreview.value = preview
            }.onFailure { e ->
                _pendingBackupRestorePreview.value = null
                _statusBannerMessage.value = "Backup validation failed: ${e.message}"
            }
        }
    }

    fun dismissBackupRestorePreview() {
        _pendingBackupRestorePreview.value = null
    }

    fun restoreFromJsonBackup(
        jsonText: String,
        password: String? = null,
        mode: com.example.data.repository.BackupRestoreMode = com.example.data.repository.BackupRestoreMode.MERGE_KEEP_EXISTING
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.restoreFromJsonBackup(jsonText, password, mode)
            }.onSuccess { count ->
                _pendingBackupRestorePreview.value = null
                val modeLabel = if (mode == com.example.data.repository.BackupRestoreMode.REPLACE_EXISTING) "Replaced vault with" else "Merged"
                _statusBannerMessage.value = "$modeLabel $count record(s) from JSON backup."
            }.onFailure { e ->
                _statusBannerMessage.value = "Restore failed: ${e.message}"
            }
        }
    }

    private data class CoreAccountingBundle(
        val accounts: List<AccountEntity>,
        val ledgers: List<LedgerAccountEntity>,
        val txs: List<TransactionEntity>,
        val entries: List<JournalEntryEntity>,
        val lines: List<JournalLineEntity>
    )

    private data class SecondaryBundle1(
        val investments: List<InvestmentEntity>,
        val loans: List<LoanEntity>,
        val loanPayments: List<LoanPaymentEntity>,
        val budgets: List<BudgetEntity>,
        val goals: List<GoalEntity>
    )

    private data class SecondaryBundle2(
        val giftCards: List<GiftCardEntity>,
        val subscriptions: List<SubscriptionEntity>,
        val taxes: List<TaxRecordEntity>,
        val logs: List<AuditLogEntity>
    )

    class Factory(private val repository: FinanceVaultRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FinanceVaultViewModel(repository) as T
        }
    }
}
