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
import com.example.domain.security.BackupPasswordCipher
import com.example.domain.security.PinSecurityUtil
import com.example.domain.security.VaultKeystoreCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed class PinVerificationResult {
    data object Success : PinVerificationResult()
    data class InvalidPin(val failedAttempts: Int, val lockoutDurationMillis: Long) : PinVerificationResult()
    data class LockedOut(val remainingSeconds: Long) : PinVerificationResult()
}

enum class BackupRestoreMode(val displayLabel: String) {
    REPLACE_EXISTING("Replace Existing Vault Data"),
    MERGE_KEEP_EXISTING("Merge & Skip Duplicates")
}

data class BackupRestorePreview(
    val schemaVersion: Int,
    val exportedAtMillis: Long,
    val userName: String,
    val activeFinancialYear: String,
    val isEncrypted: Boolean,
    val isChecksumValid: Boolean,
    val accountsCount: Int,
    val ledgerAccountsCount: Int,
    val transactionsCount: Int,
    val journalEntriesCount: Int,
    val journalLinesCount: Int,
    val investmentsCount: Int,
    val loansCount: Int,
    val loanPaymentsCount: Int,
    val budgetsCount: Int,
    val goalsCount: Int,
    val giftCardsCount: Int,
    val subscriptionsCount: Int,
    val taxRecordsCount: Int,
    val auditLogsCount: Int
) {
    val checksumVerified: Boolean
        get() = isChecksumValid

    val totalRecordsCount: Int
        get() = accountsCount + transactionsCount + investmentsCount + loansCount +
            budgetsCount + goalsCount + giftCardsCount + subscriptionsCount + taxRecordsCount
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

    private suspend fun requireAccountInMode(accountId: Long?, expectedIsDemo: Boolean, role: String): AccountEntity? {
        if (accountId == null) return null
        val acc = dao.getAccountById(accountId)
            ?: throw IllegalArgumentException("$role account (id=$accountId) does not exist.")
        require(acc.isDemo == expectedIsDemo) {
            "Cross-mode reference violation: $role account '${acc.name}' belongs to ${if (acc.isDemo) "Demo" else "Real"} mode, cannot be used in ${if (expectedIsDemo) "Demo" else "Real"} mode."
        }
        return acc
    }

    private suspend fun requireInvestmentInMode(investmentId: Long?, expectedIsDemo: Boolean): InvestmentEntity? {
        if (investmentId == null) return null
        val inv = dao.getInvestmentById(investmentId)
            ?: throw IllegalArgumentException("Investment (id=$investmentId) does not exist.")
        require(inv.isDemo == expectedIsDemo) {
            "Cross-mode reference violation: Investment '${inv.name}' belongs to ${if (inv.isDemo) "Demo" else "Real"} mode, cannot be used in ${if (expectedIsDemo) "Demo" else "Real"} mode."
        }
        return inv
    }

    private suspend fun requireLoanInMode(loanId: Long?, expectedIsDemo: Boolean): LoanEntity? {
        if (loanId == null) return null
        val loan = dao.getLoanById(loanId)
            ?: throw IllegalArgumentException("Loan (id=$loanId) does not exist.")
        require(loan.isDemo == expectedIsDemo) {
            "Cross-mode reference violation: Loan '${loan.name}' belongs to ${if (loan.isDemo) "Demo" else "Real"} mode, cannot be used in ${if (expectedIsDemo) "Demo" else "Real"} mode."
        }
        return loan
    }

    private suspend fun requireGiftCardInMode(giftCardId: Long?, expectedIsDemo: Boolean): GiftCardEntity? {
        if (giftCardId == null) return null
        val card = dao.getGiftCardById(giftCardId)
            ?: throw IllegalArgumentException("Gift card (id=$giftCardId) does not exist.")
        require(card.isDemo == expectedIsDemo) {
            "Cross-mode reference violation: Gift card '${card.name}' belongs to ${if (card.isDemo) "Demo" else "Real"} mode, cannot be used in ${if (expectedIsDemo) "Demo" else "Real"} mode."
        }
        return card
    }

    private suspend fun requireOriginalTransactionInMode(origTxId: Long?, expectedIsDemo: Boolean): TransactionEntity? {
        if (origTxId == null) return null
        val orig = dao.getTransactionById(origTxId)
            ?: throw IllegalArgumentException("Referenced original transaction (id=$origTxId) does not exist.")
        require(orig.isDemo == expectedIsDemo) {
            "Cross-mode reference violation: Original transaction '${orig.voucherNumber}' belongs to ${if (orig.isDemo) "Demo" else "Real"} mode, cannot be linked in ${if (expectedIsDemo) "Demo" else "Real"} mode."
        }
        return orig
    }

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
        require(openingBalancePaise >= 0L) { "Opening balance cannot be negative ($openingBalancePaise paise)." }
        creditLimitPaise?.let { require(it >= 0L) { "Credit limit cannot be negative." } }
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

    suspend fun archiveAccount(accountId: Long, archive: Boolean, expectedIsDemo: Boolean? = null) {
        val existing = dao.getAccountById(accountId) ?: return
        if (expectedIsDemo != null) {
            require(existing.isDemo == expectedIsDemo) {
                "Cross-mode reference violation: cannot archive account '${existing.name}' across Demo/Real boundaries."
            }
        }
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
        require(principalComponentPaise >= 0L) { "Principal component cannot be negative." }
        require(interestComponentPaise >= 0L) { "Interest component cannot be negative." }
        require(chargesPaise >= 0L) { "Charges cannot be negative." }
        require(costBasisDisposedPaise >= 0L) { "Disposed cost basis cannot be negative." }

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

            // Enforce strict Demo / Real mode isolation on all linked foreign keys
            requireAccountInMode(sourceAccountId, isDemo, "Source")
            requireAccountInMode(destinationAccountId, isDemo, "Destination")
            val linkedInv = requireInvestmentInMode(linkedInvestmentId, isDemo)
            val linkedLoan = requireLoanInMode(linkedLoanId, isDemo)
            val linkedCard = requireGiftCardInMode(linkedGiftCardId, isDemo)
            val linkedOrigTx = requireOriginalTransactionInMode(linkedOriginalTransactionId, isDemo)

            // Domain-level accounting validations before posting
            var resolvedCostBasisDisposed = costBasisDisposedPaise
            if (transactionType == TransactionType.INVESTMENT_SALE) {
                require(amountPaise > chargesPaise) {
                    "Sale proceeds ($amountPaise paise) must exceed sale brokerage/charges ($chargesPaise paise)."
                }
                if (linkedInv != null) {
                    require(!linkedInv.isFullyRedeemed && linkedInv.investedAmountPaise > 0L) {
                        "Cannot record sale against a fully redeemed investment with ₹0 remaining cost basis."
                    }
                    if (costBasisDisposedPaise > 0L) {
                        require(costBasisDisposedPaise <= linkedInv.investedAmountPaise) {
                            "Disposed cost basis ($costBasisDisposedPaise paise) cannot exceed remaining invested cost basis (${linkedInv.investedAmountPaise} paise)."
                        }
                    } else {
                        resolvedCostBasisDisposed = minOf(amountPaise, linkedInv.investedAmountPaise)
                    }
                } else {
                    require(costBasisDisposedPaise > 0L) {
                        "Cost basis disposed must be greater than ₹0 when selling an investment without a linked holding."
                    }
                }
            }

            if (transactionType == TransactionType.REFUND_RECEIVED && linkedOrigTx != null) {
                require(!linkedOrigTx.isReversed) {
                    "Cannot record a refund against a reversed transaction (${linkedOrigTx.voucherNumber})."
                }
                require(
                    linkedOrigTx.transactionType == TransactionType.EXPENSE ||
                        linkedOrigTx.transactionType == TransactionType.CREDIT_CARD_PURCHASE
                ) {
                    "Refunds can only be linked to an Expense or Credit Card Purchase transaction."
                }
                val priorRefunds = dao.getActiveRefundsForOriginalTransaction(linkedOrigTx.id, isDemo)
                val cumulativeRefunded = priorRefunds.fold(0L) { acc, r ->
                    AccountingEngine.safeAddPaise(acc, r.amountPaise)
                }
                val newTotalRefunded = AccountingEngine.safeAddPaise(cumulativeRefunded, amountPaise)
                require(newTotalRefunded <= linkedOrigTx.amountPaise) {
                    "Refund amount ($amountPaise paise) plus prior refunds ($cumulativeRefunded paise) cannot exceed original expense (${linkedOrigTx.amountPaise} paise)."
                }
            }

            if (transactionType == TransactionType.LOAN_REPAYMENT && linkedLoan != null) {
                val principalPaid = if (principalComponentPaise > 0L || interestComponentPaise > 0L) {
                    principalComponentPaise
                } else {
                    amountPaise
                }
                require(principalPaid <= linkedLoan.remainingPrincipalPaise) {
                    "Loan principal repayment ($principalPaid paise) cannot exceed remaining loan principal (${linkedLoan.remainingPrincipalPaise} paise)."
                }
            }

            if (transactionType == TransactionType.GIFT_CARD_USAGE && linkedCard != null) {
                require(amountPaise <= linkedCard.remainingValuePaise) {
                    "Gift card usage ($amountPaise paise) cannot exceed remaining gift card balance (${linkedCard.remainingValuePaise} paise)."
                }
            }

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
                    val origCat = linkedOrigTx?.category ?: category
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
                costBasisDisposedPaise = resolvedCostBasisDisposed,
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
                costBasisDisposedPaise = resolvedCostBasisDisposed,
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
            if (linkedInv != null) {
                when (transactionType) {
                    TransactionType.INVESTMENT_PURCHASE,
                    TransactionType.RD_CONTRIBUTION -> {
                        val newInvested = AccountingEngine.safeAddPaise(linkedInv.investedAmountPaise, amountPaise)
                        val newCurrent = linkedInv.currentRecordedValuePaise?.let { AccountingEngine.safeAddPaise(it, amountPaise) }
                        dao.updateInvestment(
                            linkedInv.copy(
                                investedAmountPaise = newInvested,
                                chargesPaise = AccountingEngine.safeAddPaise(linkedInv.chargesPaise, chargesPaise),
                                currentRecordedValuePaise = newCurrent
                            )
                        )
                    }
                    TransactionType.INVESTMENT_SALE -> {
                        val costDisposed = if (resolvedCostBasisDisposed > 0L) resolvedCostBasisDisposed else amountPaise
                        val gain = AccountingEngine.safeSubtractPaise(
                            AccountingEngine.safeSubtractPaise(amountPaise, costDisposed),
                            chargesPaise
                        )
                        val remainingInvested = (linkedInv.investedAmountPaise - costDisposed).coerceAtLeast(0L)
                        val updatedCurrent = linkedInv.currentRecordedValuePaise?.let { (it - amountPaise).coerceAtLeast(0L) }
                        dao.updateInvestment(
                            linkedInv.copy(
                                investedAmountPaise = remainingInvested,
                                currentRecordedValuePaise = updatedCurrent,
                                realizedProfitLossPaise = AccountingEngine.safeAddPaise(linkedInv.realizedProfitLossPaise, gain),
                                isFullyRedeemed = remainingInvested == 0L
                            )
                        )
                    }
                    TransactionType.FD_MATURITY,
                    TransactionType.RD_MATURITY -> {
                        val principal = if (principalComponentPaise > 0L) principalComponentPaise else linkedInv.investedAmountPaise
                        val interest = (amountPaise - principal).coerceAtLeast(0L)
                        val remaining = (linkedInv.investedAmountPaise - principal).coerceAtLeast(0L)
                        dao.updateInvestment(
                            linkedInv.copy(
                                investedAmountPaise = remaining,
                                currentRecordedValuePaise = if (remaining == 0L) 0L else linkedInv.currentRecordedValuePaise,
                                interestEarnedPaise = AccountingEngine.safeAddPaise(linkedInv.interestEarnedPaise, interest),
                                isFullyRedeemed = remaining == 0L
                            )
                        )
                    }
                    TransactionType.INTEREST_RECEIVED -> {
                        dao.updateInvestment(linkedInv.copy(interestEarnedPaise = AccountingEngine.safeAddPaise(linkedInv.interestEarnedPaise, amountPaise)))
                    }
                    TransactionType.DIVIDEND_RECEIVED -> {
                        dao.updateInvestment(linkedInv.copy(dividendsReceivedPaise = AccountingEngine.safeAddPaise(linkedInv.dividendsReceivedPaise, amountPaise)))
                    }
                    else -> {}
                }
            }

            if (linkedLoan != null && transactionType == TransactionType.LOAN_REPAYMENT) {
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
                val newRemaining = (linkedLoan.remainingPrincipalPaise - principalPaid).coerceAtLeast(0L)
                val newRemainingInst = (linkedLoan.remainingInstallments - 1).coerceAtLeast(0)
                dao.updateLoan(
                    linkedLoan.copy(
                        remainingPrincipalPaise = newRemaining,
                        remainingInstallments = if (newRemaining == 0L) 0 else newRemainingInst,
                        totalInterestPaidPaise = AccountingEngine.safeAddPaise(linkedLoan.totalInterestPaidPaise, interestPaid),
                        isClosed = newRemaining == 0L
                    )
                )
                dao.insertLoanPayment(
                    LoanPaymentEntity(
                        loanId = linkedLoan.id,
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

            if (linkedCard != null) {
                if (transactionType == TransactionType.GIFT_CARD_USAGE) {
                    dao.updateGiftCard(
                        linkedCard.copy(remainingValuePaise = (linkedCard.remainingValuePaise - amountPaise).coerceAtLeast(0L))
                    )
                } else if (transactionType == TransactionType.GIFT_CARD_PURCHASE) {
                    dao.updateGiftCard(
                        linkedCard.copy(remainingValuePaise = AccountingEngine.safeAddPaise(linkedCard.remainingValuePaise, amountPaise))
                    )
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
    suspend fun reverseTransaction(transactionId: Long, expectedIsDemo: Boolean? = null, reason: String = "") {
        database.withTransaction {
            val origTx = dao.getTransactionById(transactionId) ?: return@withTransaction
            if (expectedIsDemo != null) {
                require(origTx.isDemo == expectedIsDemo) {
                    "Cross-mode reference violation: cannot reverse ${if (origTx.isDemo) "Demo" else "Real"} transaction '${origTx.voucherNumber}' from ${if (expectedIsDemo) "Demo" else "Real"} mode."
                }
            }
            if (origTx.isReversed) return@withTransaction
            val origEntry = dao.getJournalEntryForTransactionAndMode(transactionId, origTx.isDemo)
                ?: dao.getJournalEntryForTransaction(transactionId)
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

                val cleanReason = reason.trim()
                val narrationWithReason = if (cleanReason.isNotBlank()) {
                    "${reversalPlan.narration} ($cleanReason)"
                } else {
                    reversalPlan.narration
                }

                val revEntryId = dao.insertJournalEntry(
                    JournalEntryEntity(
                        transactionId = origTx.id,
                        voucherNumber = reversalVoucher,
                        dateEpochDay = IndianFinancialYear.todayEpochDay(),
                        narration = narrationWithReason,
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
                val inv = dao.getInvestmentByIdAndMode(invId, origTx.isDemo)
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
                            val restoredInvested = AccountingEngine.safeAddPaise(inv.investedAmountPaise, costDisposed)
                            val restoredCurrent = inv.currentRecordedValuePaise?.let { AccountingEngine.safeAddPaise(it, origTx.amountPaise) }
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
                    val loan = dao.getLoanByIdAndMode(loanId, origTx.isDemo)
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
                        val restoredRemaining = AccountingEngine.safeAddPaise(loan.remainingPrincipalPaise, principalPaid)
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
                val card = dao.getGiftCardByIdAndMode(gcId, origTx.isDemo)
                if (card != null) {
                    if (origTx.transactionType == TransactionType.GIFT_CARD_USAGE) {
                        dao.updateGiftCard(
                            card.copy(remainingValuePaise = AccountingEngine.safeAddPaise(card.remainingValuePaise, origTx.amountPaise))
                        )
                    } else if (origTx.transactionType == TransactionType.GIFT_CARD_PURCHASE) {
                        dao.updateGiftCard(
                            card.copy(remainingValuePaise = (card.remainingValuePaise - origTx.amountPaise).coerceAtLeast(0L))
                        )
                    }
                }
            }

            val reasonSuffix = if (reason.isNotBlank()) " • Reason: ${reason.trim()}" else ""
            dao.insertAuditLog(
                AuditLogEntity(
                    action = "VOUCHER_REVERSED",
                    entityType = "Transaction",
                    entityId = origTx.id,
                    summary = "Reversed ${origTx.voucherNumber} (${InrMoney.formatPaise(origTx.amountPaise)}) • ${origTx.description}$reasonSuffix",
                    isDemo = origTx.isDemo
                )
            )
        }
    }

    /**
     * Reverses multiple selected transactions atomically in a single SQLite transaction
     * by posting offsetting double-entry journal vouchers.
     */
    suspend fun reverseTransactionsBulk(transactionIds: Set<Long>, expectedIsDemo: Boolean? = null): Int {
        if (transactionIds.isEmpty()) return 0
        var count = 0
        database.withTransaction {
            val txs = dao.getTransactionsByIds(transactionIds.toList())
            for (origTx in txs) {
                if (expectedIsDemo != null) {
                    require(origTx.isDemo == expectedIsDemo) {
                        "Cross-mode reference violation in bulk reversal."
                    }
                }
                if (!origTx.isReversed) {
                    reverseTransaction(origTx.id, expectedIsDemo)
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
    suspend fun categorizeTransactionsBulk(transactionIds: Set<Long>, newCategory: String, expectedIsDemo: Boolean? = null): Int {
        val cleanCategory = newCategory.trim()
        if (cleanCategory.isEmpty() || transactionIds.isEmpty()) return 0
        var updatedCount = 0
        database.withTransaction {
            for (id in transactionIds) {
                val tx = dao.getTransactionById(id) ?: continue
                if (expectedIsDemo != null) {
                    require(tx.isDemo == expectedIsDemo) {
                        "Cross-mode reference violation in bulk categorization."
                    }
                }
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

            requireAccountInMode(sourceAccountId, isDemo, "Source")
            requireAccountInMode(destinationAccountId, isDemo, "Destination")

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
     * posts either an INVESTMENT_PURCHASE (if paid from a bank/cash account)
     * or an OPENING_BALANCE entry (if existing holding brought into Finance Vault),
     * and synchronizes any valuation difference (from share price or growth rate) to the Balance Sheet.
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
        isDemo: Boolean = false,
        dividendsReceivedPaise: Long = 0L,
        interestEarnedPaise: Long = 0L
    ): Long {
        require(name.isNotBlank()) { "Investment name is required." }
        require(investedAmountPaise > 0L) { "Invested amount must be greater than ₹0." }
        require(chargesPaise >= 0L) { "Investment charges cannot be negative." }
        require(dividendsReceivedPaise >= 0L && interestEarnedPaise >= 0L) { "Dividends and interest cannot be negative." }
        currentRecordedValuePaise?.let { require(it >= 0L) { "Current recorded value cannot be negative." } }

        val today = IndianFinancialYear.todayEpochDay()
        val holdingDays = (today - purchaseDateEpochDay).coerceAtLeast(1L)
        val growthAnalysis = FinancialCalculators.analyzeInvestmentGrowth(
            units = units,
            purchasePricePerUnitPaise = purchasePricePerUnitPaise,
            investedAmountPaise = investedAmountPaise,
            currentRecordedValuePaise = currentRecordedValuePaise,
            isCurrentValueRecorded = isCurrentValueRecorded,
            annualGrowthOrInterestRatePercent = annualInterestRatePercent,
            compoundingPeriodsPerYear = compoundingFrequency.periodsPerYear,
            durationMonths = durationMonths,
            holdingDays = holdingDays,
            chargesPaise = chargesPaise,
            dividendsAndInterestPaise = AccountingEngine.safeAddPaise(dividendsReceivedPaise, interestEarnedPaise),
            isRecurringMonthly = category == InvestmentCategory.RECURRING_DEPOSIT || category == InvestmentCategory.SIP
        )
        val effectiveCurrentValue = growthAnalysis.effectiveCurrentValuePaise
        val effectiveIsRecorded = isCurrentValueRecorded ||
            currentRecordedValuePaise != null ||
            annualInterestRatePercent != null ||
            effectiveCurrentValue != investedAmountPaise

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)
            requireAccountInMode(paymentAccountId, isDemo, "Investment payment")

            val invId = dao.insertInvestment(
                InvestmentEntity(
                    name = name.trim(),
                    category = category,
                    purchaseDateEpochDay = purchaseDateEpochDay,
                    quantity = quantity,
                    units = units,
                    purchasePricePerUnitPaise = growthAnalysis.purchasePricePerUnitPaise,
                    investedAmountPaise = investedAmountPaise,
                    chargesPaise = chargesPaise,
                    currentRecordedValuePaise = effectiveCurrentValue,
                    isCurrentValueRecorded = effectiveIsRecorded,
                    interestEarnedPaise = interestEarnedPaise,
                    dividendsReceivedPaise = dividendsReceivedPaise,
                    linkedPaymentAccountId = paymentAccountId,
                    maturityDateEpochDay = maturityDateEpochDay,
                    annualInterestRatePercent = annualInterestRatePercent ?: growthAnalysis.annualizedGrowthRatePercent.takeIf { it != 0.0 },
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

            if (effectiveIsRecorded && effectiveCurrentValue != investedAmountPaise) {
                syncInvestmentLedgerToValuation(
                    investmentId = invId,
                    targetValuePaise = effectiveCurrentValue,
                    dateEpochDay = maxOf(purchaseDateEpochDay, today),
                    isDemo = isDemo
                )
            }

            invId
        }
    }

    /**
     * Synchronizes an investment's Balance Sheet asset ledger to its current valuation (`targetValuePaise`)
     * by posting a double-entry mark-to-market adjustment against `EQUITY_CONTRIBUTION` (Valuation & Capital Reserve).
     * This ensures Personal Net Worth on the Balance Sheet (`Total Assets - Total Liabilities`) always includes
     * the full current value of all investments and wealth without affecting Cash Flow.
     */
    private suspend fun syncInvestmentLedgerToValuation(
        investmentId: Long,
        targetValuePaise: Long,
        dateEpochDay: Long,
        isDemo: Boolean
    ) {
        val invLedger = dao.getLedgerByInvestmentId(investmentId, isDemo) ?: return
        val existingLines = dao.getJournalLines(isDemo).filter { it.ledgerAccountId == invLedger.id }
        val currentLedgerBalance = existingLines.sumOf { it.debitPaise - it.creditPaise }
        val delta = targetValuePaise - currentLedgerBalance
        if (delta == 0L) return

        val equityReserveLedger = getRequiredSystemLedger(LedgerClassification.EQUITY_CONTRIBUTION, isDemo)
        val absDelta = kotlin.math.abs(delta)
        val (srcLedger, dstLedger, narration) = if (delta > 0L) {
            Triple(
                equityReserveLedger,
                invLedger,
                "Valuation appreciation for ${invLedger.name}"
            )
        } else {
            Triple(
                invLedger,
                equityReserveLedger,
                "Valuation adjustment for ${invLedger.name}"
            )
        }

        val plan = AccountingEngine.buildPostingPlan(
            transactionType = TransactionType.CORRECTION,
            amountPaise = absDelta,
            description = narration,
            sourceLedger = srcLedger,
            destinationLedger = dstLedger
        )
        val voucherNo = nextVoucherNumber(dateEpochDay, isDemo)
        val fyLabel = IndianFinancialYear.getFinancialYearLabel(dateEpochDay)
        val entryId = dao.insertJournalEntry(
            JournalEntryEntity(
                transactionId = 0L,
                voucherNumber = voucherNo,
                dateEpochDay = dateEpochDay,
                narration = plan.narration,
                financialYear = fyLabel,
                postingStatus = PostingStatus.POSTED,
                isDemo = isDemo
            )
        )
        val lines = plan.lines.map { draft ->
            JournalLineEntity(
                journalEntryId = entryId,
                transactionId = 0L,
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
        dao.insertJournalLines(lines)
    }

    suspend fun updateInvestmentRecordedValue(
        investmentId: Long,
        newRecordedValuePaise: Long?,
        updatedUnits: Double? = null,
        updatedBuyPricePerUnitPaise: Long? = null,
        updatedAnnualRatePercent: Double? = null,
        updatedCompounding: CompoundingFrequency? = null,
        updatedDurationMonths: Int? = null,
        updatedDividendsPaise: Long? = null,
        updatedInterestPaise: Long? = null,
        expectedIsDemo: Boolean? = null
    ) {
        newRecordedValuePaise?.let { require(it >= 0L) { "Recorded investment value cannot be negative." } }
        database.withTransaction {
            val existing = dao.getInvestmentById(investmentId) ?: return@withTransaction
            if (expectedIsDemo != null) {
                require(existing.isDemo == expectedIsDemo) {
                    "Cross-mode reference violation: cannot update valuation of '${existing.name}' across Demo/Real boundaries."
                }
            }
            val units = updatedUnits ?: existing.units
            val buyPrice = updatedBuyPricePerUnitPaise ?: existing.purchasePricePerUnitPaise
            val rate = updatedAnnualRatePercent ?: existing.annualInterestRatePercent
            val comp = updatedCompounding ?: existing.compoundingFrequency
            val dur = updatedDurationMonths ?: existing.durationMonths
            val div = updatedDividendsPaise ?: existing.dividendsReceivedPaise
            val intr = updatedInterestPaise ?: existing.interestEarnedPaise

            val today = IndianFinancialYear.todayEpochDay()
            val holdingDays = (today - existing.purchaseDateEpochDay).coerceAtLeast(1L)
            val analysis = FinancialCalculators.analyzeInvestmentGrowth(
                units = units,
                purchasePricePerUnitPaise = buyPrice,
                investedAmountPaise = existing.investedAmountPaise,
                currentRecordedValuePaise = newRecordedValuePaise,
                isCurrentValueRecorded = newRecordedValuePaise != null,
                annualGrowthOrInterestRatePercent = rate,
                compoundingPeriodsPerYear = comp.periodsPerYear,
                durationMonths = dur,
                holdingDays = holdingDays,
                chargesPaise = existing.chargesPaise,
                dividendsAndInterestPaise = AccountingEngine.safeAddPaise(div, intr),
                isRecurringMonthly = existing.category == InvestmentCategory.RECURRING_DEPOSIT || existing.category == InvestmentCategory.SIP
            )
            val targetVal = newRecordedValuePaise ?: analysis.effectiveCurrentValuePaise

            dao.updateInvestment(
                existing.copy(
                    quantity = units,
                    units = units,
                    purchasePricePerUnitPaise = analysis.purchasePricePerUnitPaise,
                    currentRecordedValuePaise = targetVal,
                    isCurrentValueRecorded = true,
                    annualInterestRatePercent = rate ?: analysis.annualizedGrowthRatePercent.takeIf { it != 0.0 },
                    compoundingFrequency = comp,
                    durationMonths = dur,
                    dividendsReceivedPaise = div,
                    interestEarnedPaise = intr
                )
            )
            syncInvestmentLedgerToValuation(
                investmentId = investmentId,
                targetValuePaise = targetVal,
                dateEpochDay = today,
                isDemo = existing.isDemo
            )
            dao.insertAuditLog(
                AuditLogEntity(
                    action = "INVESTMENT_VALUATION_UPDATED",
                    entityType = "Investment",
                    entityId = investmentId,
                    summary = "Updated valuation for '${existing.name}' to ${InrMoney.formatPaise(targetVal)} (CAGR: ${analysis.annualizedGrowthRatePercent}%)",
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
        require(annualInterestRatePercent >= 0.0) { "Loan interest rate cannot be negative." }
        require(tenureMonths > 0) { "Loan tenure must be at least 1 month." }

        val calc = FinancialCalculators.calculateLoanAmortization(
            principalPaise = principalPaise,
            annualInterestRatePercent = annualInterestRatePercent,
            tenureMonths = tenureMonths
        )

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)
            requireAccountInMode(disbursedToAccountId, isDemo, "Loan disbursement")

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
        require(remainingValuePaise in 0L..initialValuePaise) {
            "Remaining gift card value ($remainingValuePaise paise) must be between ₹0 and initial value ($initialValuePaise paise)."
        }

        return database.withTransaction {
            ensureSystemChartOfAccounts(isDemo)
            requireAccountInMode(purchasedFromAccountId, isDemo, "Gift card purchase")

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
        require(limitPaise > 0L) { "Budget limit must be greater than ₹0." }
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

    suspend fun deleteBudget(id: Long, expectedIsDemo: Boolean? = null) {
        if (expectedIsDemo != null) {
            dao.deleteBudgetByIdAndMode(id, expectedIsDemo)
        } else {
            dao.deleteBudgetById(id)
        }
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
        require(name.isNotBlank()) { "Goal name is required." }
        require(targetAmountPaise > 0L) { "Goal target amount must be greater than ₹0." }
        require(currentSavedPaise >= 0L) { "Current saved amount cannot be negative." }
        return database.withTransaction {
            requireAccountInMode(linkedAccountId, isDemo, "Goal linked")
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
            id
        }
    }

    suspend fun contributeToGoal(goalId: Long, additionalAmountPaise: Long, expectedIsDemo: Boolean? = null) {
        require(additionalAmountPaise > 0L) { "Goal contribution amount must be greater than ₹0." }
        database.withTransaction {
            val existing = dao.getGoalById(goalId) ?: return@withTransaction
            if (expectedIsDemo != null) {
                require(existing.isDemo == expectedIsDemo) {
                    "Cross-mode reference violation: cannot contribute to '${existing.name}' across Demo/Real boundaries."
                }
            }
            dao.updateGoal(existing.copy(currentSavedPaise = AccountingEngine.safeAddPaise(existing.currentSavedPaise, additionalAmountPaise)))
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
    }

    suspend fun deleteGoal(id: Long, expectedIsDemo: Boolean? = null) {
        if (expectedIsDemo != null) {
            dao.deleteGoalByIdAndMode(id, expectedIsDemo)
        } else {
            dao.deleteGoalById(id)
        }
    }

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
        require(name.isNotBlank()) { "Subscription name is required." }
        require(costPaise > 0L) { "Subscription cost must be greater than ₹0." }
        return database.withTransaction {
            requireAccountInMode(paymentAccountId, isDemo, "Subscription payment")
            dao.insertSubscription(
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
    }

    suspend fun updateSubscriptionRenewalSchedule(
        subscriptionId: Long,
        nextRenewalEpochDay: Long,
        billingFrequency: BillingFrequency,
        customIntervalCount: Int = 1,
        customIntervalUnit: String = "MONTHS",
        expectedIsDemo: Boolean? = null
    ) {
        val sub = dao.getSubscriptionById(subscriptionId) ?: return
        if (expectedIsDemo != null) {
            require(sub.isDemo == expectedIsDemo) {
                "Cross-mode reference violation: cannot modify subscription '${sub.name}' across Demo/Real boundaries."
            }
        }
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
        nextCustomRenewalEpochDay: Long? = null,
        expectedIsDemo: Boolean? = null
    ): Long {
        val sub = requireNotNull(dao.getSubscriptionById(subscriptionId)) { "Subscription not found." }
        if (expectedIsDemo != null) {
            require(sub.isDemo == expectedIsDemo) {
                "Cross-mode reference violation: Subscription '${sub.name}' does not belong to the active mode."
            }
        }
        requireAccountInMode(sourceAccountId, sub.isDemo, "Subscription payment")
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
        return requireNotNull(dao.getAccountByIdAndMode(createdId, isDemo))
    }

    suspend fun deleteSubscription(id: Long, expectedIsDemo: Boolean? = null) {
        if (expectedIsDemo != null) {
            dao.deleteSubscriptionByIdAndMode(id, expectedIsDemo)
        } else {
            dao.deleteSubscriptionById(id)
        }
    }

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
        require(amountPaidPaise > 0L) { "Tax amount paid must be greater than ₹0." }
        val ay = IndianFinancialYear.getAssessmentYearForFy(financialYear)
        val displayLabel = supportingDocLabel.trim().ifBlank { taxType.displayName }
        return database.withTransaction {
            requireAccountInMode(paymentAccountId, isDemo, "Tax payment")
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

    suspend fun setLocationTaggingEnabled(enabled: Boolean) {
        val current = dao.getSettings() ?: AppSettingsEntity()
        dao.upsertSettings(
            current.copy(
                isLocationTaggingEnabled = enabled,
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
     * Ensures that if Demo Mode is active, every dashboard and module window contains at least 10
     * isolated demonstration records and transactions (`isDemo = true`).
     */
    private suspend fun ensureDemoDatasetUpToDate() {
        val hasCompleteDemoSet = dao.getAccounts(isDemo = true).size >= 10 &&
            dao.getInvestments(isDemo = true).size >= 10 &&
            dao.getSubscriptions(isDemo = true).size >= 10 &&
            dao.getGiftCards(isDemo = true).size >= 10 &&
            dao.getBudgets(isDemo = true).size >= 10 &&
            dao.getGoals(isDemo = true).size >= 10 &&
            dao.getLoans(isDemo = true).size >= 10 &&
            dao.getTaxRecords(isDemo = true).size >= 10 &&
            dao.getTransactions(isDemo = true).count { !it.isReversed && it.transactionType != TransactionType.OPENING_BALANCE } >= 20
        if (!hasCompleteDemoSet) {
            clearAllDataForMode(isDemo = true)
            seedIsolatedFictionalDemoData()
        }
    }

    /**
     * Seeds strictly isolated fictional data ONLY inside `isDemo = true` when the user explicitly turns on Demo Mode.
     * Populates 10+ records and transactions across every single dashboard and module window:
     * - 10 Accounts (Savings, Salary, Current, Cash, Wallet, UPI, 2 Credit Cards, Treasury Sweep, Travel Wallet)
     * - 10 Investments (Equity Shares, ETFs, Mutual Funds, SIP, FD, RD, Gold, PPF/G-Sec, Bonds, Silver) with growth & current valuation synced to Balance Sheet
     * - 10 Subscriptions & Renewals
     * - 10 Gift Cards (with purchases & redemptions)
     * - 10 Budgets (Overall + 9 category budgets)
     * - 10 Savings Goals (with recorded contributions)
     * - 10 Loans & Credit Facilities (with EMI schedules & repayments)
     * - 10 Indian Tax Records (Advance Tax, TDS, Self-Assessment, GST)
     * - 25+ Current-Month Transactions across all categories and locations
     */
    private suspend fun seedIsolatedFictionalDemoData() {
        val today = IndianFinancialYear.todayEpochDay()
        val activeFy = IndianFinancialYear.currentFyLabel()
        val (monthStart, _) = IndianFinancialYear.currentMonthRange()
        fun demoDate(daysAgo: Long): Long = (today - daysAgo).coerceIn(monthStart, today)

        // ── 1. SEED 10 DEMO ACCOUNTS ──────────────────────────────────────────────
        val bankId = createAccount(
            name = "[DEMO] HDFC Primary Savings",
            accountType = AccountType.SAVINGS,
            bankName = "HDFC Bank",
            branchName = "Indiranagar Branch",
            ifscCode = "HDFC0000123",
            openingBalancePaise = 4_50_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val salaryBankId = createAccount(
            name = "[DEMO] ICICI Corporate Salary",
            accountType = AccountType.SAVINGS,
            bankName = "ICICI Bank",
            branchName = "MG Road Branch",
            ifscCode = "ICIC0000002",
            openingBalancePaise = 2_80_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val currentBankId = createAccount(
            name = "[DEMO] SBI Advisory Current A/c",
            accountType = AccountType.CURRENT,
            bankName = "State Bank of India",
            branchName = "Connaught Place",
            ifscCode = "SBIN0000691",
            openingBalancePaise = 1_90_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val axisBankId = createAccount(
            name = "[DEMO] Axis Burgundy Treasury",
            accountType = AccountType.BANK,
            bankName = "Axis Bank",
            branchName = "Bandra Kurla Complex",
            ifscCode = "UTIB0000240",
            openingBalancePaise = 1_25_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val kotakUpiId = createAccount(
            name = "[DEMO] Kotak 811 UPI Account",
            accountType = AccountType.UPI,
            bankName = "Kotak Mahindra Bank",
            upiId = "demo.vault@kotak",
            openingBalancePaise = 45_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val cashId = createAccount(
            name = "[DEMO] Cash in Hand Wallet",
            accountType = AccountType.CASH,
            openingBalancePaise = 25_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val walletId = createAccount(
            name = "[DEMO] Amazon Pay & Fastag Wallet",
            accountType = AccountType.DIGITAL_WALLET,
            openingBalancePaise = 12_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val travelWalletId = createAccount(
            name = "[DEMO] Niyo Global Travel Account",
            accountType = AccountType.OTHER,
            bankName = "Equitas Small Finance Bank",
            openingBalancePaise = 35_000_00L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val ccId = createAccount(
            name = "[DEMO] HDFC Infinia Credit Card",
            accountType = AccountType.CREDIT_CARD,
            bankName = "HDFC Bank",
            creditLimitPaise = 8_00_000_00L,
            openingBalancePaise = 0L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )
        val amexCcId = createAccount(
            name = "[DEMO] Amex Platinum Travel Card",
            accountType = AccountType.CREDIT_CARD,
            bankName = "American Express",
            creditLimitPaise = 5_00_000_00L,
            openingBalancePaise = 0L,
            isOpeningBalanceRecorded = true,
            openingDateEpochDay = demoDate(9),
            isDemo = true
        )

        // ── 2. SEED 10 DEMO INVESTMENTS (WITH SHARE PRICE, GROWTH RATE & CURRENT VALUATION) ──
        addInvestment(
            name = "[DEMO] Reliance Industries Ltd (NSE: RELIANCE)",
            category = InvestmentCategory.EQUITY_SHARES,
            purchaseDateEpochDay = today - 420L,
            quantity = 50.0,
            units = 50.0,
            purchasePricePerUnitPaise = 2_400_00L,
            investedAmountPaise = 1_20_000_00L,
            currentRecordedValuePaise = 1_48_500_00L, // Current share price ₹2,970
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 20.3,
            dividendsReceivedPaise = 1_500_00L,
            paymentAccountId = null, // Opening holding
            notes = "50 Equity Shares • Buy ₹2,400 • Current ₹2,970",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] Tata Consultancy Services (NSE: TCS)",
            category = InvestmentCategory.EQUITY_SHARES,
            purchaseDateEpochDay = today - 380L,
            quantity = 25.0,
            units = 25.0,
            purchasePricePerUnitPaise = 3_400_00L,
            investedAmountPaise = 85_000_00L,
            currentRecordedValuePaise = 1_02_500_00L, // Current share price ₹4,100
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 19.8,
            dividendsReceivedPaise = 2_800_00L,
            paymentAccountId = null,
            notes = "25 Equity Shares • Buy ₹3,400 • Current ₹4,100",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] Infosys Ltd (NSE: INFY)",
            category = InvestmentCategory.EQUITY_SHARES,
            purchaseDateEpochDay = today - 310L,
            quantity = 40.0,
            units = 40.0,
            purchasePricePerUnitPaise = 1_450_00L,
            investedAmountPaise = 58_000_00L,
            currentRecordedValuePaise = 74_000_00L, // Current share price ₹1,850
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 33.2,
            dividendsReceivedPaise = 1_600_00L,
            paymentAccountId = null,
            notes = "40 Equity Shares • Buy ₹1,450 • Current ₹1,850",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] Nippon India Nifty 50 BeES ETF",
            category = InvestmentCategory.ETF,
            purchaseDateEpochDay = today - 290L,
            quantity = 300.0,
            units = 300.0,
            purchasePricePerUnitPaise = 220_00L,
            investedAmountPaise = 66_000_00L,
            currentRecordedValuePaise = 81_000_00L, // Current NAV ₹270
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 29.4,
            paymentAccountId = null,
            notes = "300 ETF Units • Buy ₹220 • Current ₹270",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] Parag Parikh Flexi Cap Direct Growth",
            category = InvestmentCategory.MUTUAL_FUND,
            purchaseDateEpochDay = today - 500L,
            quantity = 1200.0,
            units = 1200.0,
            purchasePricePerUnitPaise = 65_00L,
            investedAmountPaise = 78_000_00L,
            currentRecordedValuePaise = 1_03_200_00L, // Current NAV ₹86
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 22.6,
            paymentAccountId = null,
            notes = "1,200 MF Units • Buy NAV ₹65 • Current NAV ₹86",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] Motilal Oswal Midcap 150 SIP",
            category = InvestmentCategory.SIP,
            purchaseDateEpochDay = demoDate(2),
            quantity = 100.0,
            units = 100.0,
            purchasePricePerUnitPaise = 150_00L,
            investedAmountPaise = 15_000_00L,
            currentRecordedValuePaise = 16_200_00L,
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 24.0,
            compoundingFrequency = CompoundingFrequency.MONTHLY,
            durationMonths = 36,
            paymentAccountId = bankId,
            notes = "Monthly Equity SIP • 24% target CAGR",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] HDFC Bank 2-Year Fixed Deposit",
            category = InvestmentCategory.FIXED_DEPOSIT,
            purchaseDateEpochDay = today - 180L,
            quantity = 1.0,
            units = 1.0,
            purchasePricePerUnitPaise = 1_00_000_00L,
            investedAmountPaise = 1_00_000_00L,
            currentRecordedValuePaise = 1_03_700_00L,
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 7.4,
            compoundingFrequency = CompoundingFrequency.QUARTERLY,
            durationMonths = 24,
            interestEarnedPaise = 3_700_00L,
            paymentAccountId = null,
            notes = "7.40% p.a. Quarterly Compounding FD",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] SBI Wealth Recurring Deposit",
            category = InvestmentCategory.RECURRING_DEPOSIT,
            purchaseDateEpochDay = demoDate(4),
            quantity = 1.0,
            units = 1.0,
            purchasePricePerUnitPaise = 10_000_00L,
            investedAmountPaise = 10_000_00L,
            currentRecordedValuePaise = 10_350_00L,
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 7.1,
            compoundingFrequency = CompoundingFrequency.QUARTERLY,
            durationMonths = 12,
            paymentAccountId = currentBankId,
            notes = "7.10% p.a. 12-Month Recurring Deposit",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] RBI Sovereign Gold Bond (SGB 24K)",
            category = InvestmentCategory.GOLD,
            purchaseDateEpochDay = today - 400L,
            quantity = 10.0,
            units = 10.0,
            purchasePricePerUnitPaise = 6_200_00L,
            investedAmountPaise = 62_000_00L,
            currentRecordedValuePaise = 78_500_00L, // ₹7,850 / gram
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 23.8,
            interestEarnedPaise = 1_550_00L,
            paymentAccountId = null,
            notes = "10 Grams 24K SGB • 2.5% p.a. extra coupon",
            isDemo = true
        )
        addInvestment(
            name = "[DEMO] Public Provident Fund (PPF) & GOI Bonds",
            category = InvestmentCategory.GOVT_SECURITIES,
            purchaseDateEpochDay = today - 600L,
            quantity = 1.0,
            units = 1.0,
            purchasePricePerUnitPaise = 1_50_000_00L,
            investedAmountPaise = 1_50_000_00L,
            currentRecordedValuePaise = 1_68_000_00L,
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 7.1,
            compoundingFrequency = CompoundingFrequency.ANNUALLY,
            durationMonths = 180,
            interestEarnedPaise = 18_000_00L,
            paymentAccountId = null,
            notes = "Tax-Free EEE PPF Sovereign Account (7.1% p.a.)",
            isDemo = true
        )

        // ── 3. SEED 10 DEMO GIFT CARDS (WITH PURCHASES & USAGE) ──────────────────
        val gcAmazonId = addGiftCard(
            brand = "Amazon Pay",
            name = "[DEMO] Amazon Shopping Voucher",
            initialValuePaise = 5_000_00L,
            remainingValuePaise = 5_000_00L,
            purchaseDateEpochDay = demoDate(8),
            cardNumberRaw = "6001200230034004",
            redemptionPinRaw = "8821",
            purchasedFromAccountId = bankId,
            notes = "Amazon Prime Festival Gift Card",
            isDemo = true
        )
        // Record usage on Amazon Gift Card so Gift Card Usage transaction is demonstrated
        recordTransaction(
            transactionType = TransactionType.GIFT_CARD_USAGE,
            dateEpochDay = demoDate(5),
            amountPaise = 1_200_00L,
            linkedGiftCardId = gcAmazonId,
            category = "Shopping & Lifestyle",
            description = "[DEMO] Smart Home Accessories via Amazon Gift Card",
            locationName = "Amazon India Online",
            isDemo = true
        )
        addGiftCard(
            brand = "Flipkart",
            name = "[DEMO] Flipkart SuperCoin EGV",
            initialValuePaise = 3_000_00L,
            remainingValuePaise = 2_250_00L,
            purchaseDateEpochDay = demoDate(8),
            cardNumberRaw = "7001800290031122",
            redemptionPinRaw = "4410",
            purchasedFromAccountId = null,
            notes = "Electronics & Apparel Voucher",
            isDemo = true
        )
        addGiftCard(
            brand = "Swiggy Money",
            name = "[DEMO] Swiggy Instamart & Dining Voucher",
            initialValuePaise = 2_000_00L,
            remainingValuePaise = 1_450_00L,
            purchaseDateEpochDay = demoDate(7),
            cardNumberRaw = "5112622373348445",
            redemptionPinRaw = "1920",
            purchasedFromAccountId = null,
            notes = "Food & Instant Grocery Balance",
            isDemo = true
        )
        addGiftCard(
            brand = "Zomato",
            name = "[DEMO] Zomato Gold Dining Gift Card",
            initialValuePaise = 2_500_00L,
            remainingValuePaise = 1_800_00L,
            purchaseDateEpochDay = demoDate(7),
            cardNumberRaw = "4111522263337444",
            redemptionPinRaw = "3309",
            purchasedFromAccountId = null,
            notes = "Restaurant Dining Credits",
            isDemo = true
        )
        addGiftCard(
            brand = "Myntra",
            name = "[DEMO] Myntra Insider Fashion Card",
            initialValuePaise = 4_000_00L,
            remainingValuePaise = 3_100_00L,
            purchaseDateEpochDay = demoDate(6),
            cardNumberRaw = "8111922203331444",
            redemptionPinRaw = "7741",
            purchasedFromAccountId = null,
            notes = "Festive Wardrobe Voucher",
            isDemo = true
        )
        addGiftCard(
            brand = "Nykaa",
            name = "[DEMO] Nykaa Prive Grooming Card",
            initialValuePaise = 1_500_00L,
            remainingValuePaise = 1_500_00L,
            purchaseDateEpochDay = demoDate(6),
            cardNumberRaw = "9111022213332444",
            redemptionPinRaw = "5582",
            purchasedFromAccountId = null,
            notes = "Personal Care Gift Card",
            isDemo = true
        )
        addGiftCard(
            brand = "BookMyShow",
            name = "[DEMO] BookMyShow WinPin Voucher",
            initialValuePaise = 1_000_00L,
            remainingValuePaise = 600_00L,
            purchaseDateEpochDay = demoDate(5),
            cardNumberRaw = "3111422253336444",
            redemptionPinRaw = "6612",
            purchasedFromAccountId = null,
            notes = "Movies & Live Events",
            isDemo = true
        )
        addGiftCard(
            brand = "Croma",
            name = "[DEMO] Tata Croma Electronics Card",
            initialValuePaise = 10_000_00L,
            remainingValuePaise = 7_500_00L,
            purchaseDateEpochDay = demoDate(5),
            cardNumberRaw = "2111322243335444",
            redemptionPinRaw = "9901",
            purchasedFromAccountId = null,
            notes = "Gadget Upgrade Gift Card",
            isDemo = true
        )
        addGiftCard(
            brand = "Decathlon",
            name = "[DEMO] Decathlon Sports & Fitness Card",
            initialValuePaise = 3_000_00L,
            remainingValuePaise = 2_400_00L,
            purchaseDateEpochDay = demoDate(4),
            cardNumberRaw = "1111222233334444",
            redemptionPinRaw = "2048",
            purchasedFromAccountId = null,
            notes = "Running & Trekking Gear",
            isDemo = true
        )
        addGiftCard(
            brand = "Starbucks India",
            name = "[DEMO] Tata Starbucks Stars Card",
            initialValuePaise = 2_000_00L,
            remainingValuePaise = 1_350_00L,
            purchaseDateEpochDay = demoDate(3),
            cardNumberRaw = "6555766687779888",
            redemptionPinRaw = "7123",
            purchasedFromAccountId = null,
            notes = "Coffee & Work Lounge Card",
            isDemo = true
        )

        // ── 4. SEED 10 DEMO SUBSCRIPTIONS & RENEWALS ─────────────────────────────
        addSubscription(
            name = "[DEMO] Netflix 4K Ultra HD Plan",
            costPaise = 649_00L,
            billingFrequency = BillingFrequency.MONTHLY,
            nextRenewalEpochDay = today + 5L,
            paymentAccountId = ccId,
            startDateEpochDay = demoDate(9),
            notes = "Family 4-Screen UHD Plan",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] Spotify Premium Duo",
            costPaise = 149_00L,
            billingFrequency = BillingFrequency.MONTHLY,
            nextRenewalEpochDay = today + 8L,
            paymentAccountId = kotakUpiId,
            startDateEpochDay = demoDate(9),
            notes = "Ad-free Music & Podcasts",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] Amazon Prime Annual Membership",
            costPaise = 1_499_00L,
            billingFrequency = BillingFrequency.ANNUALLY,
            nextRenewalEpochDay = today + 90L,
            paymentAccountId = ccId,
            startDateEpochDay = demoDate(9),
            notes = "Prime Delivery, Video & Music",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] YouTube Premium Family",
            costPaise = 299_00L,
            billingFrequency = BillingFrequency.MONTHLY,
            nextRenewalEpochDay = today + 11L,
            paymentAccountId = kotakUpiId,
            startDateEpochDay = demoDate(9),
            notes = "YouTube Ad-Free + Music",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] JioFiber 300 Mbps Postpaid",
            costPaise = 1_178_00L,
            billingFrequency = BillingFrequency.MONTHLY,
            nextRenewalEpochDay = today + 3L,
            paymentAccountId = bankId,
            startDateEpochDay = demoDate(9),
            notes = "Home Symmetrical Fiber Broadband",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] Apple One / iCloud+ 200GB",
            costPaise = 365_00L,
            billingFrequency = BillingFrequency.MONTHLY,
            nextRenewalEpochDay = today + 14L,
            paymentAccountId = amexCcId,
            startDateEpochDay = demoDate(9),
            notes = "Cloud Backup & Apple Services",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] Microsoft 365 Family Cloud",
            costPaise = 6_199_00L,
            billingFrequency = BillingFrequency.ANNUALLY,
            nextRenewalEpochDay = today + 145L,
            paymentAccountId = ccId,
            startDateEpochDay = demoDate(9),
            notes = "1TB OneDrive + Office Suite",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] Cult.fit Elite Pro Pass",
            costPaise = 4_500_00L,
            billingFrequency = BillingFrequency.QUARTERLY,
            nextRenewalEpochDay = today + 35L,
            paymentAccountId = amexCcId,
            startDateEpochDay = demoDate(9),
            notes = "Gym & Group Fitness Membership",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] The Hindu & BusinessLine Digital",
            costPaise = 699_00L,
            billingFrequency = BillingFrequency.QUARTERLY,
            nextRenewalEpochDay = today + 22L,
            paymentAccountId = kotakUpiId,
            startDateEpochDay = demoDate(9),
            notes = "Financial & National e-Paper",
            isDemo = true
        )
        addSubscription(
            name = "[DEMO] ChatGPT Plus / Cloud Developer Tools",
            costPaise = 1_999_00L,
            billingFrequency = BillingFrequency.MONTHLY,
            nextRenewalEpochDay = today + 18L,
            paymentAccountId = amexCcId,
            startDateEpochDay = demoDate(9),
            notes = "Work & Productivity Suite",
            isDemo = true
        )

        // ── 5. SEED 10 DEMO BUDGETS (OVERALL + 9 CATEGORY BUDGETS) ───────────────
        addBudget(
            name = "Overall Monthly Budget",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "OVERALL",
            limitPaise = 65_000_00L,
            financialYear = activeFy,
            notes = "Total monthly household & lifestyle cap",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Housing & Rent Cap",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Housing & Rent",
            limitPaise = 25_000_00L,
            financialYear = activeFy,
            notes = "Rent, Society Maintenance & Property Care",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Household & Groceries Budget",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Household & Groceries",
            limitPaise = 8_000_00L,
            financialYear = activeFy,
            notes = "Supermarket, Dairy & Fresh Produce",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Food & Dining Out Limit",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Food & Dining",
            limitPaise = 5_000_00L,
            financialYear = activeFy,
            notes = "Restaurants, Cafes & Weekend Ordering",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Utilities & Broadband Budget",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Utilities & Bills",
            limitPaise = 4_500_00L,
            financialYear = activeFy,
            notes = "Electricity, Fiber Internet, Mobile & Gas",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Transportation & Fuel Limit",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Transportation & Fuel",
            limitPaise = 5_000_00L,
            financialYear = activeFy,
            notes = "Petrol, Metro Pass & Airport Cabs",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Healthcare & Wellness Reserve",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Healthcare & Medical",
            limitPaise = 3_500_00L,
            financialYear = activeFy,
            notes = "Pharmacy, Lab Tests & Preventive Care",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Upskilling & Education Budget",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Education",
            limitPaise = 5_000_00L,
            financialYear = activeFy,
            notes = "Courses, Books & Certifications",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Shopping & Lifestyle Cap",
            budgetPeriod = BudgetPeriod.MONTHLY,
            category = "Shopping & Lifestyle",
            limitPaise = 6_000_00L,
            financialYear = activeFy,
            notes = "Apparel, Home Decor & Electronics",
            isDemo = true
        )
        addBudget(
            name = "[DEMO] Annual Travel & Holiday Budget",
            budgetPeriod = BudgetPeriod.ANNUAL,
            category = "Entertainment & Subscriptions",
            limitPaise = 1_20_000_00L,
            financialYear = activeFy,
            notes = "Annual Family Vacation & Subscriptions",
            isDemo = true
        )

        // ── 6. SEED 10 DEMO SAVINGS GOALS ────────────────────────────────────────
        addGoal(
            name = "[DEMO] 6-Month Emergency Liquid Cushion",
            goalType = GoalType.EMERGENCY_FUND,
            targetAmountPaise = 5_00_000_00L,
            currentSavedPaise = 3_85_000_00L,
            targetDateEpochDay = today + 180L,
            linkedAccountId = bankId,
            notes = "Liquid savings + sweep FD emergency reserve",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] Europe & Swiss Alps Family Holiday",
            goalType = GoalType.SAVINGS,
            targetAmountPaise = 3_50_000_00L,
            currentSavedPaise = 2_10_000_00L,
            targetDateEpochDay = today + 240L,
            linkedAccountId = travelWalletId,
            notes = "Flights, Eurail pass & stays",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] Home Down Payment Corpus",
            goalType = GoalType.PURCHASE,
            targetAmountPaise = 20_00_000_00L,
            currentSavedPaise = 11_50_000_00L,
            targetDateEpochDay = today + 540L,
            linkedAccountId = bankId,
            notes = "20% down payment for 3BHK apartment",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] MacBook Pro M4 Workstation Upgrade",
            goalType = GoalType.PURCHASE,
            targetAmountPaise = 2_20_000_00L,
            currentSavedPaise = 1_75_000_00L,
            targetDateEpochDay = today + 60L,
            linkedAccountId = salaryBankId,
            notes = "Workstation & studio display",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] Financial Independence Retire Early (FIRE)",
            goalType = GoalType.SAVINGS,
            targetAmountPaise = 1_00_00_000_00L,
            currentSavedPaise = 24_50_000_00L,
            targetDateEpochDay = today + 3650L,
            linkedAccountId = bankId,
            notes = "Long-term equity & index portfolio",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] Higher Education & MBA Corpus",
            goalType = GoalType.SAVINGS,
            targetAmountPaise = 15_00_000_00L,
            currentSavedPaise = 6_40_000_00L,
            targetDateEpochDay = today + 900L,
            linkedAccountId = axisBankId,
            notes = "Executive education & tuition fund",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] EV SUV Upgrade Fund",
            goalType = GoalType.PURCHASE,
            targetAmountPaise = 12_00_000_00L,
            currentSavedPaise = 4_80_000_00L,
            targetDateEpochDay = today + 450L,
            linkedAccountId = salaryBankId,
            notes = "Zero-debt electric vehicle purchase",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] Parents Medical & Health Buffer",
            goalType = GoalType.EMERGENCY_FUND,
            targetAmountPaise = 4_00_000_00L,
            currentSavedPaise = 3_20_000_00L,
            targetDateEpochDay = today + 120L,
            linkedAccountId = bankId,
            notes = "Dedicated healthcare & deductible fund",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] 5kW Rooftop Solar & Home Automation",
            goalType = GoalType.PURCHASE,
            targetAmountPaise = 2_50_000_00L,
            currentSavedPaise = 1_60_000_00L,
            targetDateEpochDay = today + 150L,
            linkedAccountId = currentBankId,
            notes = "Clean energy & smart home setup",
            isDemo = true
        )
        addGoal(
            name = "[DEMO] Annual Term & Health Insurance Pool",
            goalType = GoalType.SAVINGS,
            targetAmountPaise = 90_000_00L,
            currentSavedPaise = 75_000_00L,
            targetDateEpochDay = today + 45L,
            linkedAccountId = bankId,
            notes = "Annual renewal sinking fund",
            isDemo = true
        )

        // ── 7. SEED 10 DEMO LOANS & CREDIT FACILITIES (WITH EMI REPAYMENTS) ──────
        val homeLoanId = addLoan(
            name = "[DEMO] SBI MaxGain Home Loan",
            loanType = LoanType.HOME,
            lenderName = "State Bank of India",
            principalPaise = 4_50_000_00L,
            annualInterestRatePercent = 8.50,
            tenureMonths = 120,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Floating rate home loan • 8.50% p.a.",
            isDemo = true
        )
        // Record an EMI payment on the Home Loan in the current month
        recordTransaction(
            transactionType = TransactionType.LOAN_REPAYMENT,
            dateEpochDay = demoDate(5),
            amountPaise = 5_580_00L,
            principalComponentPaise = 2_392_00L,
            interestComponentPaise = 3_188_00L,
            sourceAccountId = bankId,
            category = "Loan EMI Repayment",
            description = "[DEMO] Monthly EMI for SBI MaxGain Home Loan",
            linkedLoanId = homeLoanId,
            isDemo = true
        )
        val carLoanId = addLoan(
            name = "[DEMO] HDFC Green EV Car Loan",
            loanType = LoanType.VEHICLE,
            lenderName = "HDFC Bank",
            principalPaise = 1_80_000_00L,
            annualInterestRatePercent = 8.90,
            tenureMonths = 48,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "EV 4-Wheeler Auto Loan",
            isDemo = true
        )
        recordTransaction(
            transactionType = TransactionType.LOAN_REPAYMENT,
            dateEpochDay = demoDate(4),
            amountPaise = 4_470_00L,
            principalComponentPaise = 3_135_00L,
            interestComponentPaise = 1_335_00L,
            sourceAccountId = salaryBankId,
            category = "Loan EMI Repayment",
            description = "[DEMO] Monthly EMI for HDFC EV Car Loan",
            linkedLoanId = carLoanId,
            isDemo = true
        )
        addLoan(
            name = "[DEMO] ICICI Executive Education Loan",
            loanType = LoanType.EDUCATION,
            lenderName = "ICICI Bank",
            principalPaise = 1_20_000_00L,
            annualInterestRatePercent = 9.25,
            tenureMonths = 36,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Section 80E Tax-Deductible Education Loan",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] Axis Quick Personal Credit Line",
            loanType = LoanType.PERSONAL,
            lenderName = "Axis Bank",
            principalPaise = 60_000_00L,
            annualInterestRatePercent = 10.75,
            tenureMonths = 24,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Home office renovation credit line",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] Bajaj Finserv Zero-Cost Appliance EMI",
            loanType = LoanType.OTHER,
            lenderName = "Bajaj Finance",
            principalPaise = 36_000_00L,
            annualInterestRatePercent = 0.0,
            tenureMonths = 12,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "0% Interest Smart Refrigerator & Washer EMI",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] Kotak Two-Wheeler Commuter Loan",
            loanType = LoanType.VEHICLE,
            lenderName = "Kotak Mahindra Prime",
            principalPaise = 45_000_00L,
            annualInterestRatePercent = 9.50,
            tenureMonths = 24,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Ather Electric Scooter Loan",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] Muthoot Sovereign Gold Overdraft",
            loanType = LoanType.OTHER,
            lenderName = "Muthoot Finance",
            principalPaise = 50_000_00L,
            annualInterestRatePercent = 9.00,
            tenureMonths = 12,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Short-term working capital gold line",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] Tata Capital Solar Rooftop Loan",
            loanType = LoanType.HOME,
            lenderName = "Tata Capital",
            principalPaise = 75_000_00L,
            annualInterestRatePercent = 8.75,
            tenureMonths = 36,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Green energy home improvement loan",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] HDFC SmartEMI Studio Equipment",
            loanType = LoanType.PERSONAL,
            lenderName = "HDFC Bank",
            principalPaise = 30_000_00L,
            annualInterestRatePercent = 10.50,
            tenureMonths = 12,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Camera & lighting gear EMI",
            isDemo = true
        )
        addLoan(
            name = "[DEMO] IDFC First Skill Certification Loan",
            loanType = LoanType.EDUCATION,
            lenderName = "IDFC First Bank",
            principalPaise = 40_000_00L,
            annualInterestRatePercent = 9.00,
            tenureMonths = 18,
            startDateEpochDay = demoDate(9),
            disbursedToAccountId = null,
            notes = "Executive AI & Quantitative Finance Program",
            isDemo = true
        )

        // ── 8. SEED 10 DEMO INDIAN TAX RECORDS ───────────────────────────────────
        addTaxRecord(
            taxType = TaxType.ADVANCE_TAX,
            financialYear = activeFy,
            amountPaidPaise = 15_000_00L,
            paymentDateEpochDay = demoDate(6),
            dueDateEpochDay = today + 30L,
            challanNumber = "ITNS280-DEMO-Q1",
            paymentAccountId = bankId,
            supportingDocLabel = "Advance Tax Q1 Installment (15%)",
            notes = "[DEMO] Paid via Income Tax e-Pay Portal",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.ADVANCE_TAX,
            financialYear = activeFy,
            amountPaidPaise = 25_000_00L,
            paymentDateEpochDay = demoDate(5),
            dueDateEpochDay = today + 60L,
            challanNumber = "ITNS280-DEMO-Q2",
            paymentAccountId = null,
            supportingDocLabel = "Advance Tax Q2 Installment (45%)",
            notes = "[DEMO] Second installment challan receipt",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.TDS,
            financialYear = activeFy,
            amountPaidPaise = 12_500_00L,
            paymentDateEpochDay = demoDate(8),
            challanNumber = "FORM16-TDS-SAL01",
            paymentAccountId = null,
            supportingDocLabel = "Salary TDS u/s 192 (Form 16 Part A)",
            notes = "[DEMO] Employer monthly salary tax deduction",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.TDS,
            financialYear = activeFy,
            amountPaidPaise = 1_850_00L,
            paymentDateEpochDay = demoDate(6),
            challanNumber = "FORM26AS-194J-02",
            paymentAccountId = null,
            supportingDocLabel = "Professional Consulting TDS u/s 194J (10%)",
            notes = "[DEMO] Deducted on advisory retainer invoice",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.TDS,
            financialYear = activeFy,
            amountPaidPaise = 740_00L,
            paymentDateEpochDay = demoDate(4),
            challanNumber = "FORM16A-194A-FD",
            paymentAccountId = null,
            supportingDocLabel = "Bank FD Interest TDS u/s 194A",
            notes = "[DEMO] Reflected in Annual Information Statement (AIS)",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.INCOME_TAX,
            financialYear = activeFy,
            amountPaidPaise = 8_400_00L,
            paymentDateEpochDay = demoDate(3),
            challanNumber = "ITNS280-LTCG-112A",
            paymentAccountId = null,
            supportingDocLabel = "Equity LTCG Tax Provision u/s 112A",
            notes = "[DEMO] Long-term capital gains tax computation",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.SELF_ASSESSMENT_TAX,
            financialYear = activeFy,
            amountPaidPaise = 6_200_00L,
            paymentDateEpochDay = demoDate(2),
            challanNumber = "ITNS280-SAT-300",
            paymentAccountId = null,
            supportingDocLabel = "Self-Assessment Tax u/s 140A (Minor Head 300)",
            notes = "[DEMO] Balance tax before ITR verification",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.GST,
            financialYear = activeFy,
            amountPaidPaise = 3_330_00L,
            paymentDateEpochDay = demoDate(2),
            challanNumber = "PMT06-GST-DEMO1",
            paymentAccountId = currentBankId,
            supportingDocLabel = "GST Challan on Advisory Services (18%)",
            notes = "[DEMO] GSTR-3B monthly output tax settlement",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.OTHER,
            financialYear = activeFy,
            amountPaidPaise = 420_00L,
            paymentDateEpochDay = demoDate(1),
            challanNumber = "STT-NSE-CONTRACT",
            paymentAccountId = null,
            supportingDocLabel = "Securities Transaction Tax (STT) & Stamp Duty",
            notes = "[DEMO] Equity delivery brokerage statement",
            isDemo = true
        )
        addTaxRecord(
            taxType = TaxType.OTHER,
            financialYear = activeFy,
            amountPaidPaise = 2_400_00L,
            paymentDateEpochDay = demoDate(1),
            challanNumber = "BBMP-PTAX-2026",
            paymentAccountId = null,
            supportingDocLabel = "Municipal Property Tax & Cess Receipt",
            notes = "[DEMO] Annual residential property tax",
            isDemo = true
        )

        // ── 9. SEED 14+ CURRENT-MONTH INCOME, EXPENSE, TRANSFER & CARD TRANSACTIONS ──
        // 1. Salary received: ₹1,15,000 into salary bank
        recordTransaction(
            transactionType = TransactionType.INCOME,
            dateEpochDay = demoDate(9),
            amountPaise = 1_15_000_00L,
            destinationAccountId = salaryBankId,
            category = "Salary Income",
            description = "[DEMO] Monthly Corporate Salary Credited",
            locationName = "Outer Ring Road, Bengaluru",
            latitude = 12.9304,
            longitude = 77.6784,
            isDemo = true
        )
        // 2. Housing & Rent expense: ₹22,000 from primary bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(8),
            amountPaise = 22_000_00L,
            sourceAccountId = bankId,
            category = "Housing & Rent",
            description = "[DEMO] Apartment Rent & Society Maintenance",
            locationName = "Indiranagar, Bengaluru",
            latitude = 12.9784,
            longitude = 77.6408,
            isDemo = true
        )
        // 3. Household & Groceries expense: ₹4,800 from primary bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(7),
            amountPaise = 4_800_00L,
            sourceAccountId = bankId,
            category = "Household & Groceries",
            description = "[DEMO] Monthly Organic Groceries & Supplies",
            locationName = "Connaught Place, New Delhi",
            latitude = 28.6315,
            longitude = 77.2167,
            isDemo = true
        )
        // 4. Business & Freelance income: ₹28,500 into current bank
        recordTransaction(
            transactionType = TransactionType.INCOME,
            dateEpochDay = demoDate(6),
            amountPaise = 28_500_00L,
            destinationAccountId = currentBankId,
            category = "Business & Freelance",
            description = "[DEMO] FinTech Architecture Advisory Retainer",
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
            description = "[DEMO] ATM Cash Withdrawal to Wallet",
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
            description = "[DEMO] Fiber Broadband & BESCOM Electricity Bill",
            locationName = "Bandra West, Mumbai",
            latitude = 19.0596,
            longitude = 72.8295,
            isDemo = true
        )
        // 7. Transportation & Fuel expense: ₹3,200 from UPI account
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(4),
            amountPaise = 3_200_00L,
            sourceAccountId = kotakUpiId,
            category = "Transportation & Fuel",
            description = "[DEMO] Shell Highway Fuel & Namma Metro Pass",
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
            description = "[DEMO] Family Dinner & Third Wave Specialty Coffee",
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
            description = "[DEMO] Apollo Pharmacy & Preventive Health Check",
            locationName = "Adyar, Chennai",
            latitude = 13.0012,
            longitude = 80.2565,
            isDemo = true
        )
        // 10. Interest received: ₹3,250 into savings bank
        recordTransaction(
            transactionType = TransactionType.INTEREST_RECEIVED,
            dateEpochDay = demoDate(2),
            amountPaise = 3_250_00L,
            destinationAccountId = bankId,
            category = "Interest Income",
            description = "[DEMO] Quarterly Savings & Sweep FD Interest",
            isDemo = true
        )
        // 11. Dividend received: ₹4,400 into salary bank
        recordTransaction(
            transactionType = TransactionType.DIVIDEND_RECEIVED,
            dateEpochDay = demoDate(2),
            amountPaise = 4_400_00L,
            destinationAccountId = salaryBankId,
            category = "Dividend Income",
            description = "[DEMO] Interim Equity Dividend (TCS & Infosys)",
            isDemo = true
        )
        // 12. Education expense: ₹3,500 from primary bank
        recordTransaction(
            transactionType = TransactionType.EXPENSE,
            dateEpochDay = demoDate(2),
            amountPaise = 3_500_00L,
            sourceAccountId = bankId,
            category = "Education",
            description = "[DEMO] Quantitative Finance & AI Certification",
            locationName = "Koregaon Park, Pune",
            latitude = 18.5362,
            longitude = 73.8940,
            isDemo = true
        )
        // 13. Shopping & Lifestyle expense: ₹2,800 via Amex Credit Card
        recordTransaction(
            transactionType = TransactionType.CREDIT_CARD_PURCHASE,
            dateEpochDay = demoDate(1),
            amountPaise = 2_800_00L,
            sourceAccountId = amexCcId,
            category = "Shopping & Lifestyle",
            description = "[DEMO] Running Shoes & Ergonomic Desk Stand",
            locationName = "Jubilee Hills, Hyderabad",
            latitude = 17.4326,
            longitude = 78.4071,
            isDemo = true
        )
        // 14. Credit card repayment: ₹3,000 from bank to HDFC Infinia Card
        recordTransaction(
            transactionType = TransactionType.CREDIT_CARD_REPAYMENT,
            dateEpochDay = demoDate(1),
            amountPaise = 3_000_00L,
            sourceAccountId = bankId,
            destinationAccountId = ccId,
            category = "Credit Card Bill Payment",
            description = "[DEMO] Partial HDFC Credit Card Bill Settlement",
            isDemo = true
        )
        // 15. Equity Index Fund Purchase: ₹10,000 from primary bank
        addInvestment(
            name = "[DEMO] UTI Nifty 50 Index Direct Plan",
            category = InvestmentCategory.MUTUAL_FUND,
            purchaseDateEpochDay = demoDate(0),
            quantity = 50.0,
            units = 50.0,
            purchasePricePerUnitPaise = 200_00L,
            investedAmountPaise = 10_000_00L,
            currentRecordedValuePaise = 10_850_00L,
            isCurrentValueRecorded = true,
            annualInterestRatePercent = 18.5,
            paymentAccountId = bankId,
            notes = "50 Index Units • Current NAV ₹217",
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
     * Exports a complete, versioned JSON backup of all real user financial records (`isDemo = false`).
     *
     * Security & Integrity Guarantees:
     * - Never exports PIN hashes, salts, lockout states, or plaintext gift card pins.
     * - Includes all 14 financial entity tables (`accounts`, `ledger_accounts`, `transactions`,
     *   `journal_entries`, `journal_lines`, `investments`, `loans`, `loan_payments`, `budgets`,
     *   `goals`, `gift_cards`, `subscriptions`, `taxes`, `audit_logs`) and safe non-secret settings.
     * - Embeds a deterministic SHA-256 integrity digest (`payloadSha256`) over all exported arrays.
     * - If `password` is provided, encrypts the entire JSON backup using AES-256-GCM (derived via
     *   PBKDF2-HMAC-SHA256, 120,000 iterations).
     */
    suspend fun createJsonBackupString(password: String? = null): String {
        val settings = dao.getSettings() ?: AppSettingsEntity()
        val accounts = dao.getAccounts(isDemo = false)
        val ledgerAccounts = dao.getLedgerAccounts(isDemo = false)
        val transactions = dao.getTransactions(isDemo = false)
        val journalEntries = dao.getJournalEntries(isDemo = false)
        val journalLines = dao.getJournalLines(isDemo = false)
        val investments = dao.getInvestments(isDemo = false)
        val loans = dao.getLoans(isDemo = false)
        val loanPayments = dao.getLoanPayments(isDemo = false)
        val budgets = dao.getBudgets(isDemo = false)
        val goals = dao.getGoals(isDemo = false)
        val giftCards = dao.getGiftCards(isDemo = false)
        val subscriptions = dao.getSubscriptions(isDemo = false)
        val taxes = dao.getTaxRecords(isDemo = false)
        val auditLogs = dao.getAuditLogs(isDemo = false)

        val accountNameById = accounts.associate { it.id to it.name }

        val accountsJson = JSONArray().apply {
            accounts.forEach { acc ->
                put(JSONObject().apply {
                    put("id", acc.id)
                    put("name", acc.name)
                    put("accountType", acc.accountType.name)
                    put("bankName", acc.bankName)
                    put("branchName", acc.branchName)
                    put("accountNumber", acc.accountNumber)
                    put("ifscCode", acc.ifscCode)
                    put("accountHolder", acc.accountHolder)
                    put("upiId", acc.upiId)
                    put("openingBalancePaise", acc.openingBalancePaise)
                    put("isOpeningBalanceRecorded", acc.isOpeningBalanceRecorded)
                    put("openingDateEpochDay", acc.openingDateEpochDay)
                    acc.creditLimitPaise?.let { put("creditLimitPaise", it) }
                    acc.billingDayOfMonth?.let { put("billingDayOfMonth", it) }
                    put("notes", acc.notes)
                    put("isArchived", acc.isArchived)
                    put("createdAtMillis", acc.createdAtMillis)
                })
            }
        }

        val ledgerAccountsJson = JSONArray().apply {
            ledgerAccounts.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id)
                    put("code", l.code)
                    put("name", l.name)
                    put("classification", l.classification.name)
                    put("normalBalance", l.normalBalance.name)
                    l.linkedUserAccountId?.let { put("linkedUserAccountId", it) }
                    l.linkedInvestmentId?.let { put("linkedInvestmentId", it) }
                    l.linkedLoanId?.let { put("linkedLoanId", it) }
                    l.linkedGiftCardId?.let { put("linkedGiftCardId", it) }
                    put("isSystemAccount", l.isSystemAccount)
                })
            }
        }

        val investmentsJson = JSONArray().apply {
            investments.forEach { inv ->
                put(JSONObject().apply {
                    put("id", inv.id)
                    put("name", inv.name)
                    put("category", inv.category.name)
                    put("purchaseDateEpochDay", inv.purchaseDateEpochDay)
                    put("quantity", inv.quantity)
                    put("units", inv.units)
                    put("purchasePricePerUnitPaise", inv.purchasePricePerUnitPaise)
                    put("investedAmountPaise", inv.investedAmountPaise)
                    put("chargesPaise", inv.chargesPaise)
                    inv.currentRecordedValuePaise?.let { put("currentRecordedValuePaise", it) }
                    put("isCurrentValueRecorded", inv.isCurrentValueRecorded)
                    put("realizedProfitLossPaise", inv.realizedProfitLossPaise)
                    put("interestEarnedPaise", inv.interestEarnedPaise)
                    put("dividendsReceivedPaise", inv.dividendsReceivedPaise)
                    inv.linkedPaymentAccountId?.let { put("linkedPaymentAccountId", it) }
                    inv.maturityDateEpochDay?.let { put("maturityDateEpochDay", it) }
                    inv.annualInterestRatePercent?.let { put("annualInterestRatePercent", it) }
                    put("compoundingFrequency", inv.compoundingFrequency.name)
                    inv.durationMonths?.let { put("durationMonths", it) }
                    put("notes", inv.notes)
                    put("isFullyRedeemed", inv.isFullyRedeemed)
                    put("createdAtMillis", inv.createdAtMillis)
                })
            }
        }

        val loansJson = JSONArray().apply {
            loans.forEach { loan ->
                put(JSONObject().apply {
                    put("id", loan.id)
                    put("name", loan.name)
                    put("loanType", loan.loanType.name)
                    put("lenderName", loan.lenderName)
                    put("principalPaise", loan.principalPaise)
                    put("remainingPrincipalPaise", loan.remainingPrincipalPaise)
                    put("annualInterestRatePercent", loan.annualInterestRatePercent)
                    put("tenureMonths", loan.tenureMonths)
                    put("remainingInstallments", loan.remainingInstallments)
                    put("startDateEpochDay", loan.startDateEpochDay)
                    put("emiAmountPaise", loan.emiAmountPaise)
                    put("totalInterestPaidPaise", loan.totalInterestPaidPaise)
                    loan.linkedBankAccountId?.let { put("linkedBankAccountId", it) }
                    put("notes", loan.notes)
                    put("isClosed", loan.isClosed)
                    put("createdAtMillis", loan.createdAtMillis)
                })
            }
        }

        val loanPaymentsJson = JSONArray().apply {
            loanPayments.forEach { lp ->
                put(JSONObject().apply {
                    put("id", lp.id)
                    put("loanId", lp.loanId)
                    put("transactionId", lp.transactionId)
                    put("dateEpochDay", lp.dateEpochDay)
                    put("totalPaymentPaise", lp.totalPaymentPaise)
                    put("principalComponentPaise", lp.principalComponentPaise)
                    put("interestComponentPaise", lp.interestComponentPaise)
                    put("remainingPrincipalAfterPaise", lp.remainingPrincipalAfterPaise)
                })
            }
        }

        val giftCardsJson = JSONArray().apply {
            giftCards.forEach { gc ->
                put(JSONObject().apply {
                    put("id", gc.id)
                    put("brand", gc.brand)
                    put("name", gc.name)
                    put("initialValuePaise", gc.initialValuePaise)
                    put("remainingValuePaise", gc.remainingValuePaise)
                    put("purchaseDateEpochDay", gc.purchaseDateEpochDay)
                    gc.expirationDateEpochDay?.let { put("expirationDateEpochDay", it) }
                    put("cardNumberMasked", PinSecurityUtil.maskAccountNumber(gc.cardNumberMasked))
                    put("redemptionCodeProtected", "Protected")
                    put("notes", gc.notes)
                    put("createdAtMillis", gc.createdAtMillis)
                })
            }
        }

        val budgetsJson = JSONArray().apply {
            budgets.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("name", b.name)
                    put("budgetPeriod", b.budgetPeriod.name)
                    put("category", b.category)
                    put("limitPaise", b.limitPaise)
                    put("financialYear", b.financialYear)
                    put("notes", b.notes)
                    put("createdAtMillis", b.createdAtMillis)
                })
            }
        }

        val goalsJson = JSONArray().apply {
            goals.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("goalType", g.goalType.name)
                    put("targetAmountPaise", g.targetAmountPaise)
                    put("currentSavedPaise", g.currentSavedPaise)
                    g.targetDateEpochDay?.let { put("targetDateEpochDay", it) }
                    g.linkedAccountId?.let { put("linkedAccountId", it) }
                    put("notes", g.notes)
                    put("createdAtMillis", g.createdAtMillis)
                })
            }
        }

        val subscriptionsJson = JSONArray().apply {
            subscriptions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("costPaise", s.costPaise)
                    put("billingFrequency", s.billingFrequency.name)
                    put("nextRenewalEpochDay", s.nextRenewalEpochDay)
                    s.paymentAccountId?.let { put("paymentAccountId", it) }
                    put("startDateEpochDay", s.startDateEpochDay)
                    s.endDateEpochDay?.let { put("endDateEpochDay", it) }
                    put("notes", s.notes)
                    put("isActive", s.isActive)
                    put("customIntervalCount", s.customIntervalCount)
                    put("customIntervalUnit", s.customIntervalUnit)
                    put("createdAtMillis", s.createdAtMillis)
                })
            }
        }

        val taxesJson = JSONArray().apply {
            taxes.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("taxType", t.taxType.name)
                    put("financialYear", t.financialYear)
                    put("assessmentYear", t.assessmentYear)
                    put("amountPaidPaise", t.amountPaidPaise)
                    t.dueDateEpochDay?.let { put("dueDateEpochDay", it) }
                    put("paymentDateEpochDay", t.paymentDateEpochDay)
                    put("challanNumber", t.challanNumber)
                    t.paymentAccountId?.let { put("paymentAccountId", it) }
                    t.linkedTransactionId?.let { put("linkedTransactionId", it) }
                    put("supportingDocLabel", t.supportingDocLabel)
                    put("notes", t.notes)
                    put("createdAtMillis", t.createdAtMillis)
                })
            }
        }

        val transactionsJson = JSONArray().apply {
            transactions.forEach { tx ->
                put(JSONObject().apply {
                    put("id", tx.id)
                    put("voucherNumber", tx.voucherNumber)
                    put("transactionType", tx.transactionType.name)
                    put("dateEpochDay", tx.dateEpochDay)
                    put("amountPaise", tx.amountPaise)
                    put("principalComponentPaise", tx.principalComponentPaise)
                    put("interestComponentPaise", tx.interestComponentPaise)
                    put("chargesPaise", tx.chargesPaise)
                    put("costBasisDisposedPaise", tx.costBasisDisposedPaise)
                    tx.sourceAccountId?.let { id ->
                        put("sourceAccountId", id)
                        accountNameById[id]?.let { put("sourceAccountName", it) }
                    }
                    tx.destinationAccountId?.let { id ->
                        put("destinationAccountId", id)
                        accountNameById[id]?.let { put("destinationAccountName", it) }
                    }
                    put("category", tx.category)
                    put("description", tx.description)
                    put("referenceNumber", tx.referenceNumber)
                    put("notes", tx.notes)
                    put("locationName", tx.locationName)
                    tx.latitude?.let { put("latitude", it) }
                    tx.longitude?.let { put("longitude", it) }
                    put("attachmentName", tx.attachmentName)
                    tx.linkedInvestmentId?.let { put("linkedInvestmentId", it) }
                    tx.linkedLoanId?.let { put("linkedLoanId", it) }
                    tx.linkedGiftCardId?.let { put("linkedGiftCardId", it) }
                    tx.linkedTaxRecordId?.let { put("linkedTaxRecordId", it) }
                    tx.linkedOriginalTransactionId?.let { put("linkedOriginalTransactionId", it) }
                    put("isReversed", tx.isReversed)
                    put("createdAtMillis", tx.createdAtMillis)
                })
            }
        }

        val journalEntriesJson = JSONArray().apply {
            journalEntries.forEach { je ->
                put(JSONObject().apply {
                    put("id", je.id)
                    put("transactionId", je.transactionId)
                    put("voucherNumber", je.voucherNumber)
                    put("dateEpochDay", je.dateEpochDay)
                    put("narration", je.narration)
                    put("financialYear", je.financialYear)
                    put("postingStatus", je.postingStatus.name)
                    put("isReversal", je.isReversal)
                    je.reversedJournalEntryId?.let { put("reversedJournalEntryId", it) }
                    put("createdAtMillis", je.createdAtMillis)
                })
            }
        }

        val journalLinesJson = JSONArray().apply {
            journalLines.forEach { jl ->
                put(JSONObject().apply {
                    put("id", jl.id)
                    put("journalEntryId", jl.journalEntryId)
                    put("transactionId", jl.transactionId)
                    put("voucherNumber", jl.voucherNumber)
                    put("dateEpochDay", jl.dateEpochDay)
                    put("ledgerAccountId", jl.ledgerAccountId)
                    put("ledgerAccountName", jl.ledgerAccountName)
                    put("ledgerFolio", jl.ledgerFolio)
                    put("debitPaise", jl.debitPaise)
                    put("creditPaise", jl.creditPaise)
                    put("lineNarration", jl.lineNarration)
                })
            }
        }

        val auditLogsJson = JSONArray().apply {
            auditLogs.forEach { log ->
                put(JSONObject().apply {
                    put("id", log.id)
                    put("timestampMillis", log.timestampMillis)
                    put("action", log.action)
                    put("entityType", log.entityType)
                    put("entityId", log.entityId)
                    put("summary", log.summary)
                })
            }
        }

        val settingsJson = JSONObject().apply {
            put("userName", settings.userName)
            put("panMasked", PinSecurityUtil.maskAccountNumber(settings.panMasked))
            put("activeFinancialYear", settings.activeFinancialYear)
            put("isPrivacyModeEnabled", settings.isPrivacyModeEnabled)
            put("themeMode", settings.themeMode.name)
            put("autoLockMinutes", settings.autoLockMinutes)
            put("customCategoriesJson", settings.customCategoriesJson)
            put("isAiFinancialContextConsentGranted", settings.isAiFinancialContextConsentGranted)
            put("isLocationTaggingEnabled", settings.isLocationTaggingEnabled)
        }

        val canonicalDigestInput = buildString {
            append(accountsJson.toString())
            append("|").append(ledgerAccountsJson.toString())
            append("|").append(transactionsJson.toString())
            append("|").append(journalEntriesJson.toString())
            append("|").append(journalLinesJson.toString())
            append("|").append(investmentsJson.toString())
            append("|").append(loansJson.toString())
            append("|").append(loanPaymentsJson.toString())
            append("|").append(budgetsJson.toString())
            append("|").append(goalsJson.toString())
            append("|").append(giftCardsJson.toString())
            append("|").append(subscriptionsJson.toString())
            append("|").append(taxesJson.toString())
            append("|").append(auditLogsJson.toString())
        }
        val payloadSha256 = BackupPasswordCipher.sha256Hex(canonicalDigestInput)

        val root = JSONObject().apply {
            put("app", "Finance Vault")
            put("schemaVersion", 2)
            put("encrypted", false)
            put("creators", "Made by Lenin and Shaswat")
            put("currency", "INR")
            put("exportedAtMillis", System.currentTimeMillis())
            put("userName", settings.userName)
            put("activeFinancialYear", settings.activeFinancialYear)
            put("payloadSha256", payloadSha256)
            put("settings", settingsJson)
            put("accounts", accountsJson)
            put("ledger_accounts", ledgerAccountsJson)
            put("transactions", transactionsJson)
            put("journal_entries", journalEntriesJson)
            put("journal_lines", journalLinesJson)
            put("investments", investmentsJson)
            put("loans", loansJson)
            put("loan_payments", loanPaymentsJson)
            put("budgets", budgetsJson)
            put("goals", goalsJson)
            put("gift_cards", giftCardsJson)
            put("subscriptions", subscriptionsJson)
            put("taxes", taxesJson)
            put("audit_logs", auditLogsJson)
            put("transactionsCount", transactions.size)
            put("investmentsCount", investments.size)
            put("loansCount", loans.size)
            put("budgetsCount", budgets.size)
            put("goalsCount", goals.size)
        }

        val plainFormatted = root.toString(2)
        return if (!password.isNullOrBlank()) {
            BackupPasswordCipher.encryptJsonWithPassword(plainFormatted, password.trim()).toString(2)
        } else {
            plainFormatted
        }
    }

    private fun parseAndValidateBackupJson(rawJson: String, password: String?): Pair<JSONObject, Boolean> {
        require(rawJson.isNotBlank()) { "Backup JSON string cannot be empty." }
        val outer = try {
            JSONObject(rawJson.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed backup JSON format.", e)
        }

        val isEncrypted = outer.optBoolean("encrypted", false)
        val root = if (isEncrypted) {
            require(!password.isNullOrBlank()) {
                "This backup is password-encrypted. Please provide the backup password to unlock and preview/restore."
            }
            val decryptedStr = BackupPasswordCipher.decryptJsonWithPassword(outer, password.trim())
            JSONObject(decryptedStr)
        } else {
            outer
        }

        val schemaVersion = root.optInt("schemaVersion", 1)
        require(schemaVersion in setOf(1, 2, 7)) {
            "Unsupported backup schemaVersion: $schemaVersion"
        }

        val expectedSha = root.optString("payloadSha256", "")
        if (expectedSha.isNotBlank()) {
            val canonicalDigestInput = buildString {
                append((root.optJSONArray("accounts") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("ledger_accounts") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("transactions") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("journal_entries") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("journal_lines") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("investments") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("loans") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("loan_payments") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("budgets") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("goals") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("gift_cards") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("subscriptions") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("taxes") ?: JSONArray()).toString())
                append("|").append((root.optJSONArray("audit_logs") ?: JSONArray()).toString())
            }
            val actualSha = BackupPasswordCipher.sha256Hex(canonicalDigestInput)
            require(
                MessageDigest.isEqual(
                    expectedSha.lowercase().toByteArray(Charsets.UTF_8),
                    actualSha.lowercase().toByteArray(Charsets.UTF_8)
                )
            ) {
                "Backup integrity check failed: SHA-256 checksum mismatch (backup file was modified or corrupted)."
            }
        }
        return root to isEncrypted
    }

    /**
     * Validates a backup JSON string (decrypting if needed), verifies its SHA-256 checksum,
     * and returns a complete summary preview of all records before the user commits the restore.
     */
    fun inspectJsonBackup(rawJson: String, password: String? = null): BackupRestorePreview {
        val (root, isEncrypted) = parseAndValidateBackupJson(rawJson, password)
        return BackupRestorePreview(
            schemaVersion = root.optInt("schemaVersion", 2),
            exportedAtMillis = root.optLong("exportedAtMillis", 0L),
            userName = root.optString("userName", ""),
            activeFinancialYear = root.optString("activeFinancialYear", IndianFinancialYear.currentFyLabel()),
            isEncrypted = isEncrypted,
            isChecksumValid = true,
            accountsCount = root.optJSONArray("accounts")?.length() ?: 0,
            ledgerAccountsCount = root.optJSONArray("ledger_accounts")?.length() ?: 0,
            transactionsCount = root.optJSONArray("transactions")?.length() ?: 0,
            journalEntriesCount = root.optJSONArray("journal_entries")?.length() ?: 0,
            journalLinesCount = root.optJSONArray("journal_lines")?.length() ?: 0,
            investmentsCount = root.optJSONArray("investments")?.length() ?: 0,
            loansCount = root.optJSONArray("loans")?.length() ?: 0,
            loanPaymentsCount = root.optJSONArray("loan_payments")?.length() ?: 0,
            budgetsCount = root.optJSONArray("budgets")?.length() ?: 0,
            goalsCount = root.optJSONArray("goals")?.length() ?: 0,
            giftCardsCount = root.optJSONArray("gift_cards")?.length() ?: 0,
            subscriptionsCount = root.optJSONArray("subscriptions")?.length() ?: 0,
            taxRecordsCount = root.optJSONArray("taxes")?.length() ?: 0,
            auditLogsCount = root.optJSONArray("audit_logs")?.length() ?: 0
        )
    }

    /**
     * Atomically restores all financial entities, double-entry journal entries/lines, linked records,
     * and non-secret settings from a validated JSON backup.
     *
     * Supports:
     * - `BackupRestoreMode.REPLACE_EXISTING`: Clears existing real-mode data and restores exact snapshot with full foreign-key remapping.
     * - `BackupRestoreMode.MERGE_KEEP_EXISTING`: Preserves existing records, skips duplicates, and remaps all foreign keys cleanly.
     */
    suspend fun restoreFromJsonBackup(
        jsonString: String,
        password: String? = null,
        mode: BackupRestoreMode = BackupRestoreMode.MERGE_KEEP_EXISTING
    ): Int {
        val (obj, _) = parseAndValidateBackupJson(jsonString, password)
        val converters = com.example.data.local.FinanceTypeConverters()
        var restoredCount = 0

        database.withTransaction {
            if (mode == BackupRestoreMode.REPLACE_EXISTING) {
                clearAllDataForMode(isDemo = false)
            } else {
                ensureSystemChartOfAccounts(isDemo = false)
            }

            // Restore safe non-secret settings (never overwriting PIN hash/salt)
            obj.optJSONObject("settings")?.let { sObj ->
                val current = dao.getSettings() ?: AppSettingsEntity()
                dao.upsertSettings(
                    current.copy(
                        userName = sObj.optString("userName", current.userName),
                        activeFinancialYear = sObj.optString("activeFinancialYear", current.activeFinancialYear),
                        isPrivacyModeEnabled = sObj.optBoolean("isPrivacyModeEnabled", current.isPrivacyModeEnabled),
                        themeMode = converters.toThemeMode(sObj.optString("themeMode", current.themeMode.name)),
                        autoLockMinutes = sObj.optInt("autoLockMinutes", current.autoLockMinutes).coerceIn(1, 60),
                        customCategoriesJson = sObj.optString("customCategoriesJson", current.customCategoriesJson),
                        isAiFinancialContextConsentGranted = sObj.optBoolean("isAiFinancialContextConsentGranted", current.isAiFinancialContextConsentGranted),
                        isLocationTaggingEnabled = sObj.optBoolean("isLocationTaggingEnabled", current.isLocationTaggingEnabled),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }

            val hasFullJournalArrays = obj.optJSONArray("journal_entries") != null &&
                obj.optJSONArray("journal_lines") != null &&
                obj.optJSONArray("ledger_accounts") != null

            // Foreign-key remap tables: oldId -> newId
            val accountIdMap = mutableMapOf<Long, Long>()
            val existingAccountsByName = dao.getAccounts(isDemo = false)
                .associateBy { it.name.trim().lowercase() }
                .toMutableMap()

            // 1. Accounts
            val accountsArray = obj.optJSONArray("accounts")
            if (accountsArray != null) {
                for (i in 0 until accountsArray.length()) {
                    val item = accountsArray.getJSONObject(i)
                    val oldId = item.optLong("id", -(i + 1).toLong())
                    val name = item.optString("name", "").trim()
                    if (name.isBlank()) continue

                    val existing = existingAccountsByName[name.lowercase()]
                    if (existing != null && mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                        accountIdMap[oldId] = existing.id
                    } else {
                        val type = converters.toAccountType(item.optString("accountType", AccountType.BANK.name))
                        val newId = if (hasFullJournalArrays) {
                            dao.insertAccount(
                                AccountEntity(
                                    name = name,
                                    accountType = type,
                                    bankName = item.optString("bankName", ""),
                                    branchName = item.optString("branchName", ""),
                                    accountNumber = item.optString("accountNumber", ""),
                                    ifscCode = item.optString("ifscCode", ""),
                                    accountHolder = item.optString("accountHolder", ""),
                                    upiId = item.optString("upiId", ""),
                                    openingBalancePaise = item.optLong("openingBalancePaise", 0L).coerceAtLeast(0L),
                                    isOpeningBalanceRecorded = item.optBoolean("isOpeningBalanceRecorded", false),
                                    openingDateEpochDay = item.optLong("openingDateEpochDay", IndianFinancialYear.todayEpochDay()),
                                    creditLimitPaise = if (item.has("creditLimitPaise")) item.optLong("creditLimitPaise") else null,
                                    billingDayOfMonth = if (item.has("billingDayOfMonth")) item.optInt("billingDayOfMonth") else null,
                                    notes = item.optString("notes", ""),
                                    isArchived = item.optBoolean("isArchived", false),
                                    isDemo = false,
                                    createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                                )
                            )
                        } else {
                            createAccount(
                                name = name,
                                accountType = type,
                                bankName = item.optString("bankName", ""),
                                branchName = item.optString("branchName", ""),
                                accountNumber = item.optString("accountNumber", ""),
                                ifscCode = item.optString("ifscCode", ""),
                                accountHolder = item.optString("accountHolder", ""),
                                upiId = item.optString("upiId", ""),
                                openingBalancePaise = item.optLong("openingBalancePaise", 0L).coerceAtLeast(0L),
                                isOpeningBalanceRecorded = item.optBoolean("isOpeningBalanceRecorded", false),
                                openingDateEpochDay = item.optLong("openingDateEpochDay", IndianFinancialYear.todayEpochDay()),
                                creditLimitPaise = if (item.has("creditLimitPaise")) item.optLong("creditLimitPaise") else null,
                                billingDayOfMonth = if (item.has("billingDayOfMonth")) item.optInt("billingDayOfMonth") else null,
                                notes = item.optString("notes", ""),
                                isDemo = false
                            )
                        }
                        accountIdMap[oldId] = newId
                        dao.getAccountById(newId)?.let { existingAccountsByName[name.lowercase()] = it }
                        restoredCount++
                    }
                }
            }

            // 2. Investments
            val investmentIdMap = mutableMapOf<Long, Long>()
            val existingInvestmentsBySig = dao.getInvestments(isDemo = false)
                .associateBy { "${it.name.trim().lowercase()}|${it.category.name}|${it.purchaseDateEpochDay}" }
                .toMutableMap()

            val investmentsArray = obj.optJSONArray("investments")
            if (investmentsArray != null) {
                for (i in 0 until investmentsArray.length()) {
                    val item = investmentsArray.getJSONObject(i)
                    val oldId = item.optLong("id", -(i + 1).toLong())
                    val name = item.optString("name", "").trim()
                    val cat = converters.toInvestmentCategory(item.optString("category", InvestmentCategory.OTHER.name))
                    val pDate = item.optLong("purchaseDateEpochDay", IndianFinancialYear.todayEpochDay())
                    val invested = item.optLong("investedAmountPaise", 0L)
                    if (name.isBlank() || invested < 0L) continue

                    val sig = "${name.lowercase()}|${cat.name}|$pDate"
                    val existing = existingInvestmentsBySig[sig]
                    if (existing != null && mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                        investmentIdMap[oldId] = existing.id
                    } else {
                        val mappedPayAcc = if (item.has("linkedPaymentAccountId")) accountIdMap[item.optLong("linkedPaymentAccountId")] else null
                        val newId = if (hasFullJournalArrays) {
                            dao.insertInvestment(
                                InvestmentEntity(
                                    name = name,
                                    category = cat,
                                    purchaseDateEpochDay = pDate,
                                    quantity = item.optDouble("quantity", 1.0),
                                    units = item.optDouble("units", 1.0),
                                    purchasePricePerUnitPaise = item.optLong("purchasePricePerUnitPaise", 0L),
                                    investedAmountPaise = invested,
                                    chargesPaise = item.optLong("chargesPaise", 0L),
                                    currentRecordedValuePaise = if (item.has("currentRecordedValuePaise")) item.optLong("currentRecordedValuePaise") else null,
                                    isCurrentValueRecorded = item.optBoolean("isCurrentValueRecorded", false),
                                    realizedProfitLossPaise = item.optLong("realizedProfitLossPaise", 0L),
                                    interestEarnedPaise = item.optLong("interestEarnedPaise", 0L),
                                    dividendsReceivedPaise = item.optLong("dividendsReceivedPaise", 0L),
                                    linkedPaymentAccountId = mappedPayAcc,
                                    maturityDateEpochDay = if (item.has("maturityDateEpochDay")) item.optLong("maturityDateEpochDay") else null,
                                    annualInterestRatePercent = if (item.has("annualInterestRatePercent")) item.optDouble("annualInterestRatePercent") else null,
                                    compoundingFrequency = converters.toCompoundingFrequency(item.optString("compoundingFrequency", CompoundingFrequency.QUARTERLY.name)),
                                    durationMonths = if (item.has("durationMonths")) item.optInt("durationMonths") else null,
                                    notes = item.optString("notes", ""),
                                    isFullyRedeemed = item.optBoolean("isFullyRedeemed", false),
                                    isDemo = false,
                                    createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                                )
                            )
                        } else if (invested > 0L) {
                            addInvestment(
                                name = name,
                                category = cat,
                                purchaseDateEpochDay = pDate,
                                quantity = item.optDouble("quantity", 1.0),
                                units = item.optDouble("units", 1.0),
                                purchasePricePerUnitPaise = item.optLong("purchasePricePerUnitPaise", 0L),
                                investedAmountPaise = invested,
                                chargesPaise = item.optLong("chargesPaise", 0L),
                                currentRecordedValuePaise = if (item.has("currentRecordedValuePaise")) item.optLong("currentRecordedValuePaise") else null,
                                isCurrentValueRecorded = item.optBoolean("isCurrentValueRecorded", false),
                                paymentAccountId = mappedPayAcc,
                                maturityDateEpochDay = if (item.has("maturityDateEpochDay")) item.optLong("maturityDateEpochDay") else null,
                                annualInterestRatePercent = if (item.has("annualInterestRatePercent")) item.optDouble("annualInterestRatePercent") else null,
                                compoundingFrequency = converters.toCompoundingFrequency(item.optString("compoundingFrequency", CompoundingFrequency.QUARTERLY.name)),
                                durationMonths = if (item.has("durationMonths")) item.optInt("durationMonths") else null,
                                notes = item.optString("notes", ""),
                                isDemo = false
                            )
                        } else continue
                        investmentIdMap[oldId] = newId
                        dao.getInvestmentById(newId)?.let { existingInvestmentsBySig[sig] = it }
                        restoredCount++
                    }
                }
            }

            // 3. Loans
            val loanIdMap = mutableMapOf<Long, Long>()
            val existingLoansBySig = dao.getLoans(isDemo = false)
                .associateBy { "${it.name.trim().lowercase()}|${it.loanType.name}|${it.principalPaise}" }
                .toMutableMap()

            val loansArray = obj.optJSONArray("loans")
            if (loansArray != null) {
                for (i in 0 until loansArray.length()) {
                    val item = loansArray.getJSONObject(i)
                    val oldId = item.optLong("id", -(i + 1).toLong())
                    val name = item.optString("name", "").trim()
                    val lType = converters.toLoanType(item.optString("loanType", LoanType.PERSONAL.name))
                    val prin = item.optLong("principalPaise", 0L)
                    if (name.isBlank() || prin <= 0L) continue

                    val sig = "${name.lowercase()}|${lType.name}|$prin"
                    val existing = existingLoansBySig[sig]
                    if (existing != null && mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                        loanIdMap[oldId] = existing.id
                    } else {
                        val mappedBank = if (item.has("linkedBankAccountId")) accountIdMap[item.optLong("linkedBankAccountId")] else null
                        val newId = if (hasFullJournalArrays) {
                            dao.insertLoan(
                                LoanEntity(
                                    name = name,
                                    loanType = lType,
                                    lenderName = item.optString("lenderName", ""),
                                    principalPaise = prin,
                                    remainingPrincipalPaise = item.optLong("remainingPrincipalPaise", prin),
                                    annualInterestRatePercent = item.optDouble("annualInterestRatePercent", 8.5),
                                    tenureMonths = item.optInt("tenureMonths", 12).coerceAtLeast(1),
                                    remainingInstallments = item.optInt("remainingInstallments", 12).coerceAtLeast(0),
                                    startDateEpochDay = item.optLong("startDateEpochDay", IndianFinancialYear.todayEpochDay()),
                                    emiAmountPaise = item.optLong("emiAmountPaise", prin / 12),
                                    totalInterestPaidPaise = item.optLong("totalInterestPaidPaise", 0L),
                                    linkedBankAccountId = mappedBank,
                                    notes = item.optString("notes", ""),
                                    isClosed = item.optBoolean("isClosed", false),
                                    isDemo = false,
                                    createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                                )
                            )
                        } else {
                            addLoan(
                                name = name,
                                loanType = lType,
                                lenderName = item.optString("lenderName", ""),
                                principalPaise = prin,
                                annualInterestRatePercent = item.optDouble("annualInterestRatePercent", 8.5),
                                tenureMonths = item.optInt("tenureMonths", 12).coerceAtLeast(1),
                                startDateEpochDay = item.optLong("startDateEpochDay", IndianFinancialYear.todayEpochDay()),
                                disbursedToAccountId = mappedBank,
                                notes = item.optString("notes", ""),
                                isDemo = false
                            )
                        }
                        loanIdMap[oldId] = newId
                        dao.getLoanById(newId)?.let { existingLoansBySig[sig] = it }
                        restoredCount++
                    }
                }
            }

            // 4. Gift Cards
            val giftCardIdMap = mutableMapOf<Long, Long>()
            val existingGiftCardsBySig = dao.getGiftCards(isDemo = false)
                .associateBy { "${it.brand.trim().lowercase()}|${it.name.trim().lowercase()}|${it.initialValuePaise}" }
                .toMutableMap()

            val giftCardsArray = obj.optJSONArray("gift_cards")
            if (giftCardsArray != null) {
                for (i in 0 until giftCardsArray.length()) {
                    val item = giftCardsArray.getJSONObject(i)
                    val oldId = item.optLong("id", -(i + 1).toLong())
                    val brand = item.optString("brand", "").trim()
                    val name = item.optString("name", brand).trim()
                    val initVal = item.optLong("initialValuePaise", 0L)
                    if (brand.isBlank() || initVal <= 0L) continue

                    val sig = "${brand.lowercase()}|${name.lowercase()}|$initVal"
                    val existing = existingGiftCardsBySig[sig]
                    if (existing != null && mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                        giftCardIdMap[oldId] = existing.id
                    } else {
                        val remVal = item.optLong("remainingValuePaise", initVal).coerceIn(0L, initVal)
                        val newId = if (hasFullJournalArrays) {
                            dao.insertGiftCard(
                                GiftCardEntity(
                                    brand = brand,
                                    name = name,
                                    initialValuePaise = initVal,
                                    remainingValuePaise = remVal,
                                    purchaseDateEpochDay = item.optLong("purchaseDateEpochDay", IndianFinancialYear.todayEpochDay()),
                                    expirationDateEpochDay = if (item.has("expirationDateEpochDay")) item.optLong("expirationDateEpochDay") else null,
                                    cardNumberMasked = item.optString("cardNumberMasked", "Not provided"),
                                    redemptionCodeProtected = item.optString("redemptionCodeProtected", "Protected"),
                                    notes = item.optString("notes", ""),
                                    isDemo = false,
                                    createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                                )
                            )
                        } else {
                            addGiftCard(
                                brand = brand,
                                name = name,
                                initialValuePaise = initVal,
                                remainingValuePaise = remVal,
                                purchaseDateEpochDay = item.optLong("purchaseDateEpochDay", IndianFinancialYear.todayEpochDay()),
                                expirationDateEpochDay = if (item.has("expirationDateEpochDay")) item.optLong("expirationDateEpochDay") else null,
                                notes = item.optString("notes", ""),
                                isDemo = false
                            )
                        }
                        giftCardIdMap[oldId] = newId
                        dao.getGiftCardById(newId)?.let { existingGiftCardsBySig[sig] = it }
                        restoredCount++
                    }
                }
            }

            // 5. Ledger Accounts (Chart of Accounts)
            val ledgerIdMap = mutableMapOf<Long, Long>()
            val existingSystemLedgersByClass = dao.getLedgerAccounts(isDemo = false)
                .filter { it.isSystemAccount }
                .associateBy { it.classification }
            val existingLedgersByCode = dao.getLedgerAccounts(isDemo = false)
                .associateBy { it.code }
                .toMutableMap()

            val ledgerArray = obj.optJSONArray("ledger_accounts")
            if (ledgerArray != null && hasFullJournalArrays) {
                for (i in 0 until ledgerArray.length()) {
                    val item = ledgerArray.getJSONObject(i)
                    val oldId = item.optLong("id", -(i + 1).toLong())
                    val classification = converters.toLedgerClassification(item.optString("classification", LedgerClassification.EXPENSE_OTHER.name))
                    val isSys = item.optBoolean("isSystemAccount", false)
                    if (isSys) {
                        val sysMatch = existingSystemLedgersByClass[classification]
                        if (sysMatch != null) {
                            ledgerIdMap[oldId] = sysMatch.id
                            continue
                        }
                    }

                    val mappedUserAcc = if (item.has("linkedUserAccountId")) accountIdMap[item.optLong("linkedUserAccountId")] else null
                    val mappedInv = if (item.has("linkedInvestmentId")) investmentIdMap[item.optLong("linkedInvestmentId")] else null
                    val mappedLoan = if (item.has("linkedLoanId")) loanIdMap[item.optLong("linkedLoanId")] else null
                    val mappedGc = if (item.has("linkedGiftCardId")) giftCardIdMap[item.optLong("linkedGiftCardId")] else null

                    val existingLinked = when {
                        mappedUserAcc != null -> dao.getLedgerByUserAccountId(mappedUserAcc, isDemo = false)
                        mappedInv != null -> dao.getLedgerByInvestmentId(mappedInv, isDemo = false)
                        mappedLoan != null -> dao.getLedgerByLoanId(mappedLoan, isDemo = false)
                        mappedGc != null -> dao.getLedgerByGiftCardId(mappedGc, isDemo = false)
                        else -> existingLedgersByCode[item.optString("code", "")]
                    }

                    if (existingLinked != null) {
                        ledgerIdMap[oldId] = existingLinked.id
                    } else {
                        val newId = dao.insertLedgerAccount(
                            LedgerAccountEntity(
                                code = item.optString("code", "1999"),
                                name = item.optString("name", "Ledger Account"),
                                classification = classification,
                                normalBalance = converters.toNormalBalance(item.optString("normalBalance", classification.normalBalance.name)),
                                linkedUserAccountId = mappedUserAcc,
                                linkedInvestmentId = mappedInv,
                                linkedLoanId = mappedLoan,
                                linkedGiftCardId = mappedGc,
                                isSystemAccount = isSys,
                                isDemo = false
                            )
                        )
                        ledgerIdMap[oldId] = newId
                    }
                }
            } else {
                // Ensure every restored account, investment, loan, and gift card has a ledger account mapped
                for ((_, newAccId) in accountIdMap) {
                    if (dao.getLedgerByUserAccountId(newAccId, isDemo = false) == null) {
                        val acc = dao.getAccountById(newAccId) ?: continue
                        val prefix = if (acc.accountType == AccountType.CREDIT_CARD) "21" else "11"
                        dao.insertLedgerAccount(
                            LedgerAccountEntity(
                                code = "$prefix${newAccId.toString().padStart(3, '0')}",
                                name = acc.name,
                                classification = acc.accountType.ledgerClassification,
                                normalBalance = acc.accountType.normalBalance,
                                linkedUserAccountId = newAccId,
                                isSystemAccount = false,
                                isDemo = false
                            )
                        )
                    }
                }
            }

            // 6. Transactions, Journal Entries, and Journal Lines
            val txIdMap = mutableMapOf<Long, Long>()
            if (hasFullJournalArrays) {
                val existingTxBySig = dao.getTransactions(isDemo = false)
                    .associateBy { "${it.voucherNumber}|${it.dateEpochDay}|${it.amountPaise}|${it.transactionType.name}" }
                    .toMutableMap()

                val txArray = obj.optJSONArray("transactions") ?: JSONArray()
                for (i in 0 until txArray.length()) {
                    val item = txArray.getJSONObject(i)
                    val oldTxId = item.optLong("id", -(i + 1).toLong())
                    val voucher = item.optString("voucherNumber", "VCH-RESTORED-$i")
                    val type = converters.toTransactionType(item.optString("transactionType", "EXPENSE"))
                    val dateEpochDay = item.optLong("dateEpochDay", IndianFinancialYear.todayEpochDay())
                    val amountPaise = item.optLong("amountPaise", 0L)
                    if (amountPaise <= 0L) continue

                    val sig = "$voucher|$dateEpochDay|$amountPaise|${type.name}"
                    val existingTx = existingTxBySig[sig]
                    if (existingTx != null && mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                        txIdMap[oldTxId] = existingTx.id
                        continue
                    }

                    val srcAccId = when {
                        item.has("sourceAccountId") -> accountIdMap[item.optLong("sourceAccountId")]
                        item.has("sourceAccountName") -> existingAccountsByName[item.optString("sourceAccountName").trim().lowercase()]?.id
                        else -> null
                    }
                    val dstAccId = when {
                        item.has("destinationAccountId") -> accountIdMap[item.optLong("destinationAccountId")]
                        item.has("destinationAccountName") -> existingAccountsByName[item.optString("destinationAccountName").trim().lowercase()]?.id
                        else -> null
                    }
                    val mappedInvId = if (item.has("linkedInvestmentId")) investmentIdMap[item.optLong("linkedInvestmentId")] else null
                    val mappedLoanId = if (item.has("linkedLoanId")) loanIdMap[item.optLong("linkedLoanId")] else null
                    val mappedGcId = if (item.has("linkedGiftCardId")) giftCardIdMap[item.optLong("linkedGiftCardId")] else null
                    val mappedOrigTxId = if (item.has("linkedOriginalTransactionId")) txIdMap[item.optLong("linkedOriginalTransactionId")] else null

                    val newTxId = dao.insertTransaction(
                        TransactionEntity(
                            voucherNumber = voucher,
                            dateEpochDay = dateEpochDay,
                            amountPaise = amountPaise,
                            principalComponentPaise = item.optLong("principalComponentPaise", 0L),
                            interestComponentPaise = item.optLong("interestComponentPaise", 0L),
                            chargesPaise = item.optLong("chargesPaise", 0L),
                            costBasisDisposedPaise = item.optLong("costBasisDisposedPaise", 0L),
                            transactionType = type,
                            sourceAccountId = srcAccId,
                            destinationAccountId = dstAccId,
                            category = item.optString("category", "Other"),
                            description = item.optString("description", ""),
                            referenceNumber = item.optString("referenceNumber", ""),
                            notes = item.optString("notes", ""),
                            locationName = item.optString("locationName", ""),
                            latitude = if (item.has("latitude")) item.optDouble("latitude") else null,
                            longitude = if (item.has("longitude")) item.optDouble("longitude") else null,
                            attachmentName = item.optString("attachmentName", ""),
                            linkedInvestmentId = mappedInvId,
                            linkedLoanId = mappedLoanId,
                            linkedGiftCardId = mappedGcId,
                            linkedOriginalTransactionId = mappedOrigTxId,
                            isReversed = item.optBoolean("isReversed", false),
                            isDemo = false,
                            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                    )
                    txIdMap[oldTxId] = newTxId
                    dao.getTransactionById(newTxId)?.let { existingTxBySig[sig] = it }
                    restoredCount++
                }

                // Restore Journal Entries
                val journalEntryIdMap = mutableMapOf<Long, Long>()
                val existingEntryByVoucher = dao.getJournalEntries(isDemo = false)
                    .associateBy { "${it.voucherNumber}|${it.dateEpochDay}|${it.isReversal}" }
                    .toMutableMap()

                val jeArray = obj.optJSONArray("journal_entries") ?: JSONArray()
                for (i in 0 until jeArray.length()) {
                    val item = jeArray.getJSONObject(i)
                    val oldJeId = item.optLong("id", -(i + 1).toLong())
                    val oldTxId = item.optLong("transactionId", 0L)
                    val mappedTxId = if (oldTxId == 0L) 0L else (txIdMap[oldTxId] ?: continue)
                    val voucher = item.optString("voucherNumber", "VCH-JE-$i")
                    val dateEpochDay = item.optLong("dateEpochDay", IndianFinancialYear.todayEpochDay())
                    val isRev = item.optBoolean("isReversal", false)

                    val jeSig = "$voucher|$dateEpochDay|$isRev"
                    val existingJe = existingEntryByVoucher[jeSig]
                    if (existingJe != null && mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                        journalEntryIdMap[oldJeId] = existingJe.id
                        continue
                    }

                    val mappedReversedId = if (item.has("reversedJournalEntryId")) journalEntryIdMap[item.optLong("reversedJournalEntryId")] else null
                    val newJeId = dao.insertJournalEntry(
                        JournalEntryEntity(
                            transactionId = mappedTxId,
                            voucherNumber = voucher,
                            dateEpochDay = dateEpochDay,
                            narration = item.optString("narration", ""),
                            financialYear = item.optString("financialYear", IndianFinancialYear.getFinancialYearLabel(dateEpochDay)),
                            postingStatus = converters.toPostingStatus(item.optString("postingStatus", PostingStatus.POSTED.name)),
                            isReversal = isRev,
                            reversedJournalEntryId = mappedReversedId,
                            isDemo = false,
                            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                    )
                    journalEntryIdMap[oldJeId] = newJeId
                }

                // Restore Journal Lines
                val jlArray = obj.optJSONArray("journal_lines") ?: JSONArray()
                val existingLineEntryIds = if (mode == BackupRestoreMode.MERGE_KEEP_EXISTING) {
                    dao.getJournalLines(isDemo = false).map { it.journalEntryId }.toSet()
                } else emptySet()
                val linesToInsert = mutableListOf<JournalLineEntity>()

                for (i in 0 until jlArray.length()) {
                    val item = jlArray.getJSONObject(i)
                    val oldJeId = item.optLong("journalEntryId", 0L)
                    val mappedJeId = journalEntryIdMap[oldJeId] ?: continue
                    if (mappedJeId in existingLineEntryIds) continue

                    val oldTxId = item.optLong("transactionId", 0L)
                    val mappedTxId = if (oldTxId == 0L) 0L else (txIdMap[oldTxId] ?: 0L)
                    val oldLedgerId = item.optLong("ledgerAccountId", 0L)
                    val mappedLedgerId = ledgerIdMap[oldLedgerId] ?: continue

                    linesToInsert.add(
                        JournalLineEntity(
                            journalEntryId = mappedJeId,
                            transactionId = mappedTxId,
                            voucherNumber = item.optString("voucherNumber", ""),
                            dateEpochDay = item.optLong("dateEpochDay", IndianFinancialYear.todayEpochDay()),
                            ledgerAccountId = mappedLedgerId,
                            ledgerAccountName = item.optString("ledgerAccountName", ""),
                            ledgerFolio = item.optString("ledgerFolio", "LF-$mappedLedgerId"),
                            debitPaise = item.optLong("debitPaise", 0L),
                            creditPaise = item.optLong("creditPaise", 0L),
                            lineNarration = item.optString("lineNarration", ""),
                            isDemo = false
                        )
                    )
                }
                if (linesToInsert.isNotEmpty()) {
                    dao.insertJournalLines(linesToInsert)
                }

                // Restore Loan Payments
                val lpArray = obj.optJSONArray("loan_payments") ?: JSONArray()
                for (i in 0 until lpArray.length()) {
                    val item = lpArray.getJSONObject(i)
                    val mappedLoanId = loanIdMap[item.optLong("loanId")] ?: continue
                    val mappedTxId = txIdMap[item.optLong("transactionId")] ?: 0L
                    dao.insertLoanPayment(
                        LoanPaymentEntity(
                            loanId = mappedLoanId,
                            transactionId = mappedTxId,
                            dateEpochDay = item.optLong("dateEpochDay", IndianFinancialYear.todayEpochDay()),
                            totalPaymentPaise = item.optLong("totalPaymentPaise", 0L),
                            principalComponentPaise = item.optLong("principalComponentPaise", 0L),
                            interestComponentPaise = item.optLong("interestComponentPaise", 0L),
                            remainingPrincipalAfterPaise = item.optLong("remainingPrincipalAfterPaise", 0L),
                            isDemo = false
                        )
                    )
                }
            } else {
                // Legacy v1/v7 backup without raw journal lines: re-post transactions through AccountingEngine
                val existingTxSignatures = dao.getTransactions(isDemo = false)
                    .map { "${it.transactionType}|${it.dateEpochDay}|${it.amountPaise}|${it.description.trim().lowercase()}" }
                    .toMutableSet()

                val txArray = obj.optJSONArray("transactions")
                if (txArray != null) {
                    for (i in 0 until txArray.length()) {
                        val item = txArray.getJSONObject(i)
                        val type = converters.toTransactionType(item.optString("transactionType", "EXPENSE"))
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
            }

            // 7. Budgets
            val budgetsArray = obj.optJSONArray("budgets")
            if (budgetsArray != null) {
                for (i in 0 until budgetsArray.length()) {
                    val item = budgetsArray.getJSONObject(i)
                    val limit = item.optLong("limitPaise", 0L)
                    if (limit > 0L) {
                        val period = converters.toBudgetPeriod(item.optString("budgetPeriod", "MONTHLY"))
                        addBudget(
                            name = item.optString("name", "Budget"),
                            budgetPeriod = period,
                            category = item.optString("category", "OVERALL"),
                            limitPaise = limit,
                            financialYear = item.optString("financialYear", IndianFinancialYear.currentFyLabel()),
                            notes = item.optString("notes", ""),
                            isDemo = false
                        )
                        restoredCount++
                    }
                }
            }

            // 8. Goals
            val existingGoalsByName = dao.getGoals(isDemo = false)
                .map { it.name.trim().lowercase() }
                .toMutableSet()
            val goalsArray = obj.optJSONArray("goals")
            if (goalsArray != null) {
                for (i in 0 until goalsArray.length()) {
                    val item = goalsArray.getJSONObject(i)
                    val name = item.optString("name", "").trim()
                    val target = item.optLong("targetAmountPaise", 0L)
                    if (name.isBlank() || target <= 0L || existingGoalsByName.contains(name.lowercase())) continue
                    val mappedAcc = if (item.has("linkedAccountId")) accountIdMap[item.optLong("linkedAccountId")] else null
                    dao.insertGoal(
                        GoalEntity(
                            name = name,
                            goalType = converters.toGoalType(item.optString("goalType", GoalType.SAVINGS.name)),
                            targetAmountPaise = target,
                            currentSavedPaise = item.optLong("currentSavedPaise", 0L).coerceAtLeast(0L),
                            targetDateEpochDay = if (item.has("targetDateEpochDay")) item.optLong("targetDateEpochDay") else null,
                            linkedAccountId = mappedAcc,
                            notes = item.optString("notes", ""),
                            isDemo = false,
                            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                    )
                    existingGoalsByName.add(name.lowercase())
                    restoredCount++
                }
            }

            // 9. Subscriptions
            val existingSubsByName = dao.getSubscriptions(isDemo = false)
                .map { it.name.trim().lowercase() }
                .toMutableSet()
            val subsArray = obj.optJSONArray("subscriptions")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    val item = subsArray.getJSONObject(i)
                    val name = item.optString("name", "").trim()
                    val cost = item.optLong("costPaise", 0L)
                    if (name.isBlank() || cost <= 0L || existingSubsByName.contains(name.lowercase())) continue
                    val mappedAcc = if (item.has("paymentAccountId")) accountIdMap[item.optLong("paymentAccountId")] else null
                    dao.insertSubscription(
                        SubscriptionEntity(
                            name = name,
                            costPaise = cost,
                            billingFrequency = converters.toBillingFrequency(item.optString("billingFrequency", BillingFrequency.MONTHLY.name)),
                            nextRenewalEpochDay = item.optLong("nextRenewalEpochDay", IndianFinancialYear.todayEpochDay() + 30L),
                            paymentAccountId = mappedAcc,
                            startDateEpochDay = item.optLong("startDateEpochDay", IndianFinancialYear.todayEpochDay()),
                            endDateEpochDay = if (item.has("endDateEpochDay")) item.optLong("endDateEpochDay") else null,
                            notes = item.optString("notes", ""),
                            isActive = item.optBoolean("isActive", true),
                            isDemo = false,
                            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
                            customIntervalCount = item.optInt("customIntervalCount", 1).coerceAtLeast(1),
                            customIntervalUnit = item.optString("customIntervalUnit", "MONTHS")
                        )
                    )
                    existingSubsByName.add(name.lowercase())
                    restoredCount++
                }
            }

            // 10. Tax Records
            val existingTaxSigs = dao.getTaxRecords(isDemo = false)
                .map { "${it.taxType.name}|${it.financialYear}|${it.paymentDateEpochDay}|${it.amountPaidPaise}" }
                .toMutableSet()
            val taxesArray = obj.optJSONArray("taxes")
            if (taxesArray != null) {
                for (i in 0 until taxesArray.length()) {
                    val item = taxesArray.getJSONObject(i)
                    val tType = converters.toTaxType(item.optString("taxType", TaxType.INCOME_TAX.name))
                    val fy = item.optString("financialYear", IndianFinancialYear.currentFyLabel())
                    val payDate = item.optLong("paymentDateEpochDay", IndianFinancialYear.todayEpochDay())
                    val amt = item.optLong("amountPaidPaise", 0L)
                    if (amt <= 0L) continue
                    val sig = "${tType.name}|$fy|$payDate|$amt"
                    if (existingTaxSigs.contains(sig)) continue

                    val mappedAcc = if (item.has("paymentAccountId")) accountIdMap[item.optLong("paymentAccountId")] else null
                    val mappedTx = if (item.has("linkedTransactionId")) txIdMap[item.optLong("linkedTransactionId")] else null
                    dao.insertTaxRecord(
                        TaxRecordEntity(
                            taxType = tType,
                            financialYear = fy,
                            assessmentYear = item.optString("assessmentYear", IndianFinancialYear.getAssessmentYearForFy(fy)),
                            amountPaidPaise = amt,
                            dueDateEpochDay = if (item.has("dueDateEpochDay")) item.optLong("dueDateEpochDay") else null,
                            paymentDateEpochDay = payDate,
                            challanNumber = item.optString("challanNumber", ""),
                            paymentAccountId = mappedAcc,
                            linkedTransactionId = mappedTx,
                            supportingDocLabel = item.optString("supportingDocLabel", tType.displayName),
                            notes = item.optString("notes", ""),
                            isDemo = false,
                            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                    )
                    existingTaxSigs.add(sig)
                    restoredCount++
                }
            }

            // 11. Audit Logs
            val existingAuditSigs = dao.getAuditLogs(isDemo = false)
                .map { "${it.timestampMillis}|${it.action}|${it.summary}" }
                .toMutableSet()
            val auditArray = obj.optJSONArray("audit_logs")
            if (auditArray != null) {
                for (i in 0 until auditArray.length()) {
                    val item = auditArray.getJSONObject(i)
                    val ts = item.optLong("timestampMillis", System.currentTimeMillis())
                    val act = item.optString("action", "VOUCHER_POSTED")
                    val sum = item.optString("summary", "")
                    val sig = "$ts|$act|$sum"
                    if (sum.isBlank() || existingAuditSigs.contains(sig)) continue
                    dao.insertAuditLog(
                        AuditLogEntity(
                            timestampMillis = ts,
                            action = act,
                            entityType = item.optString("entityType", "Transaction"),
                            entityId = item.optLong("entityId", 0L),
                            summary = sum,
                            isDemo = false
                        )
                    )
                    existingAuditSigs.add(sig)
                }
            }

            // Final double-entry integrity assertion before committing transaction
            val allRestoredLines = dao.getJournalLines(isDemo = false)
            require(AccountingEngine.verifyLedgerIntegrity(allRestoredLines)) {
                "Restore aborted: restored journal lines failed double-entry balance verification (Debits != Credits)."
            }
        }
        return restoredCount
    }
}
