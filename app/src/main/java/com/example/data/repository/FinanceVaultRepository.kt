package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.AppSettingsEntity
import com.example.data.local.AuditLogEntity
import com.example.data.local.BillingFrequency
import com.example.data.local.BudgetEntity
import com.example.data.local.BudgetPeriod
import com.example.data.local.CompoundingFrequency
import com.example.data.local.FinanceVaultDatabase
import com.example.data.local.GiftCardEntity
import com.example.data.local.GoalEntity
import com.example.data.local.GoalType
import com.example.data.local.InvestmentCategory
import com.example.data.local.InvestmentEntity
import com.example.data.local.JournalEntryEntity
import com.example.data.local.JournalLineEntity
import com.example.data.local.LedgerAccountEntity
import com.example.data.local.LedgerClassification
import com.example.data.local.LoanEntity
import com.example.data.local.LoanPaymentEntity
import com.example.data.local.LoanType
import com.example.data.local.NormalBalance
import com.example.data.local.PostingStatus
import com.example.data.local.SubscriptionEntity
import com.example.data.local.TaxRecordEntity
import com.example.data.local.TaxType
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.accounting.AccountingEngine
import com.example.domain.calculators.FinancialCalculators
import com.example.domain.money.InrMoney
import com.example.domain.period.IndianFinancialYear
import com.example.domain.security.PinSecurityUtil
import com.example.domain.security.VaultKeystoreCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

sealed class PinVerificationResult {
    data object Success : PinVerificationResult()
    data class InvalidPin(val failedAttempts: Int, val lockoutDurationMillis: Long) : PinVerificationResult()
    data class LockedOut(val remainingSeconds: Long) : PinVerificationResult()
}

/**
 * Central repository coordinating the SQLite database (via Room) and the Double-Entry Accounting Engine.
 * Guarantees that every financial record, journal entry, ledger line, and balance update
 * is committed atomically in a single database transaction.
 */
class FinanceVaultRepository(
    private val database: FinanceVaultDatabase
) {
    private val dao = database.dao()
    private val initializedChartModes = ConcurrentHashMap.newKeySet<Boolean>()
    @Volatile
    private var lastNonDemoTxSignature: String = ""
    @Volatile
    private var lastNonDemoTxTimestampMs: Long = 0L

    fun observeSettings(): Flow<AppSettingsEntity?> = dao.observeSettings().distinctUntilChanged()
    fun observeAccounts(isDemo: Boolean): Flow<List<AccountEntity>> = dao.observeAccounts(isDemo).distinctUntilChanged()
    fun observeLedgerAccounts(isDemo: Boolean): Flow<List<LedgerAccountEntity>> = dao.observeLedgerAccounts(isDemo).distinctUntilChanged()
    fun observeTransactions(isDemo: Boolean): Flow<List<TransactionEntity>> = dao.observeTransactions(isDemo).distinctUntilChanged()
    fun observeJournalEntries(isDemo: Boolean): Flow<List<JournalEntryEntity>> = dao.observeJournalEntries(isDemo).distinctUntilChanged()
    fun observeJournalLines(isDemo: Boolean): Flow<List<JournalLineEntity>> = dao.observeJournalLines(isDemo).distinctUntilChanged()
    fun observeInvestments(isDemo: Boolean): Flow<List<InvestmentEntity>> = dao.observeInvestments(isDemo).distinctUntilChanged()
    fun observeLoans(isDemo: Boolean): Flow<List<LoanEntity>> = dao.observeLoans(isDemo).distinctUntilChanged()
    fun observeLoanPayments(isDemo: Boolean): Flow<List<LoanPaymentEntity>> = dao.observeLoanPayments(isDemo).distinctUntilChanged()
    fun observeBudgets(isDemo: Boolean): Flow<List<BudgetEntity>> = dao.observeBudgets(isDemo).distinctUntilChanged()
    fun observeGoals(isDemo: Boolean): Flow<List<GoalEntity>> = dao.observeGoals(isDemo).distinctUntilChanged()
    fun observeGiftCards(isDemo: Boolean): Flow<List<GiftCardEntity>> = dao.observeGiftCards(isDemo).distinctUntilChanged()
    fun observeSubscriptions(isDemo: Boolean): Flow<List<SubscriptionEntity>> = dao.observeSubscriptions(isDemo).distinctUntilChanged()
    fun observeTaxRecords(isDemo: Boolean): Flow<List<TaxRecordEntity>> = dao.observeTaxRecords(isDemo).distinctUntilChanged()
    fun observeAuditLogs(isDemo: Boolean): Flow<List<AuditLogEntity>> = dao.observeAuditLogs(isDemo).distinctUntilChanged()

    /**
     * Ensures default settings and the standard Indian Personal Chart of Accounts exist.
     * Never creates fake banks, accounts, or transactions in real mode.
     */
    suspend fun ensureInitialized() {
        database.withTransaction {
            val systemFy = IndianFinancialYear.currentFyLabel()
            val currentSettings = dao.getSettings()
            if (currentSettings == null) {
                dao.upsertSettings(
                    AppSettingsEntity(
                        id = 1,
                        activeFinancialYear = systemFy
                    )
                )
            } else if (currentSettings.activeFinancialYear != systemFy) {
                dao.upsertSettings(currentSettings.copy(activeFinancialYear = systemFy))
            }
            ensureSystemChartOfAccounts(isDemo = false)
            if (currentSettings?.isDemoModeEnabled == true) {
                ensureDemoDatasetUpToDate()
            }
        }
    }

    private suspend fun ensureSystemChartOfAccounts(isDemo: Boolean) {
        val systemDefs = listOf(
            Triple("3001", "Opening Capital", LedgerClassification.EQUITY_OPENING_CAPITAL),
            Triple("3002", "Additional Contributions", LedgerClassification.EQUITY_CONTRIBUTION),
            Triple("3003", "Personal Drawings & Adjustments", LedgerClassification.EQUITY_DRAWINGS),
            Triple("4001", "Salary Income", LedgerClassification.INCOME_SALARY),
            Triple("4002", "Business & Profession Income", LedgerClassification.INCOME_BUSINESS),
            Triple("4003", "Interest Income", LedgerClassification.INCOME_INTEREST),
            Triple("4004", "Dividend Income", LedgerClassification.INCOME_DIVIDEND),
            Triple("4005", "Realized Investment Gains", LedgerClassification.INCOME_CAPITAL_GAIN),
            Triple("4009", "Other Income", LedgerClassification.INCOME_OTHER),
            Triple("5001", "Household Expenses", LedgerClassification.EXPENSE_HOUSEHOLD),
            Triple("5002", "Education Expenses", LedgerClassification.EXPENSE_EDUCATION),
            Triple("5003", "Transportation Expenses", LedgerClassification.EXPENSE_TRANSPORT),
            Triple("5004", "Utilities & Bills", LedgerClassification.EXPENSE_UTILITIES),
            Triple("5005", "Healthcare & Medical", LedgerClassification.EXPENSE_HEALTHCARE),
            Triple("5006", "Interest Expenses", LedgerClassification.EXPENSE_INTEREST),
            Triple("5007", "Bank Charges & Fees", LedgerClassification.EXPENSE_BANK_CHARGES),
            Triple("5008", "Brokerage & Investment Fees", LedgerClassification.EXPENSE_INVESTMENT_FEES),
            Triple("5009", "Taxes & Statutory Payments", LedgerClassification.EXPENSE_TAXES),
            Triple("5010", "Realized Investment Losses", LedgerClassification.EXPENSE_CAPITAL_LOSS),
            Triple("5099", "Other Expenses", LedgerClassification.EXPENSE_OTHER)
        )

        if (initializedChartModes.contains(isDemo)) return

        val existingSystemClasses = dao.getLedgerAccounts(isDemo)
            .asSequence()
            .filter { it.isSystemAccount }
            .map { it.classification }
            .toSet()

        val missingLedgers = systemDefs.mapNotNull { (code, name, classification) ->
            if (classification in existingSystemClasses) null
            else LedgerAccountEntity(
                code = code,
                name = name,
                classification = classification,
                normalBalance = classification.normalBalance,
                isSystemAccount = true,
                isDemo = isDemo
            )
        }
        if (missingLedgers.isNotEmpty()) {
            dao.insertLedgerAccounts(missingLedgers)
        }
        initializedChartModes.add(isDemo)
    }

    private suspend fun getRequiredSystemLedger(
        classification: LedgerClassification,
        isDemo: Boolean
    ): LedgerAccountEntity {
        ensureSystemChartOfAccounts(isDemo)
        return requireNotNull(dao.getSystemLedgerByClassification(classification, isDemo)) {
            "System ledger missing for $classification"
        }
    }

    private suspend fun nextVoucherNumber(dateEpochDay: Long, isDemo: Boolean): String {
        val count = dao.getTransactionCount(isDemo) + 1
        val fy = IndianFinancialYear.getFinancialYearLabel(dateEpochDay).removePrefix("FY ")
        val prefix = if (isDemo) "DEMO-VCH" else "VCH"
        return "$prefix-$fy-${count.toString().padStart(4, '0')}"
    }

    /**
     * Creates a user-entered financial account (Cash, Bank, Savings, Current, UPI, Wallet, Credit Card, etc.),
     * automatically creates its corresponding LedgerAccount in the Chart of Accounts,
     * and if an opening balance > 0 is provided, posts the double-entry Opening Balance voucher.
     */
    suspend fun createAccount(
        name: String,
        accountType: AccountType,
        bankName: String = "",
        branchName: String = "",
        accountNumber: String = "",
        ifscCode: String = "",
        accountHolder: String = "",
        upiId: String = "",
        openingBalancePaise: Long = 0L,
        isOpeningBalanceRecorded: Boolean = false,
        openingDateEpochDay: Long = IndianFinancialYear.todayEpochDay(),
        creditLimitPaise: Long? = null,
        billingDayOfMonth: Int? = null,
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        require(name.isNotBlank()) { "Account name is required." }
        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)
            val accountId = dao.insertAccount(
                AccountEntity(
                    name = name.trim(),
                    accountType = accountType,
                    bankName = bankName.trim(),
                    branchName = branchName.trim(),
                    accountNumber = accountNumber.trim(),
                    ifscCode = ifscCode.trim().uppercase(),
                    accountHolder = accountHolder.trim(),
                    upiId = upiId.trim(),
                    openingBalancePaise = openingBalancePaise,
                    isOpeningBalanceRecorded = isOpeningBalanceRecorded,
                    openingDateEpochDay = openingDateEpochDay,
                    creditLimitPaise = creditLimitPaise,
                    billingDayOfMonth = billingDayOfMonth,
                    notes = notes.trim(),
                    isDemo = isDemo
                )
            )

            val codePrefix = if (accountType == AccountType.CREDIT_CARD) "21" else "11"
            val ledgerId = dao.insertLedgerAccount(
                LedgerAccountEntity(
                    code = "$codePrefix${accountId.toString().padStart(3, '0')}",
                    name = name.trim(),
                    classification = accountType.ledgerClassification,
                    normalBalance = accountType.normalBalance,
                    linkedUserAccountId = accountId,
                    isSystemAccount = false,
                    isDemo = isDemo
                )
            )

            val createdLedger = requireNotNull(dao.getLedgerAccountById(ledgerId))

            if (isOpeningBalanceRecorded && openingBalancePaise > 0L) {
                val openingCapitalLedger = getRequiredSystemLedger(
                    LedgerClassification.EQUITY_OPENING_CAPITAL,
                    isDemo
                )
                val plan = AccountingEngine.buildPostingPlan(
                    transactionType = TransactionType.OPENING_BALANCE,
                    amountPaise = openingBalancePaise,
                    description = "Opening balance for ${name.trim()}",
                    destinationLedger = createdLedger,
                    openingCapitalLedger = openingCapitalLedger
                )
                commitPostingPlan(
                    plan = plan,
                    transactionType = TransactionType.OPENING_BALANCE,
                    dateEpochDay = openingDateEpochDay,
                    amountPaise = openingBalancePaise,
                    sourceAccountId = if (accountType == AccountType.CREDIT_CARD) accountId else null,
                    destinationAccountId = if (accountType != AccountType.CREDIT_CARD) accountId else null,
                    category = "Opening Capital",
                    description = "Opening balance for ${name.trim()}",
                    isDemo = isDemo
                )
            }

            accountId
        }
    }

    suspend fun archiveAccount(accountId: Long, archive: Boolean) {
        val existing = dao.getAccountById(accountId) ?: return
        dao.updateAccount(existing.copy(isArchived = archive))
    }

    /**
     * Records a user transaction and automatically generates the double-entry journal entry,
     * ledger postings, and linked entity updates inside an atomic database transaction.
     */
    suspend fun recordTransaction(
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
        attachmentName: String = "",
        linkedInvestmentId: Long? = null,
        linkedLoanId: Long? = null,
        linkedGiftCardId: Long? = null,
        linkedOriginalTransactionId: Long? = null,
        isDemo: Boolean = false
    ): Long {
        require(amountPaise > 0L) { "Transaction amount must be greater than ₹0." }

        if (!isDemo) {
            val nowMs = System.currentTimeMillis()
            val sig = "$transactionType|$dateEpochDay|$amountPaise|$sourceAccountId|$destinationAccountId|${category.trim()}|${description.trim()}"
            if (sig == lastNonDemoTxSignature && (nowMs - lastNonDemoTxTimestampMs) in 0..1800L) {
                val recent = dao.getTransactions(isDemo = false).lastOrNull()
                if (recent != null) return recent.id
            }
            lastNonDemoTxSignature = sig
            lastNonDemoTxTimestampMs = nowMs
        }

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)

            val sourceUserLedger = sourceAccountId?.let { dao.getLedgerByUserAccountId(it, isDemo) }
            val destUserLedger = destinationAccountId?.let { dao.getLedgerByUserAccountId(it, isDemo) }
            val investmentLedger = linkedInvestmentId?.let { dao.getLedgerByInvestmentId(it, isDemo) }
            val loanLedger = linkedLoanId?.let { dao.getLedgerByLoanId(it, isDemo) }
            val giftCardLedger = linkedGiftCardId?.let { dao.getLedgerByGiftCardId(it, isDemo) }

            val openingCapLedger = getRequiredSystemLedger(LedgerClassification.EQUITY_OPENING_CAPITAL, isDemo)
            val interestExpLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_INTEREST, isDemo)
            val interestIncLedger = getRequiredSystemLedger(LedgerClassification.INCOME_INTEREST, isDemo)
            val investmentFeeLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_INVESTMENT_FEES, isDemo)
            val capitalGainLedger = getRequiredSystemLedger(LedgerClassification.INCOME_CAPITAL_GAIN, isDemo)
            val capitalLossLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_CAPITAL_LOSS, isDemo)

            val categoryClassification = when (transactionType) {
                TransactionType.INCOME -> AccountingEngine.resolveIncomeClassification(category)
                TransactionType.INTEREST_RECEIVED -> LedgerClassification.INCOME_INTEREST
                TransactionType.DIVIDEND_RECEIVED -> LedgerClassification.INCOME_DIVIDEND
                TransactionType.BANK_CHARGE -> LedgerClassification.EXPENSE_BANK_CHARGES
                TransactionType.TAX_PAYMENT -> LedgerClassification.EXPENSE_TAXES
                TransactionType.REFUND_RECEIVED -> {
                    val origTx = linkedOriginalTransactionId?.let { dao.getTransactionById(it) }
                    val origCat = origTx?.category ?: category
                    AccountingEngine.resolveExpenseClassification(origCat)
                }
                else -> AccountingEngine.resolveExpenseClassification(category)
            }
            val categoryLedger = getRequiredSystemLedger(categoryClassification, isDemo)

            val effectiveSourceLedger = when (transactionType) {
                TransactionType.INVESTMENT_SALE,
                TransactionType.FD_MATURITY,
                TransactionType.RD_MATURITY -> investmentLedger ?: sourceUserLedger
                TransactionType.LOAN_RECEIVED -> loanLedger ?: sourceUserLedger
                TransactionType.GIFT_CARD_USAGE -> giftCardLedger ?: sourceUserLedger
                else -> sourceUserLedger
            }

            val effectiveDestLedger = when (transactionType) {
                TransactionType.INVESTMENT_PURCHASE,
                TransactionType.RD_CONTRIBUTION -> investmentLedger ?: destUserLedger
                TransactionType.LOAN_REPAYMENT -> loanLedger ?: destUserLedger
                TransactionType.GIFT_CARD_PURCHASE -> giftCardLedger ?: destUserLedger
                else -> destUserLedger
            }

            val plan = AccountingEngine.buildPostingPlan(
                transactionType = transactionType,
                amountPaise = amountPaise,
                principalComponentPaise = principalComponentPaise,
                interestComponentPaise = interestComponentPaise,
                chargesPaise = chargesPaise,
                costBasisDisposedPaise = costBasisDisposedPaise,
                description = description.ifBlank { category.ifBlank { transactionType.userLabel } },
                sourceLedger = effectiveSourceLedger,
                destinationLedger = effectiveDestLedger,
                categoryLedger = categoryLedger,
                openingCapitalLedger = openingCapLedger,
                interestExpenseLedger = interestExpLedger,
                interestIncomeLedger = interestIncLedger,
                investmentFeeLedger = investmentFeeLedger,
                capitalGainLedger = capitalGainLedger,
                capitalLossLedger = capitalLossLedger
            )

            val txId = commitPostingPlan(
                plan = plan,
                transactionType = transactionType,
                dateEpochDay = dateEpochDay,
                amountPaise = amountPaise,
                principalComponentPaise = principalComponentPaise,
                interestComponentPaise = interestComponentPaise,
                chargesPaise = chargesPaise,
                costBasisDisposedPaise = costBasisDisposedPaise,
                sourceAccountId = sourceAccountId,
                destinationAccountId = destinationAccountId,
                category = category.ifBlank { transactionType.userLabel },
                description = description.ifBlank { category.ifBlank { transactionType.userLabel } },
                referenceNumber = referenceNumber,
                notes = notes,
                locationName = locationName,
                latitude = latitude,
                longitude = longitude,
                attachmentName = attachmentName,
                linkedInvestmentId = linkedInvestmentId,
                linkedLoanId = linkedLoanId,
                linkedGiftCardId = linkedGiftCardId,
                linkedOriginalTransactionId = linkedOriginalTransactionId,
                isDemo = isDemo
            )

            // Update linked domain entities (Investments, Loans, Gift Cards)
            if (linkedInvestmentId != null) {
                val inv = dao.getInvestmentById(linkedInvestmentId)
                if (inv != null) {
                    when (transactionType) {
                        TransactionType.INVESTMENT_PURCHASE,
                        TransactionType.RD_CONTRIBUTION -> {
                            val newInvested = inv.investedAmountPaise + amountPaise
                            val newCurrent = inv.currentRecordedValuePaise?.plus(amountPaise)
                            dao.updateInvestment(
                                inv.copy(
                                    investedAmountPaise = newInvested,
                                    chargesPaise = inv.chargesPaise + chargesPaise,
                                    currentRecordedValuePaise = newCurrent
                                )
                            )
                        }
                        TransactionType.INVESTMENT_SALE -> {
                            val costDisposed = if (costBasisDisposedPaise > 0L) costBasisDisposedPaise else amountPaise
                            val gain = amountPaise - costDisposed - chargesPaise
                            val remainingInvested = (inv.investedAmountPaise - costDisposed).coerceAtLeast(0L)
                            val updatedCurrent = inv.currentRecordedValuePaise?.let { (it - amountPaise).coerceAtLeast(0L) }
                            dao.updateInvestment(
                                inv.copy(
                                    investedAmountPaise = remainingInvested,
                                    currentRecordedValuePaise = updatedCurrent,
                                    realizedProfitLossPaise = inv.realizedProfitLossPaise + gain,
                                    isFullyRedeemed = remainingInvested == 0L
                                )
                            )
                        }
                        TransactionType.FD_MATURITY,
                        TransactionType.RD_MATURITY -> {
                            val principal = if (principalComponentPaise > 0L) principalComponentPaise else inv.investedAmountPaise
                            val interest = (amountPaise - principal).coerceAtLeast(0L)
                            val remaining = (inv.investedAmountPaise - principal).coerceAtLeast(0L)
                            dao.updateInvestment(
                                inv.copy(
                                    investedAmountPaise = remaining,
                                    currentRecordedValuePaise = if (remaining == 0L) 0L else inv.currentRecordedValuePaise,
                                    interestEarnedPaise = inv.interestEarnedPaise + interest,
                                    isFullyRedeemed = remaining == 0L
                                )
                            )
                        }
                        TransactionType.INTEREST_RECEIVED -> {
                            dao.updateInvestment(inv.copy(interestEarnedPaise = inv.interestEarnedPaise + amountPaise))
                        }
                        TransactionType.DIVIDEND_RECEIVED -> {
                            dao.updateInvestment(inv.copy(dividendsReceivedPaise = inv.dividendsReceivedPaise + amountPaise))
                        }
                        else -> {}
                    }
                }
            }

            if (linkedLoanId != null && transactionType == TransactionType.LOAN_REPAYMENT) {
                val loan = dao.getLoanById(linkedLoanId)
                if (loan != null) {
                    val principalPaid = if (principalComponentPaise > 0L || interestComponentPaise > 0L) {
                        principalComponentPaise
                    } else {
                        amountPaise
                    }
                    val interestPaid = if (principalComponentPaise > 0L || interestComponentPaise > 0L) {
                        interestComponentPaise
                    } else {
                        0L
                    }
                    val newRemaining = (loan.remainingPrincipalPaise - principalPaid).coerceAtLeast(0L)
                    val newRemainingInst = (loan.remainingInstallments - 1).coerceAtLeast(0)
                    dao.updateLoan(
                        loan.copy(
                            remainingPrincipalPaise = newRemaining,
                            remainingInstallments = if (newRemaining == 0L) 0 else newRemainingInst,
                            totalInterestPaidPaise = loan.totalInterestPaidPaise + interestPaid,
                            isClosed = newRemaining == 0L
                        )
                    )
                    dao.insertLoanPayment(
                        LoanPaymentEntity(
                            loanId = loan.id,
                            transactionId = txId,
                            dateEpochDay = dateEpochDay,
                            totalPaymentPaise = amountPaise,
                            principalComponentPaise = principalPaid,
                            interestComponentPaise = interestPaid,
                            remainingPrincipalAfterPaise = newRemaining,
                            isDemo = isDemo
                        )
                    )
                }
            }

            if (linkedGiftCardId != null) {
                val card = dao.getGiftCardById(linkedGiftCardId)
                if (card != null) {
                    if (transactionType == TransactionType.GIFT_CARD_USAGE) {
                        dao.updateGiftCard(
                            card.copy(remainingValuePaise = (card.remainingValuePaise - amountPaise).coerceAtLeast(0L))
                        )
                    } else if (transactionType == TransactionType.GIFT_CARD_PURCHASE) {
                        dao.updateGiftCard(
                            card.copy(remainingValuePaise = card.remainingValuePaise + amountPaise)
                        )
                    }
                }
            }

            txId
        }
    }

    private suspend fun commitPostingPlan(
        plan: AccountingEngine.ValidatedPostingPlan,
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
        attachmentName: String = "",
        linkedInvestmentId: Long? = null,
        linkedLoanId: Long? = null,
        linkedGiftCardId: Long? = null,
        linkedOriginalTransactionId: Long? = null,
        isDemo: Boolean = false
    ): Long {
        val voucherNo = nextVoucherNumber(dateEpochDay, isDemo)
        val fyLabel = IndianFinancialYear.getFinancialYearLabel(dateEpochDay)

        val transactionId = dao.insertTransaction(
            TransactionEntity(
                voucherNumber = voucherNo,
                dateEpochDay = dateEpochDay,
                amountPaise = amountPaise,
                principalComponentPaise = principalComponentPaise,
                interestComponentPaise = interestComponentPaise,
                chargesPaise = chargesPaise,
                costBasisDisposedPaise = costBasisDisposedPaise,
                transactionType = transactionType,
                sourceAccountId = sourceAccountId,
                destinationAccountId = destinationAccountId,
                category = category,
                description = description,
                referenceNumber = referenceNumber.trim(),
                notes = notes.trim(),
                locationName = locationName.trim(),
                latitude = latitude,
                longitude = longitude,
                attachmentName = attachmentName.trim(),
                linkedInvestmentId = linkedInvestmentId,
                linkedLoanId = linkedLoanId,
                linkedGiftCardId = linkedGiftCardId,
                linkedOriginalTransactionId = linkedOriginalTransactionId,
                isDemo = isDemo
            )
        )

        val journalEntryId = dao.insertJournalEntry(
            JournalEntryEntity(
                transactionId = transactionId,
                voucherNumber = voucherNo,
                dateEpochDay = dateEpochDay,
                narration = plan.narration,
                financialYear = fyLabel,
                postingStatus = PostingStatus.POSTED,
                isDemo = isDemo
            )
        )

        val journalLines = plan.lines.map { draft ->
            JournalLineEntity(
                journalEntryId = journalEntryId,
                transactionId = transactionId,
                voucherNumber = voucherNo,
                dateEpochDay = dateEpochDay,
                ledgerAccountId = draft.ledgerAccount.id,
                ledgerAccountName = draft.ledgerAccount.name,
                ledgerFolio = "${draft.ledgerAccount.classification.folioPrefix}-${draft.ledgerAccount.id}",
                debitPaise = draft.debitPaise,
                creditPaise = draft.creditPaise,
                lineNarration = draft.narration,
                isDemo = isDemo
            )
        }
        dao.insertJournalLines(journalLines)

        dao.insertAuditLog(
            AuditLogEntity(
                action = "VOUCHER_POSTED",
                entityType = "Transaction",
                entityId = transactionId,
                summary = "$voucherNo • ${transactionType.userLabel} • ${InrMoney.formatPaise(amountPaise)} • $description",
                isDemo = isDemo
            )
        )

        return transactionId
    }

    /**
     * Reverses an existing transaction by marking it reversed in the ledger history,
     * generating an offsetting double-entry journal entry, and restoring linked entity balances.
     */
    suspend fun reverseTransaction(transactionId: Long) {
        database.withTransaction {
            val origTx = dao.getTransactionById(transactionId) ?: return@withTransaction
            if (origTx.isReversed) return@withTransaction
            val origEntry = dao.getJournalEntryForTransaction(transactionId)
            val allLedgers = dao.getLedgerAccounts(origTx.isDemo).associateBy { it.id }

            dao.updateTransaction(origTx.copy(isReversed = true))

            val reversalVoucher = nextVoucherNumber(IndianFinancialYear.todayEpochDay(), origTx.isDemo)
            if (origEntry != null) {
                val origLines = dao.getLinesForJournalEntry(origEntry.id)
                val reversalPlan = AccountingEngine.buildReversalPlan(
                    originalVoucherNumber = origTx.voucherNumber,
                    originalLines = origLines,
                    ledgerById = allLedgers
                )

                dao.updateJournalEntry(origEntry.copy(postingStatus = PostingStatus.REVERSED))

                val revEntryId = dao.insertJournalEntry(
                    JournalEntryEntity(
                        transactionId = origTx.id,
                        voucherNumber = reversalVoucher,
                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                        narration = reversalPlan.narration,
                        financialYear = IndianFinancialYear.currentFyLabel(),
                        postingStatus = PostingStatus.POSTED,
                        isReversal = true,
                        reversedJournalEntryId = origEntry.id,
                        isDemo = origTx.isDemo
                    )
                )

                val revLines = reversalPlan.lines.map { draft ->
                    JournalLineEntity(
                        journalEntryId = revEntryId,
                        transactionId = origTx.id,
                        voucherNumber = reversalVoucher,
                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                        ledgerAccountId = draft.ledgerAccount.id,
                        ledgerAccountName = draft.ledgerAccount.name,
                        ledgerFolio = "${draft.ledgerAccount.classification.folioPrefix}-${draft.ledgerAccount.id}",
                        debitPaise = draft.debitPaise,
                        creditPaise = draft.creditPaise,
                        lineNarration = draft.narration,
                        isDemo = origTx.isDemo
                    )
                }
                dao.insertJournalLines(revLines)
            }

            // Restore linked Investment, Loan, or Gift Card balances when reversed
            origTx.linkedInvestmentId?.let { invId ->
                val inv = dao.getInvestmentById(invId)
                if (inv != null) {
                    when (origTx.transactionType) {
                        TransactionType.INVESTMENT_PURCHASE,
                        TransactionType.RD_CONTRIBUTION -> {
                            val newInvested = (inv.investedAmountPaise - origTx.amountPaise).coerceAtLeast(0L)
                            val newCurrent = inv.currentRecordedValuePaise?.let { (it - origTx.amountPaise).coerceAtLeast(0L) }
                            dao.updateInvestment(
                                inv.copy(
                                    investedAmountPaise = newInvested,
                                    chargesPaise = (inv.chargesPaise - origTx.chargesPaise).coerceAtLeast(0L),
                                    currentRecordedValuePaise = newCurrent
                                )
                            )
                        }
                        TransactionType.INVESTMENT_SALE -> {
                            val costDisposed = if (origTx.costBasisDisposedPaise > 0L) origTx.costBasisDisposedPaise else origTx.amountPaise
                            val gain = origTx.amountPaise - costDisposed - origTx.chargesPaise
                            val restoredInvested = inv.investedAmountPaise + costDisposed
                            val restoredCurrent = inv.currentRecordedValuePaise?.plus(origTx.amountPaise)
                            dao.updateInvestment(
                                inv.copy(
                                    investedAmountPaise = restoredInvested,
                                    currentRecordedValuePaise = restoredCurrent,
                                    realizedProfitLossPaise = inv.realizedProfitLossPaise - gain,
                                    isFullyRedeemed = false
                                )
                            )
                        }
                        TransactionType.INTEREST_RECEIVED -> {
                            dao.updateInvestment(inv.copy(interestEarnedPaise = (inv.interestEarnedPaise - origTx.amountPaise).coerceAtLeast(0L)))
                        }
                        TransactionType.DIVIDEND_RECEIVED -> {
                            dao.updateInvestment(inv.copy(dividendsReceivedPaise = (inv.dividendsReceivedPaise - origTx.amountPaise).coerceAtLeast(0L)))
                        }
                        else -> {}
                    }
                }
            }

            origTx.linkedLoanId?.let { loanId ->
                if (origTx.transactionType == TransactionType.LOAN_REPAYMENT) {
                    val loan = dao.getLoanById(loanId)
                    if (loan != null) {
                        val principalPaid = if (origTx.principalComponentPaise > 0L || origTx.interestComponentPaise > 0L) {
                            origTx.principalComponentPaise
                        } else {
                            origTx.amountPaise
                        }
                        val interestPaid = if (origTx.principalComponentPaise > 0L || origTx.interestComponentPaise > 0L) {
                            origTx.interestComponentPaise
                        } else {
                            0L
                        }
                        val restoredRemaining = loan.remainingPrincipalPaise + principalPaid
                        dao.updateLoan(
                            loan.copy(
                                remainingPrincipalPaise = restoredRemaining,
                                remainingInstallments = (loan.remainingInstallments + 1).coerceAtMost(loan.tenureMonths),
                                totalInterestPaidPaise = (loan.totalInterestPaidPaise - interestPaid).coerceAtLeast(0L),
                                isClosed = false
                            )
                        )
                    }
                }
            }

            origTx.linkedGiftCardId?.let { gcId ->
                val card = dao.getGiftCardById(gcId)
                if (card != null) {
                    if (origTx.transactionType == TransactionType.GIFT_CARD_USAGE) {
                        dao.updateGiftCard(
                            card.copy(remainingValuePaise = card.remainingValuePaise + origTx.amountPaise)
                        )
                    } else if (origTx.transactionType == TransactionType.GIFT_CARD_PURCHASE) {
                        dao.updateGiftCard(
                            card.copy(remainingValuePaise = (card.remainingValuePaise - origTx.amountPaise).coerceAtLeast(0L))
                        )
                    }
                }
            }

            dao.insertAuditLog(
                AuditLogEntity(
                    action = "VOUCHER_REVERSED",
                    entityType = "Transaction",
                    entityId = origTx.id,
                    summary = "Reversed ${origTx.voucherNumber} (${InrMoney.formatPaise(origTx.amountPaise)}) • ${origTx.description}",
                    isDemo = origTx.isDemo
                )
            )
        }
    }

    /**
     * Reverses multiple selected transactions atomically in a single SQLite transaction
     * by posting offsetting double-entry journal vouchers.
     */
    suspend fun reverseTransactionsBulk(transactionIds: Set<Long>): Int {
        if (transactionIds.isEmpty()) return 0
        var count = 0
        database.withTransaction {
            val txs = dao.getTransactionsByIds(transactionIds.toList())
            for (origTx in txs) {
                if (!origTx.isReversed) {
                    reverseTransaction(origTx.id)
                    count++
                }
            }
        }
        return count
    }

    /**
     * Updates the category for multiple selected transactions and synchronizes their income/expense
     * double-entry journal lines so all financial statements remain reconciled.
     */
    suspend fun categorizeTransactionsBulk(transactionIds: Set<Long>, newCategory: String): Int {
        val cleanCategory = newCategory.trim()
        if (cleanCategory.isEmpty() || transactionIds.isEmpty()) return 0
        var updatedCount = 0
        database.withTransaction {
            for (id in transactionIds) {
                val tx = dao.getTransactionById(id) ?: continue
                dao.updateTransaction(tx.copy(category = cleanCategory))

                if (tx.transactionType == TransactionType.EXPENSE || tx.transactionType == TransactionType.INCOME) {
                    ensureSystemChartOfAccounts(tx.isDemo)
                    val targetClass = if (tx.transactionType == TransactionType.INCOME) {
                        AccountingEngine.resolveIncomeClassification(cleanCategory)
                    } else {
                        AccountingEngine.resolveExpenseClassification(cleanCategory)
                    }
                    val targetLedger = getRequiredSystemLedger(targetClass, tx.isDemo)
                    val entry = dao.getJournalEntryForTransaction(tx.id)
                    if (entry != null) {
                        val lines = dao.getLinesForJournalEntry(entry.id)
                        val allLedgers = dao.getLedgerAccounts(tx.isDemo).associateBy { it.id }
                        for (line in lines) {
                            val lineLedger = allLedgers[line.ledgerAccountId]
                            val isIncomeOrExpenseLine = lineLedger?.classification?.name?.let {
                                it.startsWith("INCOME_") || it.startsWith("EXPENSE_")
                            } ?: false
                            if (isIncomeOrExpenseLine) {
                                dao.updateJournalLine(
                                    line.copy(
                                        ledgerAccountId = targetLedger.id,
                                        ledgerAccountName = targetLedger.name,
                                        ledgerFolio = "${targetLedger.classification.folioPrefix}-${targetLedger.id}"
                                    )
                                )
                            }
                        }
                    }
                }

                updatedCount++
            }
        }
        return updatedCount
    }

    /**
     * Atomically edits an existing transaction (amount, date, category, description, location, or accounts)
     * and rebuilds its double-entry journal lines so the General Ledger and all financial statements remain 100% reconciled.
     */
    suspend fun editTransaction(
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
        require(amountPaise > 0L) { "Transaction amount must be greater than ₹0." }
        database.withTransaction {
            val origTx = dao.getTransactionById(transactionId) ?: return@withTransaction
            require(!origTx.isReversed) { "Reversed transactions cannot be edited." }
            val isDemo = origTx.isDemo
            ensureSystemChartOfAccounts(isDemo)

            val sourceUserLedger = sourceAccountId?.let { dao.getLedgerByUserAccountId(it, isDemo) }
            val destUserLedger = destinationAccountId?.let { dao.getLedgerByUserAccountId(it, isDemo) }
            val investmentLedger = origTx.linkedInvestmentId?.let { dao.getLedgerByInvestmentId(it, isDemo) }
            val loanLedger = origTx.linkedLoanId?.let { dao.getLedgerByLoanId(it, isDemo) }
            val giftCardLedger = origTx.linkedGiftCardId?.let { dao.getLedgerByGiftCardId(it, isDemo) }

            val openingCapLedger = getRequiredSystemLedger(LedgerClassification.EQUITY_OPENING_CAPITAL, isDemo)
            val interestExpLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_INTEREST, isDemo)
            val interestIncLedger = getRequiredSystemLedger(LedgerClassification.INCOME_INTEREST, isDemo)
            val investmentFeeLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_INVESTMENT_FEES, isDemo)
            val capitalGainLedger = getRequiredSystemLedger(LedgerClassification.INCOME_CAPITAL_GAIN, isDemo)
            val capitalLossLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_CAPITAL_LOSS, isDemo)

            val cleanCat = category.trim().ifBlank { origTx.category }
            val cleanDesc = description.trim().ifBlank { cleanCat.ifBlank { origTx.transactionType.userLabel } }

            val categoryClassification = when (origTx.transactionType) {
                TransactionType.INCOME -> AccountingEngine.resolveIncomeClassification(cleanCat)
                TransactionType.INTEREST_RECEIVED -> LedgerClassification.INCOME_INTEREST
                TransactionType.DIVIDEND_RECEIVED -> LedgerClassification.INCOME_DIVIDEND
                TransactionType.BANK_CHARGE -> LedgerClassification.EXPENSE_BANK_CHARGES
                TransactionType.TAX_PAYMENT -> LedgerClassification.EXPENSE_TAXES
                TransactionType.REFUND_RECEIVED -> AccountingEngine.resolveExpenseClassification(cleanCat)
                else -> AccountingEngine.resolveExpenseClassification(cleanCat)
            }
            val categoryLedger = getRequiredSystemLedger(categoryClassification, isDemo)

            val effectiveSourceLedger = when (origTx.transactionType) {
                TransactionType.INVESTMENT_SALE,
                TransactionType.FD_MATURITY,
                TransactionType.RD_MATURITY -> investmentLedger ?: sourceUserLedger
                TransactionType.LOAN_RECEIVED -> loanLedger ?: sourceUserLedger
                TransactionType.GIFT_CARD_USAGE -> giftCardLedger ?: sourceUserLedger
                else -> sourceUserLedger
            }

            val effectiveDestLedger = when (origTx.transactionType) {
                TransactionType.INVESTMENT_PURCHASE,
                TransactionType.RD_CONTRIBUTION -> investmentLedger ?: destUserLedger
                TransactionType.LOAN_REPAYMENT -> loanLedger ?: destUserLedger
                TransactionType.GIFT_CARD_PURCHASE -> giftCardLedger ?: destUserLedger
                else -> destUserLedger
            }

            val plan = AccountingEngine.buildPostingPlan(
                transactionType = origTx.transactionType,
                amountPaise = amountPaise,
                principalComponentPaise = origTx.principalComponentPaise,
                interestComponentPaise = origTx.interestComponentPaise,
                chargesPaise = origTx.chargesPaise,
                costBasisDisposedPaise = origTx.costBasisDisposedPaise,
                description = cleanDesc,
                sourceLedger = effectiveSourceLedger,
                destinationLedger = effectiveDestLedger,
                categoryLedger = categoryLedger,
                openingCapitalLedger = openingCapLedger,
                interestExpenseLedger = interestExpLedger,
                interestIncomeLedger = interestIncLedger,
                investmentFeeLedger = investmentFeeLedger,
                capitalGainLedger = capitalGainLedger,
                capitalLossLedger = capitalLossLedger
            )

            dao.updateTransaction(
                origTx.copy(
                    dateEpochDay = dateEpochDay,
                    amountPaise = amountPaise,
                    sourceAccountId = sourceAccountId,
                    destinationAccountId = destinationAccountId,
                    category = cleanCat,
                    description = cleanDesc,
                    locationName = locationName.trim(),
                    notes = notes.trim()
                )
            )

            val entry = dao.getJournalEntryForTransaction(origTx.id)
            if (entry != null) {
                val fyLabel = IndianFinancialYear.getFinancialYearLabel(dateEpochDay)
                dao.updateJournalEntry(
                    entry.copy(
                        dateEpochDay = dateEpochDay,
                        narration = plan.narration,
                        financialYear = fyLabel
                    )
                )
                dao.deleteLinesForJournalEntry(entry.id)
                val updatedLines = plan.lines.map { draft ->
                    JournalLineEntity(
                        journalEntryId = entry.id,
                        transactionId = origTx.id,
                        voucherNumber = origTx.voucherNumber,
                        dateEpochDay = dateEpochDay,
                        ledgerAccountId = draft.ledgerAccount.id,
                        ledgerAccountName = draft.ledgerAccount.name,
                        ledgerFolio = "${draft.ledgerAccount.classification.folioPrefix}-${draft.ledgerAccount.id}",
                        debitPaise = draft.debitPaise,
                        creditPaise = draft.creditPaise,
                        lineNarration = draft.narration,
                        isDemo = isDemo
                    )
                }
                dao.insertJournalLines(updatedLines)
            }

            dao.insertAuditLog(
                AuditLogEntity(
                    action = "VOUCHER_UPDATED",
                    entityType = "Transaction",
                    entityId = origTx.id,
                    summary = "Updated ${origTx.voucherNumber} • ${InrMoney.formatPaise(amountPaise)} • $cleanDesc",
                    isDemo = isDemo
                )
            )
        }
    }

    /**
     * Adds an investment holding, creates its dedicated Investment Asset ledger account,
     * and posts either an INVESTMENT_PURCHASE (if paid from a bank/cash account)
     * or an OPENING_BALANCE entry (if existing holding brought into Finance Vault).
     */
    suspend fun addInvestment(
        name: String,
        category: InvestmentCategory,
        purchaseDateEpochDay: Long,
        quantity: Double,
        units: Double,
        purchasePricePerUnitPaise: Long,
        investedAmountPaise: Long,
        chargesPaise: Long = 0L,
        currentRecordedValuePaise: Long? = null,
        isCurrentValueRecorded: Boolean = false,
        paymentAccountId: Long? = null,
        maturityDateEpochDay: Long? = null,
        annualInterestRatePercent: Double? = null,
        compoundingFrequency: CompoundingFrequency = CompoundingFrequency.QUARTERLY,
        durationMonths: Int? = null,
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        require(name.isNotBlank()) { "Investment name is required." }
        require(investedAmountPaise > 0L) { "Invested amount must be greater than ₹0." }

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)

            val invId = dao.insertInvestment(
                InvestmentEntity(
                    name = name.trim(),
                    category = category,
                    purchaseDateEpochDay = purchaseDateEpochDay,
                    quantity = quantity,
                    units = units,
                    purchasePricePerUnitPaise = purchasePricePerUnitPaise,
                    investedAmountPaise = investedAmountPaise,
                    chargesPaise = chargesPaise,
                    currentRecordedValuePaise = currentRecordedValuePaise,
                    isCurrentValueRecorded = isCurrentValueRecorded,
                    linkedPaymentAccountId = paymentAccountId,
                    maturityDateEpochDay = maturityDateEpochDay,
                    annualInterestRatePercent = annualInterestRatePercent,
                    compoundingFrequency = compoundingFrequency,
                    durationMonths = durationMonths,
                    notes = notes.trim(),
                    isDemo = isDemo
                )
            )

            val ledgerId = dao.insertLedgerAccount(
                LedgerAccountEntity(
                    code = "12${invId.toString().padStart(3, '0')}",
                    name = "${name.trim()} (${category.displayName})",
                    classification = LedgerClassification.ASSET_NON_CURRENT_INVESTMENT,
                    normalBalance = NormalBalance.DEBIT,
                    linkedInvestmentId = invId,
                    isSystemAccount = false,
                    isDemo = isDemo
                )
            )
            val invLedger = requireNotNull(dao.getLedgerAccountById(ledgerId))

            if (paymentAccountId != null) {
                val srcLedger = requireNotNull(dao.getLedgerByUserAccountId(paymentAccountId, isDemo)) {
                    "Selected payment account ledger not found."
                }
                val feeLedger = getRequiredSystemLedger(LedgerClassification.EXPENSE_INVESTMENT_FEES, isDemo)
                val plan = AccountingEngine.buildPostingPlan(
                    transactionType = TransactionType.INVESTMENT_PURCHASE,
                    amountPaise = investedAmountPaise,
                    chargesPaise = chargesPaise,
                    description = "Investment purchase: ${name.trim()}",
                    sourceLedger = srcLedger,
                    destinationLedger = invLedger,
                    investmentFeeLedger = feeLedger
                )
                commitPostingPlan(
                    plan = plan,
                    transactionType = TransactionType.INVESTMENT_PURCHASE,
                    dateEpochDay = purchaseDateEpochDay,
                    amountPaise = investedAmountPaise,
                    chargesPaise = chargesPaise,
                    sourceAccountId = paymentAccountId,
                    category = category.displayName,
                    description = "Investment purchase: ${name.trim()}",
                    linkedInvestmentId = invId,
                    isDemo = isDemo
                )
            } else {
                val capLedger = getRequiredSystemLedger(LedgerClassification.EQUITY_OPENING_CAPITAL, isDemo)
                val plan = AccountingEngine.buildPostingPlan(
                    transactionType = TransactionType.OPENING_BALANCE,
                    amountPaise = investedAmountPaise,
                    description = "Opening investment holding: ${name.trim()}",
                    destinationLedger = invLedger,
                    openingCapitalLedger = capLedger
                )
                commitPostingPlan(
                    plan = plan,
                    transactionType = TransactionType.OPENING_BALANCE,
                    dateEpochDay = purchaseDateEpochDay,
                    amountPaise = investedAmountPaise,
                    category = category.displayName,
                    description = "Opening investment holding: ${name.trim()}",
                    linkedInvestmentId = invId,
                    isDemo = isDemo
                )
            }

            invId
        }
    }

    suspend fun updateInvestmentRecordedValue(investmentId: Long, newRecordedValuePaise: Long?) {
        val existing = dao.getInvestmentById(investmentId) ?: return
        dao.updateInvestment(
            existing.copy(
                currentRecordedValuePaise = newRecordedValuePaise,
                isCurrentValueRecorded = newRecordedValuePaise != null
            )
        )
        if (newRecordedValuePaise != null) {
            dao.insertAuditLog(
                AuditLogEntity(
                    action = "INVESTMENT_VALUATION_UPDATED",
                    entityType = "Investment",
                    entityId = investmentId,
                    summary = "Updated valuation for '${existing.name}' to ${InrMoney.formatPaise(newRecordedValuePaise)}",
                    isDemo = existing.isDemo
                )
            )
        }
    }

    /**
     * Adds a loan, calculates its reducing-balance EMI schedule, creates its Loan Liability ledger,
     * and posts either a LOAN_RECEIVED entry (if credited to a bank account) or an OPENING_BALANCE liability entry.
     */
    suspend fun addLoan(
        name: String,
        loanType: LoanType,
        lenderName: String,
        principalPaise: Long,
        annualInterestRatePercent: Double,
        tenureMonths: Int,
        startDateEpochDay: Long,
        disbursedToAccountId: Long? = null,
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        require(name.isNotBlank()) { "Loan name is required." }
        require(principalPaise > 0L) { "Loan principal must be greater than ₹0." }
        require(tenureMonths > 0) { "Loan tenure must be at least 1 month." }

        val calc = FinancialCalculators.calculateLoanAmortization(
            principalPaise = principalPaise,
            annualInterestRatePercent = annualInterestRatePercent,
            tenureMonths = tenureMonths
        )

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)

            val loanId = dao.insertLoan(
                LoanEntity(
                    name = name.trim(),
                    loanType = loanType,
                    lenderName = lenderName.trim(),
                    principalPaise = principalPaise,
                    remainingPrincipalPaise = principalPaise,
                    annualInterestRatePercent = annualInterestRatePercent,
                    tenureMonths = tenureMonths,
                    remainingInstallments = tenureMonths,
                    startDateEpochDay = startDateEpochDay,
                    emiAmountPaise = calc.monthlyEmiPaise,
                    linkedBankAccountId = disbursedToAccountId,
                    notes = notes.trim(),
                    isDemo = isDemo
                )
            )

            val ledgerId = dao.insertLedgerAccount(
                LedgerAccountEntity(
                    code = "22${loanId.toString().padStart(3, '0')}",
                    name = "${name.trim()} (${loanType.displayName})",
                    classification = LedgerClassification.LIABILITY_NON_CURRENT_LOAN,
                    normalBalance = NormalBalance.CREDIT,
                    linkedLoanId = loanId,
                    isSystemAccount = false,
                    isDemo = isDemo
                )
            )
            val loanLedger = requireNotNull(dao.getLedgerAccountById(ledgerId))

            if (disbursedToAccountId != null) {
                val bankLedger = requireNotNull(dao.getLedgerByUserAccountId(disbursedToAccountId, isDemo)) {
                    "Destination bank account ledger not found."
                }
                val plan = AccountingEngine.buildPostingPlan(
                    transactionType = TransactionType.LOAN_RECEIVED,
                    amountPaise = principalPaise,
                    description = "Loan received: ${name.trim()}",
                    sourceLedger = loanLedger,
                    destinationLedger = bankLedger
                )
                commitPostingPlan(
                    plan = plan,
                    transactionType = TransactionType.LOAN_RECEIVED,
                    dateEpochDay = startDateEpochDay,
                    amountPaise = principalPaise,
                    destinationAccountId = disbursedToAccountId,
                    category = loanType.displayName,
                    description = "Loan received: ${name.trim()}",
                    linkedLoanId = loanId,
                    isDemo = isDemo
                )
            } else {
                val capLedger = getRequiredSystemLedger(LedgerClassification.EQUITY_OPENING_CAPITAL, isDemo)
                val plan = AccountingEngine.buildPostingPlan(
                    transactionType = TransactionType.OPENING_BALANCE,
                    amountPaise = principalPaise,
                    description = "Existing loan recorded: ${name.trim()}",
                    destinationLedger = loanLedger,
                    openingCapitalLedger = capLedger
                )
                commitPostingPlan(
                    plan = plan,
                    transactionType = TransactionType.OPENING_BALANCE,
                    dateEpochDay = startDateEpochDay,
                    amountPaise = principalPaise,
                    category = loanType.displayName,
                    description = "Existing loan recorded: ${name.trim()}",
                    linkedLoanId = loanId,
                    isDemo = isDemo
                )
            }

            loanId
        }
    }

    /**
     * Adds a Gift Card, creates its Asset ledger (`ASSET_CURRENT_OTHER`), and posts the double-entry record.
     */
    suspend fun addGiftCard(
        brand: String,
        name: String,
        initialValuePaise: Long,
        remainingValuePaise: Long,
        purchaseDateEpochDay: Long,
        expirationDateEpochDay: Long? = null,
        cardNumberRaw: String = "",
        redemptionPinRaw: String = "",
        purchasedFromAccountId: Long? = null,
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        require(brand.isNotBlank()) { "Gift card brand is required." }
        require(initialValuePaise > 0L) { "Initial gift card value must be > ₹0." }

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)

            val gcId = dao.insertGiftCard(
                GiftCardEntity(
                    brand = brand.trim(),
                    name = name.trim().ifBlank { "${brand.trim()} Gift Card" },
                    initialValuePaise = initialValuePaise,
                    remainingValuePaise = remainingValuePaise,
                    purchaseDateEpochDay = purchaseDateEpochDay,
                    expirationDateEpochDay = expirationDateEpochDay,
                    cardNumberMasked = PinSecurityUtil.maskAccountNumber(cardNumberRaw),
                    redemptionCodeProtected = if (redemptionPinRaw.isBlank()) {
                        "Not provided"
                    } else {
                        VaultKeystoreCipher.encryptSensitiveField(redemptionPinRaw.trim())
                    },
                    notes = notes.trim(),
                    isDemo = isDemo
                )
            )

            val ledgerId = dao.insertLedgerAccount(
                LedgerAccountEntity(
                    code = "13${gcId.toString().padStart(3, '0')}",
                    name = "Gift Card: ${brand.trim()}",
                    classification = LedgerClassification.ASSET_CURRENT_OTHER,
                    normalBalance = NormalBalance.DEBIT,
                    linkedGiftCardId = gcId,
                    isSystemAccount = false,
                    isDemo = isDemo
                )
            )
            val gcLedger = requireNotNull(dao.getLedgerAccountById(ledgerId))

            if (remainingValuePaise > 0L) {
                if (purchasedFromAccountId != null) {
                    val srcLedger = requireNotNull(dao.getLedgerByUserAccountId(purchasedFromAccountId, isDemo))
                    val plan = AccountingEngine.buildPostingPlan(
                        transactionType = TransactionType.GIFT_CARD_PURCHASE,
                        amountPaise = remainingValuePaise,
                        description = "Purchased ${brand.trim()} Gift Card",
                        sourceLedger = srcLedger,
                        destinationLedger = gcLedger
                    )
                    commitPostingPlan(
                        plan = plan,
                        transactionType = TransactionType.GIFT_CARD_PURCHASE,
                        dateEpochDay = purchaseDateEpochDay,
                        amountPaise = remainingValuePaise,
                        sourceAccountId = purchasedFromAccountId,
                        category = "Gift Card",
                        description = "Purchased ${brand.trim()} Gift Card",
                        linkedGiftCardId = gcId,
                        isDemo = isDemo
                    )
                } else {
                    val capLedger = getRequiredSystemLedger(LedgerClassification.EQUITY_OPENING_CAPITAL, isDemo)
                    val plan = AccountingEngine.buildPostingPlan(
                        transactionType = TransactionType.OPENING_BALANCE,
                        amountPaise = remainingValuePaise,
                        description = "Recorded ${brand.trim()} Gift Card balance",
                        destinationLedger = gcLedger,
                        openingCapitalLedger = capLedger
                    )
                    commitPostingPlan(
                        plan = plan,
                        transactionType = TransactionType.OPENING_BALANCE,
                        dateEpochDay = purchaseDateEpochDay,
                        amountPaise = remainingValuePaise,
                        category = "Gift Card",
                        description = "Recorded ${brand.trim()} Gift Card balance",
                        linkedGiftCardId = gcId,
                        isDemo = isDemo
                    )
                }
            }
            gcId
        }
    }

    suspend fun addBudget(
        name: String,
        budgetPeriod: BudgetPeriod,
        category: String,
        limitPaise: Long,
        financialYear: String,
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        return database.withTransaction {
            val existingBudgets = dao.getBudgets(isDemo)
            val existingMatch = if (category.equals("OVERALL", ignoreCase = true)) {
                existingBudgets.firstOrNull { it.category.equals("OVERALL", ignoreCase = true) }
            } else {
                existingBudgets.firstOrNull {
                    it.category.equals(category.trim(), ignoreCase = true) && it.budgetPeriod == budgetPeriod
                }
            }
            val budgetId = if (existingMatch != null) {
                dao.updateBudget(
                    existingMatch.copy(
                        name = name.trim(),
                        limitPaise = limitPaise,
                        financialYear = financialYear,
                        notes = notes.trim()
                    )
                )
                existingMatch.id
            } else {
                dao.insertBudget(
                    BudgetEntity(
                        name = name.trim(),
                        budgetPeriod = budgetPeriod,
                        category = category.trim(),
                        limitPaise = limitPaise,
                        financialYear = financialYear,
                        notes = notes.trim(),
                        isDemo = isDemo
                    )
                )
            }
            budgetId
        }
    }

    suspend fun deleteBudget(id: Long) {
        dao.deleteBudgetById(id)
    }

    suspend fun addGoal(
        name: String,
        goalType: GoalType,
        targetAmountPaise: Long,
        currentSavedPaise: Long,
        targetDateEpochDay: Long? = null,
        linkedAccountId: Long? = null,
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        val id = dao.insertGoal(
            GoalEntity(
                name = name.trim(),
                goalType = goalType,
                targetAmountPaise = targetAmountPaise,
                currentSavedPaise = currentSavedPaise,
                targetDateEpochDay = targetDateEpochDay,
                linkedAccountId = linkedAccountId,
                notes = notes.trim(),
                isDemo = isDemo
            )
        )
        if (currentSavedPaise > 0L) {
            dao.insertAuditLog(
                AuditLogEntity(
                    action = "GOAL_CONTRIBUTION",
                    entityType = "Goal",
                    entityId = id,
                    summary = "Saved ${InrMoney.formatPaise(currentSavedPaise)} toward '${name.trim()}'",
                    isDemo = isDemo
                )
            )
        }
        return id
    }

    suspend fun contributeToGoal(goalId: Long, additionalAmountPaise: Long) {
        val existing = dao.getGoalById(goalId) ?: return
        dao.updateGoal(existing.copy(currentSavedPaise = existing.currentSavedPaise + additionalAmountPaise))
        dao.insertAuditLog(
            AuditLogEntity(
                action = "GOAL_CONTRIBUTION",
                entityType = "Goal",
                entityId = goalId,
                summary = "Contributed ${InrMoney.formatPaise(additionalAmountPaise)} to '${existing.name}'",
                isDemo = existing.isDemo
            )
        )
    }

    suspend fun deleteGoal(id: Long) = dao.deleteGoalById(id)

    suspend fun addSubscription(
        name: String,
        costPaise: Long,
        billingFrequency: BillingFrequency,
        nextRenewalEpochDay: Long,
        paymentAccountId: Long? = null,
        startDateEpochDay: Long = IndianFinancialYear.todayEpochDay(),
        endDateEpochDay: Long? = null,
        notes: String = "",
        isDemo: Boolean = false,
        customIntervalCount: Int = 1,
        customIntervalUnit: String = "MONTHS"
    ): Long {
        return dao.insertSubscription(
            SubscriptionEntity(
                name = name.trim(),
                costPaise = costPaise,
                billingFrequency = billingFrequency,
                nextRenewalEpochDay = nextRenewalEpochDay,
                paymentAccountId = paymentAccountId,
                startDateEpochDay = startDateEpochDay,
                endDateEpochDay = endDateEpochDay,
                notes = notes.trim(),
                isDemo = isDemo,
                customIntervalCount = customIntervalCount.coerceAtLeast(1),
                customIntervalUnit = customIntervalUnit.uppercase()
            )
        )
    }

    suspend fun updateSubscriptionRenewalSchedule(
        subscriptionId: Long,
        nextRenewalEpochDay: Long,
        billingFrequency: BillingFrequency,
        customIntervalCount: Int = 1,
        customIntervalUnit: String = "MONTHS"
    ) {
        val sub = dao.getSubscriptionById(subscriptionId) ?: return
        dao.updateSubscription(
            sub.copy(
                nextRenewalEpochDay = nextRenewalEpochDay,
                billingFrequency = billingFrequency,
                customIntervalCount = customIntervalCount.coerceAtLeast(1),
                customIntervalUnit = customIntervalUnit.uppercase()
            )
        )
    }

    /**
     * Explicit user-initiated action to record a subscription renewal payment through the accounting engine
     * and advance the subscription's next renewal date according to its custom interval or user-chosen date.
     */
    suspend fun recordSubscriptionPaymentNow(
        subscriptionId: Long,
        sourceAccountId: Long,
        nextCustomRenewalEpochDay: Long? = null
    ): Long {
        val sub = requireNotNull(dao.getSubscriptionById(subscriptionId)) { "Subscription not found." }
        val today = IndianFinancialYear.todayEpochDay()
        val baseEpochDay = maxOf(sub.nextRenewalEpochDay, today)
        val computedNextRenewal = nextCustomRenewalEpochDay ?: IndianFinancialYear.advanceSubscriptionRenewalEpochDay(
            fromEpochDay = baseEpochDay,
            frequency = sub.billingFrequency,
            customIntervalCount = sub.customIntervalCount,
            customIntervalUnit = sub.customIntervalUnit
        )
        return database.withTransaction {
            val txId = recordTransaction(
                transactionType = TransactionType.EXPENSE,
                dateEpochDay = today,
                amountPaise = sub.costPaise,
                sourceAccountId = sourceAccountId,
                category = "Utilities & Subscriptions",
                description = "Subscription renewal: ${sub.name} (${sub.cycleLabel()})",
                isDemo = sub.isDemo
            )
            dao.updateSubscription(
                sub.copy(
                    nextRenewalEpochDay = computedNextRenewal,
                    paymentAccountId = sourceAccountId
                )
            )
            txId
        }
    }

    /**
     * Resolves an existing active account matching a spoken account name or returns the primary active account.
     * If no active account exists yet, automatically creates a default account so hands-free voice recording
     * completes with zero button clicks.
     */
    suspend fun resolveOrCreateAccountForVoice(
        spokenAccountHint: String?,
        isDemo: Boolean
    ): AccountEntity {
        val activeAccounts = dao.getAccounts(isDemo).filter { !it.isArchived }
        if (activeAccounts.isNotEmpty()) {
            val hint = spokenAccountHint?.trim().orEmpty()
            if (hint.isNotEmpty()) {
                val matched = activeAccounts.firstOrNull { acc ->
                    acc.name.contains(hint, ignoreCase = true) ||
                        hint.contains(acc.name, ignoreCase = true) ||
                        (acc.bankName.isNotBlank() && (acc.bankName.contains(hint, ignoreCase = true) || hint.contains(acc.bankName, ignoreCase = true))) ||
                        acc.accountType.displayName.contains(hint, ignoreCase = true)
                }
                if (matched != null) return matched
            }
            return activeAccounts.first()
        }
        val createdId = createAccount(
            name = if (spokenAccountHint.isNullOrBlank()) "Primary Account" else spokenAccountHint.trim(),
            accountType = AccountType.BANK,
            bankName = spokenAccountHint?.trim().orEmpty(),
            openingBalancePaise = 0L,
            isOpeningBalanceRecorded = false,
            openingDateEpochDay = IndianFinancialYear.todayEpochDay(),
            notes = "Auto-created for hands-free voice transactions",
            isDemo = isDemo
        )
        return requireNotNull(dao.getAccountById(createdId))
    }

    suspend fun deleteSubscription(id: Long) = dao.deleteSubscriptionById(id)

    /**
     * Adds a tax payment record and optionally posts the payment through the accounting engine
     * if a source payment account is selected.
     */
    suspend fun addTaxRecord(
        taxType: TaxType,
        financialYear: String,
        amountPaidPaise: Long,
        paymentDateEpochDay: Long,
        dueDateEpochDay: Long? = null,
        challanNumber: String = "",
        paymentAccountId: Long? = null,
        supportingDocLabel: String = "",
        notes: String = "",
        isDemo: Boolean = false
    ): Long {
        val ay = IndianFinancialYear.getAssessmentYearForFy(financialYear)
        val displayLabel = supportingDocLabel.trim().ifBlank { taxType.displayName }
        return database.withTransaction {
            var txId: Long? = null
            if (paymentAccountId != null && amountPaidPaise > 0L) {
                txId = recordTransaction(
                    transactionType = TransactionType.TAX_PAYMENT,
                    dateEpochDay = paymentDateEpochDay,
                    amountPaise = amountPaidPaise,
                    sourceAccountId = paymentAccountId,
                    category = displayLabel,
                    description = "$displayLabel ($financialYear / $ay)",
                    referenceNumber = challanNumber,
                    notes = notes,
                    isDemo = isDemo
                )
            }
            val recId = dao.insertTaxRecord(
                TaxRecordEntity(
                    taxType = taxType,
                    financialYear = financialYear,
                    assessmentYear = ay,
                    amountPaidPaise = amountPaidPaise,
                    dueDateEpochDay = dueDateEpochDay,
                    paymentDateEpochDay = paymentDateEpochDay,
                    challanNumber = challanNumber.trim(),
                    paymentAccountId = paymentAccountId,
                    linkedTransactionId = txId,
                    supportingDocLabel = supportingDocLabel.trim(),
                    notes = notes.trim(),
                    isDemo = isDemo
                )
            )
            if (txId == null && amountPaidPaise > 0L) {
                dao.insertAuditLog(
                    AuditLogEntity(
                        action = "TAX_RECORDED",
                        entityType = "TaxRecord",
                        entityId = recId,
                        summary = "Recorded $displayLabel (${InrMoney.formatPaise(amountPaidPaise)}) for $financialYear ($ay)",
                        isDemo = isDemo
                    )
                )
            }
            recId
        }
    }

    // Settings & Privacy & Security
    suspend fun updateProfileAndFy(userName: String, panMasked: String, activeFy: String = IndianFinancialYear.currentFyLabel()) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        val systemFy = IndianFinancialYear.currentFyLabel()
        dao.upsertSettings(
            current.copy(
                userName = userName.trim(),
                panMasked = panMasked.trim().uppercase(),
                activeFinancialYear = systemFy,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun togglePrivacyMode(enabled: Boolean) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(current.copy(isPrivacyModeEnabled = enabled))
    }

    suspend fun updateThemeMode(themeMode: com.example.data.local.ThemeMode) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(
            current.copy(
                themeMode = themeMode,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(
            current.copy(
                isBiometricEnabled = enabled,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun setSecurityPin(newPin: String?) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        if (newPin.isNullOrBlank()) {
            dao.upsertSettings(
                current.copy(
                    pinHashSha256 = null,
                    pinSalt = null,
                    isBiometricEnabled = false,
                    failedPinAttempts = 0,
                    lockoutUntilEpochMillis = 0L,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        } else {
            val salt = PinSecurityUtil.generateSaltHex()
            val hash = PinSecurityUtil.hashPinPbkdf2(newPin.trim(), salt, PinSecurityUtil.DEFAULT_ITERATIONS)
            dao.upsertSettings(
                current.copy(
                    pinHashSha256 = hash,
                    pinSalt = salt,
                    pinAlgorithm = PinSecurityUtil.ALGORITHM_PBKDF2,
                    pinIterations = PinSecurityUtil.DEFAULT_ITERATIONS,
                    failedPinAttempts = 0,
                    lockoutUntilEpochMillis = 0L,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Verifies the user's PIN with exponential brute-force lockout protection and automatic
     * transparent migration from legacy salted SHA-256 hashes to PBKDF2-HMAC-SHA256.
     */
    suspend fun verifyAndMigratePinWithThrottling(enteredPin: String): PinVerificationResult {
        return database.withTransaction {
            val current = dao.getSettings() ?: return@withTransaction PinVerificationResult.Success
            val storedHash = current.pinHashSha256
            val storedSalt = current.pinSalt
            if (storedHash.isNullOrBlank() || storedSalt.isNullOrBlank()) {
                return@withTransaction PinVerificationResult.Success
            }

            val nowMs = System.currentTimeMillis()
            if (current.lockoutUntilEpochMillis > nowMs) {
                val remainingSec = ((current.lockoutUntilEpochMillis - nowMs + 999L) / 1000L).coerceAtLeast(1L)
                return@withTransaction PinVerificationResult.LockedOut(remainingSec)
            }

            val isValid = PinSecurityUtil.verifyPin(
                enteredPin = enteredPin.trim(),
                storedHash = storedHash,
                storedSalt = storedSalt
            )
            if (isValid) {
                val needsUpgrade = PinSecurityUtil.needsLegacyMigration(storedHash)
                val upgradedSalt = if (needsUpgrade) PinSecurityUtil.generateSaltHex() else storedSalt
                val upgradedHash = if (needsUpgrade) {
                    PinSecurityUtil.hashPinPbkdf2(enteredPin.trim(), upgradedSalt, PinSecurityUtil.DEFAULT_ITERATIONS)
                } else {
                    storedHash
                }
                dao.upsertSettings(
                    current.copy(
                        pinHashSha256 = upgradedHash,
                        pinSalt = upgradedSalt,
                        pinAlgorithm = PinSecurityUtil.ALGORITHM_PBKDF2,
                        pinIterations = PinSecurityUtil.DEFAULT_ITERATIONS,
                        failedPinAttempts = 0,
                        lockoutUntilEpochMillis = 0L,
                        updatedAtMillis = nowMs
                    )
                )
                PinVerificationResult.Success
            } else {
                val newFailedCount = current.failedPinAttempts + 1
                val delayMs = PinSecurityUtil.calculateLockoutDurationMillis(newFailedCount)
                val lockoutUntil = if (delayMs > 0L) nowMs + delayMs else 0L
                dao.upsertSettings(
                    current.copy(
                        failedPinAttempts = newFailedCount,
                        lockoutUntilEpochMillis = lockoutUntil,
                        updatedAtMillis = nowMs
                    )
                )
                PinVerificationResult.InvalidPin(
                    failedAttempts = newFailedCount,
                    lockoutDurationMillis = delayMs
                )
            }
        }
    }

    suspend fun resetPinLockoutOnBiometricSuccess() {
        val current = dao.getSettings() ?: return
        if (current.failedPinAttempts > 0 || current.lockoutUntilEpochMillis > 0L) {
            dao.upsertSettings(
                current.copy(
                    failedPinAttempts = 0,
                    lockoutUntilEpochMillis = 0L,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun setAiFinancialContextConsent(granted: Boolean) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(
            current.copy(
                isAiFinancialContextConsentGranted = granted,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateAutoLockMinutes(minutes: Int) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(
            current.copy(
                autoLockMinutes = minutes.coerceIn(1, 60),
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun addCustomCategoryOption(scopeKey: String, customValue: String) {
        val cleanKey = scopeKey.trim()
        val cleanVal = customValue.trim()
        if (cleanKey.isEmpty() || cleanVal.isEmpty()) return
        database.withTransaction {
            val current = dao.getSettings() ?: AppSettingsEntity()
            val root = runCatching { JSONObject(current.customCategoriesJson) }.getOrDefault(JSONObject())
            val arr = root.optJSONArray(cleanKey) ?: JSONArray()
            val existing = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) existing.add(item)
            }
            if (existing.none { it.equals(cleanVal, ignoreCase = true) }) {
                existing.add(cleanVal)
                val updatedArr = JSONArray()
                existing.forEach { updatedArr.put(it) }
                root.put(cleanKey, updatedArr)
                dao.upsertSettings(
                    current.copy(
                        customCategoriesJson = root.toString(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    companion object {
        val MONEY_AUDIT_ACTIONS = setOf(
            "VOUCHER_POSTED",
            "VOUCHER_UPDATED",
            "VOUCHER_REVERSED",
            "GOAL_CONTRIBUTION",
            "INVESTMENT_VALUATION_UPDATED",
            "TAX_RECORDED"
        )

        fun parseCustomOptionsMap(json: String): Map<String, List<String>> {
            if (json.isBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(json)
                val map = mutableMapOf<String, List<String>>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = obj.optJSONArray(k) ?: continue
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) list.add(s)
                    }
                    map[k] = list
                }
                map
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * Switches between the user's real records (`isDemo = false`) and isolated fictional demonstration records (`isDemo = true`).
     */
    suspend fun setDemonstrationMode(enabled: Boolean) {
        database.withTransaction {
            val current = dao.getSettings() ?: AppSettingsEntity()
            dao.upsertSettings(current.copy(isDemoModeEnabled = enabled))
            if (enabled) {
                ensureDemoDatasetUpToDate()
            }
        }
    }

    /**
     * Ensures that if Demo Mode is active, the isolated demo dataset contains the full 12+ current-month
     * transactions. Automatically upgrades older 6-item demo datasets without affecting real user records.
     */
    private suspend fun ensureDemoDatasetUpToDate() {
        val activeDemoUserTxs = dao.getTransactions(isDemo = true)
            .filter { !it.isReversed && it.transactionType != TransactionType.OPENING_BALANCE }
        if (activeDemoUserTxs.size < 12) {
            clearAllDataForMode(isDemo = true)
            seedIsolatedFictionalDemoData()
        }
    }

    /**
     * Seeds strictly isolated fictional data ONLY inside `isDemo = true` when the user explicitly turns on Demo Mode.
     * Creates 3 demo accounts and 13 current-month transactions (15 total ledger vouchers including opening balances)
     * so every dashboard, statement, and analytics window is populated.
     */
    private suspend fun seedIsolatedFictionalDemoData() {
        val today = IndianFinancialYear.todayEpochDay()
        val (monthStart, _) = IndianFinancialYear.currentMonthRange()
        fun demoDate(daysAgo: Long): Long = (today - daysAgo).coerceIn(monthStart, today)

        val bankId = createAccount(
            name = "[DEMO - FICTIONAL] Primary Savings Bank",
            accountType = AccountType.SAVINGS,
            bankName = "Demo Fictional Bank",
            openingBalancePaise = 1_50_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val cashId = createAccount(
            name = "[DEMO - FICTIONAL] Cash in Hand",
            accountType = AccountType.CASH,
            openingBalancePaise = 10_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val ccId = createAccount(
            name = "[DEMO - FICTIONAL] Platinum Credit Card",
            accountType = AccountType.CREDIT_CARD,
            creditLimitPaise = 1_00_000_00L,
            openingBalancePaise = 0L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )

        // 1. Salary received: ₹85,000 into bank
        recordTransaction(
            transactionType = TransactionType.INCOME,
            dateEpochDay = demoDate(9),
            amountPaise = 85_000_00L,
            destinationAccountId = bankId,
            category = "Salary Income",
            description = "[DEMO - FICTIONAL] Monthly Salary Credited",
            locationName = "Outer Ring Road, Bengaluru",
            latitude = 12.9304,
            longitude = 77.6784,
            isDemo = true
        )
        // 2. Housing & Rent expense: ₹22,000 from bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(8),
            amountPaise = 22_000_00L,
            sourceAccountId = bankId,
            category = "Housing & Rent",
            description = "[DEMO - FICTIONAL] Apartment Rent & Maintenance",
            locationName = "Indiranagar, Bengaluru",
            latitude = 12.9784,
            longitude = 77.6408,
            isDemo = true
        )
        // 3. Household & Groceries expense: ₹4,800 from bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(7),
            amountPaise = 4_800_00L,
            sourceAccountId = bankId,
            category = "Household & Groceries",
            description = "[DEMO - FICTIONAL] Monthly Groceries & Supplies",
            locationName = "Connaught Place, New Delhi",
            latitude = 28.6315,
            longitude = 77.2167,
            isDemo = true
        )
        // 4. Business & Freelance income: ₹18,500 into bank
        recordTransaction(
            transactionType = TransactionType.INCOME,
            dateEpochDay = demoDate(6),
            amountPaise = 18_500_00L,
            destinationAccountId = bankId,
            category = "Business & Freelance",
            description = "[DEMO - FICTIONAL] Advisory & Consulting Retainer",
            locationName = "Cyber Hub, Gurugram",
            latitude = 28.4950,
            longitude = 77.0895,
            isDemo = true
        )
        // 5. Transfer: ₹5,000 from bank to cash
        recordTransaction(
            transactionType = TransactionType.TRANSFER,
            dateEpochDay = demoDate(6),
            amountPaise = 5_000_00L,
            sourceAccountId = bankId,
            destinationAccountId = cashId,
            category = "Account Transfer",
            description = "[DEMO - FICTIONAL] ATM Cash Withdrawal to Wallet",
            locationName = "Indiranagar, Bengaluru",
            latitude = 12.9784,
            longitude = 77.6408,
            isDemo = true
        )
        // 6. Credit card purchase (Utilities): ₹2,450
        recordTransaction(
            transactionType = TransactionType.CREDIT_CARD_PURCHASE,
            dateEpochDay = demoDate(5),
            amountPaise = 2_450_00L,
            sourceAccountId = ccId,
            category = "Utilities & Bills",
            description = "[DEMO - FICTIONAL] Broadband & Electricity Bill via Card",
            locationName = "Bandra West, Mumbai",
            latitude = 19.0596,
            longitude = 72.8295,
            isDemo = true
        )
        // 7. Transportation & Fuel expense: ₹3,200 from bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(4),
            amountPaise = 3_200_00L,
            sourceAccountId = bankId,
            category = "Transportation & Fuel",
            description = "[DEMO - FICTIONAL] Highway Fuel & Metro Pass Recharge",
            locationName = "Hitech City, Hyderabad",
            latitude = 17.4435,
            longitude = 78.3772,
            isDemo = true
        )
        // 8. Credit card purchase (Food & Dining): ₹1,850
        recordTransaction(
            transactionType = TransactionType.CREDIT_CARD_PURCHASE,
            dateEpochDay = demoDate(3),
            amountPaise = 1_850_00L,
            sourceAccountId = ccId,
            category = "Food & Dining",
            description = "[DEMO - FICTIONAL] Family Dinner & Specialty Coffee",
            locationName = "Koramangala, Bengaluru",
            latitude = 12.9352,
            longitude = 77.6245,
            isDemo = true
        )
        // 9. Healthcare & Medical expense: ₹1,400 from cash
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(3),
            amountPaise = 1_400_00L,
            sourceAccountId = cashId,
            category = "Healthcare & Medical",
            description = "[DEMO - FICTIONAL] Pharmacy & Annual Health Checkup",
            locationName = "Adyar, Chennai",
            latitude = 13.0012,
            longitude = 80.2565,
            isDemo = true
        )
        // 10. Interest received: ₹1,250 into bank
        recordTransaction(
            transactionType = TransactionType.INTEREST_RECEIVED,
            dateEpochDay = demoDate(2),
            amountPaise = 1_250_00L,
            destinationAccountId = bankId,
            category = "Interest Income",
            description = "[DEMO - FICTIONAL] Quarterly Savings Bank Interest",
            isDemo = true
        )
        // 11. Education expense: ₹3,500 from bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(2),
            amountPaise = 3_500_00L,
            sourceAccountId = bankId,
            category = "Education",
            description = "[DEMO - FICTIONAL] Financial Modeling Certification",
            locationName = "Koregaon Park, Pune",
            latitude = 18.5362,
            longitude = 73.8940,
            isDemo = true
        )
        // 12. Credit card repayment: ₹2,000 from bank
        recordTransaction(
            transactionType = TransactionType.CREDIT_CARD_REPAYMENT,
            dateEpochDay = demoDate(1),
            amountPaise = 2_000_00L,
            sourceAccountId = bankId,
            destinationAccountId = ccId,
            category = "Credit Card Bill Payment",
            description = "[DEMO - FICTIONAL] Partial Credit Card Bill Payment",
            isDemo = true
        )
        // 13. Investment purchase: ₹10,000 from bank
        addInvestment(
            name = "[DEMO - FICTIONAL] Nifty 50 Index Fund",
            category = InvestmentCategory.MUTUAL_FUND,
            purchaseDateEpochDay = demoDate(0),
            quantity = 1.0,
            units = 50.0,
            purchasePricePerUnitPaise = 200_00L,
            investedAmountPaise = 10_000_00L,
            currentRecordedValuePaise = 10_000_00L,
            isCurrentValueRecorded = true,
            paymentAccountId = bankId,
            notes = "Fictional demonstration investment record",
            isDemo = true
        )
    }

    /**
     * Deletes all records for the specified mode (`isDemo = false` for real data or `true` for demo data).
     */
    suspend fun clearAllDataForMode(isDemo: Boolean) {
        database.withTransaction {
            dao.clearJournalLinesByMode(isDemo)
            dao.clearJournalEntriesByMode(isDemo)
            dao.clearTransactionsByMode(isDemo)
            dao.clearLoanPaymentsByMode(isDemo)
            dao.clearLoansByMode(isDemo)
            dao.clearInvestmentsByMode(isDemo)
            dao.clearGiftCardsByMode(isDemo)
            dao.clearBudgetsByMode(isDemo)
            dao.clearGoalsByMode(isDemo)
            dao.clearSubscriptionsByMode(isDemo)
            dao.clearTaxesByMode(isDemo)
            dao.clearAccountsByMode(isDemo)
            dao.clearLedgerAccountsByMode(isDemo)
            dao.clearAuditLogsByMode(isDemo)
            initializedChartModes.remove(isDemo)
            ensureSystemChartOfAccounts(isDemo)
        }
    }

    /**
     * Exports a complete JSON backup of the user's real records (`isDemo = false`).
     * Excludes PIN hashes, salts, and raw credentials for safety.
     */
    suspend fun createJsonBackupString(): String {
        val settings = dao.getSettings() ?: AppSettingsEntity()
        val accounts = dao.getAccounts(isDemo = false)
        val transactions = dao.getTransactions(isDemo = false)
        val investments = dao.getInvestments(isDemo = false)
        val loans = dao.getLoans(isDemo = false)
        val budgets = dao.getBudgets(isDemo = false)
        val goals = dao.getGoals(isDemo = false)
        val subscriptions = dao.getSubscriptions(isDemo = false)

        val accountNameById = accounts.associate { it.id to it.name }

        val root = JSONObject().apply {
            put("app", "Finance Vault")
            put("schemaVersion", 7)
            put("creators", "Made by Lenin and Shaswat")
            put("currency", "INR")
            put("exportedAtMillis", System.currentTimeMillis())
            put("userName", settings.userName)
            put("activeFinancialYear", settings.activeFinancialYear)

            put("accounts", JSONArray().apply {
                accounts.forEach { acc ->
                    put(JSONObject().apply {
                        put("name", acc.name)
                        put("accountType", acc.accountType.name)
                        put("bankName", acc.bankName)
                        put("openingBalancePaise", acc.openingBalancePaise)
                        put("isOpeningBalanceRecorded", acc.isOpeningBalanceRecorded)
                        put("openingDateEpochDay", acc.openingDateEpochDay)
                        acc.creditLimitPaise?.let { put("creditLimitPaise", it) }
                    })
                }
            })

            put("transactions", JSONArray().apply {
                transactions.filter { !it.isReversed && it.transactionType != TransactionType.OPENING_BALANCE && it.transactionType != TransactionType.REVERSAL }.forEach { tx ->
                    put(JSONObject().apply {
                        put("voucherNumber", tx.voucherNumber)
                        put("transactionType", tx.transactionType.name)
                        put("dateEpochDay", tx.dateEpochDay)
                        put("amountPaise", tx.amountPaise)
                        put("category", tx.category)
                        put("description", tx.description)
                        put("locationName", tx.locationName)
                        tx.sourceAccountId?.let { id -> accountNameById[id]?.let { put("sourceAccountName", it) } }
                        tx.destinationAccountId?.let { id -> accountNameById[id]?.let { put("destinationAccountName", it) } }
                    })
                }
            })

            put("budgets", JSONArray().apply {
                budgets.forEach { b ->
                    put(JSONObject().apply {
                        put("name", b.name)
                        put("budgetPeriod", b.budgetPeriod.name)
                        put("category", b.category)
                        put("limitPaise", b.limitPaise)
                        put("financialYear", b.financialYear)
                    })
                }
            })

            put("goals", JSONArray().apply {
                goals.forEach { g ->
                    put(JSONObject().apply {
                        put("name", g.name)
                        put("goalType", g.goalType.name)
                        put("targetAmountPaise", g.targetAmountPaise)
                        put("currentSavedPaise", g.currentSavedPaise)
                    })
                }
            })

            put("subscriptions", JSONArray().apply {
                subscriptions.forEach { s ->
                    put(JSONObject().apply {
                        put("name", s.name)
                        put("costPaise", s.costPaise)
                        put("billingFrequency", s.billingFrequency.name)
                        put("nextRenewalEpochDay", s.nextRenewalEpochDay)
                    })
                }
            })

            put("transactionsCount", transactions.size)
            put("investmentsCount", investments.size)
            put("loansCount", loans.size)
            put("budgetsCount", budgets.size)
            put("goalsCount", goals.size)
        }
        return root.toString(2)
    }

    /**
     * Restores user accounts, transactions, budgets, goals, and subscriptions from a JSON backup string
     * while deduplicating against existing records so repeated restores never create duplicates.
     */
    suspend fun restoreFromJsonBackup(jsonString: String): Int {
        val obj = JSONObject(jsonString)
        var restoredCount = 0
        database.withTransaction {
            val existingAccountsByName = dao.getAccounts(isDemo = false)
                .associateBy { it.name.trim().lowercase() }
                .toMutableMap()

            val accountsArray = obj.optJSONArray("accounts")
            if (accountsArray != null) {
                for (i in 0 until accountsArray.length()) {
                    val item = accountsArray.getJSONObject(i)
                    val name = item.optString("name", "").trim()
                    if (name.isNotBlank() && !existingAccountsByName.containsKey(name.lowercase())) {
                        val typeName = item.optString("accountType", AccountType.BANK.name)
                        val type = runCatching { AccountType.valueOf(typeName) }.getOrDefault(AccountType.BANK)
                        val newId = createAccount(
                            name = name,
                            accountType = type,
                            bankName = item.optString("bankName", ""),
                            openingBalancePaise = item.optLong("openingBalancePaise", 0L),
                            isOpeningBalanceRecorded = item.optBoolean("isOpeningBalanceRecorded", false),
                            openingDateEpochDay = item.optLong("openingDateEpochDay", IndianFinancialYear.todayEpochDay()),
                            creditLimitPaise = if (item.has("creditLimitPaise")) item.optLong("creditLimitPaise") else null,
                            isDemo = false
                        )
                        dao.getAccountById(newId)?.let { existingAccountsByName[name.lowercase()] = it }
                        restoredCount++
                    }
                }
            }

            val existingTxSignatures = dao.getTransactions(isDemo = false)
                .map { "${it.transactionType}|${it.dateEpochDay}|${it.amountPaise}|${it.description.trim().lowercase()}" }
                .toMutableSet()

            val txArray = obj.optJSONArray("transactions")
            if (txArray != null) {
                for (i in 0 until txArray.length()) {
                    val item = txArray.getJSONObject(i)
                    val type = runCatching { TransactionType.valueOf(item.optString("transactionType", "EXPENSE")) }
                        .getOrDefault(TransactionType.EXPENSE)
                    val dateEpochDay = item.optLong("dateEpochDay", IndianFinancialYear.todayEpochDay())
                    val amountPaise = item.optLong("amountPaise", 0L)
                    val category = item.optString("category", "Other")
                    val description = item.optString("description", category)
                    val locationName = item.optString("locationName", "")
                    val srcName = item.optString("sourceAccountName", "").trim().lowercase()
                    val dstName = item.optString("destinationAccountName", "").trim().lowercase()
                    val srcId = existingAccountsByName[srcName]?.id
                    val dstId = existingAccountsByName[dstName]?.id

                    val sig = "$type|$dateEpochDay|$amountPaise|${description.trim().lowercase()}"
                    val canPost = when (type) {
                        TransactionType.INCOME -> dstId != null
                        TransactionType.EXPENSE, TransactionType.CREDIT_CARD_PURCHASE, TransactionType.BANK_CHARGE, TransactionType.TAX_PAYMENT -> srcId != null
                        TransactionType.TRANSFER, TransactionType.CASH_DEPOSIT, TransactionType.CASH_WITHDRAWAL, TransactionType.CREDIT_CARD_REPAYMENT -> srcId != null && dstId != null && srcId != dstId
                        else -> false
                    }
                    if (amountPaise > 0L && canPost && !existingTxSignatures.contains(sig)) {
                        recordTransaction(
                            transactionType = type,
                            dateEpochDay = dateEpochDay,
                            amountPaise = amountPaise,
                            sourceAccountId = srcId,
                            destinationAccountId = dstId,
                            category = category,
                            description = description,
                            locationName = locationName,
                            isDemo = false
                        )
                        existingTxSignatures.add(sig)
                        restoredCount++
                    }
                }
            }

            val budgetsArray = obj.optJSONArray("budgets")
            if (budgetsArray != null) {
                for (i in 0 until budgetsArray.length()) {
                    val item = budgetsArray.getJSONObject(i)
                    val limit = item.optLong("limitPaise", 0L)
                    if (limit > 0L) {
                        val period = runCatching { BudgetPeriod.valueOf(item.optString("budgetPeriod", "MONTHLY")) }.getOrDefault(BudgetPeriod.MONTHLY)
                        addBudget(
                            name = item.optString("name", "Budget"),
                            budgetPeriod = period,
                            category = item.optString("category", "OVERALL"),
                            limitPaise = limit,
                            financialYear = item.optString("financialYear", IndianFinancialYear.currentFyLabel()),
                            isDemo = false
                        )
                        restoredCount++
                    }
                }
            }
        }
        return restoredCount
    }
}
