package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import com.example.data.local.AccountType
import com.example.data.local.CompoundingFrequency
import com.example.data.local.InvestmentCategory
import com.example.data.local.InvestmentEntity
import com.example.data.local.TransactionType
import com.example.domain.calculators.FinancialCalculators
import com.example.domain.money.InrMoney
import com.example.domain.period.IndianFinancialYear
import com.example.ui.AccountWithBalance
import com.example.ui.FinanceVaultUiState
import com.example.ui.components.EditTransactionDialog
import com.example.ui.components.IndianCurrencyVisualTransformation
import com.example.ui.components.VaultChooseSelector
import com.example.ui.components.VaultGlassCard
import com.example.ui.components.accountTypeIcon
import com.example.ui.components.dismissKeyboardOnSwipeDown
import com.example.ui.components.investmentCategoryIcon
import com.example.ui.components.transactionTypeIcon
import com.example.ui.theme.VaultCardElevated
import com.example.ui.theme.VaultCardSurface
import com.example.ui.theme.VaultCoral
import com.example.ui.theme.VaultCyan
import com.example.ui.theme.VaultEmerald
import com.example.ui.theme.VaultGold
import com.example.ui.theme.VaultTextMuted
import com.example.ui.theme.VaultTextPrimary
import com.example.ui.theme.VaultTextSecondary

@Composable
fun ActivityScreen(
    uiState: FinanceVaultUiState,
    searchQuery: String = "",
    onOpenAddTransaction: () -> Unit,
    onReverseTransaction: (Long) -> Unit,
    onEditTransaction: (Long, Long, Long, Long?, Long?, String, String, String, String) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onBulkReverseTransactions: (Set<Long>) -> Unit = {},
    onBulkCategorizeTransactions: (Set<Long>, String) -> Unit = { _, _ -> },
    onAddCustomOption: (String, String) -> Unit = { _, _ -> }
) {
    // Starts unselected on "Choose" (null) — shows all transactions until a filter is chosen
    var selectedTypeFilter by remember { mutableStateOf<TransactionType?>(null) }
    val isHidden = uiState.settings.isPrivacyModeEnabled

    val filteredTransactions = remember(uiState.transactions, searchQuery, selectedTypeFilter) {
        if (selectedTypeFilter == null && searchQuery.isBlank()) {
            uiState.transactions
        } else {
            uiState.transactions.filter { tx ->
                val matchesType = selectedTypeFilter == null || tx.transactionType == selectedTypeFilter
                val matchesQuery = searchQuery.isBlank() || tx.matchesSearchQuery(searchQuery)
                matchesType && matchesQuery
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val dragCoords = remember { FloatArray(4) } // [0]=listTopY, [1]=listBottomY, [2]=dragStartY, [3]=dragCurrentY
    var selectedTxIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dragInitialIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dragAnchorTxId by remember { mutableStateOf<Long?>(null) }
    var isFingerPressedSelecting by remember { mutableStateOf(false) }
    var showBulkCategorizeDialog by remember { mutableStateOf(false) }
    var pendingDeleteTxIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editingTransaction by remember { mutableStateOf<com.example.data.local.TransactionEntity?>(null) }
    val itemBoundsInRoot = remember { mutableMapOf<Long, ClosedFloatingPointRange<Float>>() }
    val isBatchSelectionMode by remember { derivedStateOf { selectedTxIds.isNotEmpty() } }

    fun updateSelectionForRange(startY: Float, currentY: Float) {
        val minY = min(startY, currentY)
        val maxY = max(startY, currentY)
        val swipedIds = itemBoundsInRoot.filter { (_, bounds) ->
            bounds.endInclusive >= minY && bounds.start <= maxY
        }.keys
        val anchorId = dragAnchorTxId
        val indexRangeIds = if (anchorId != null && swipedIds.isNotEmpty()) {
            val anchorIdx = filteredTransactions.indexOfFirst { it.id == anchorId }
            val hitIndices = filteredTransactions.mapIndexedNotNull { idx, item ->
                if (item.id in swipedIds) idx else null
            }
            if (anchorIdx >= 0 && hitIndices.isNotEmpty()) {
                val minIdx = min(anchorIdx, hitIndices.minOrNull() ?: anchorIdx)
                val maxIdx = max(anchorIdx, hitIndices.maxOrNull() ?: anchorIdx)
                filteredTransactions.subList(minIdx, maxIdx + 1).map { it.id }.toSet()
            } else emptySet()
        } else emptySet()
        val nextIds = dragInitialIds + swipedIds + indexRangeIds
        if (nextIds != selectedTxIds) {
            selectedTxIds = nextIds
        }
    }

    LaunchedEffect(isFingerPressedSelecting) {
        while (isFingerPressedSelecting) {
            val listTopRootY = dragCoords[0]
            val listBottomRootY = dragCoords[1]
            val activeDragStartY = dragCoords[2]
            val activeDragCurrentY = dragCoords[3]
            val edgeZone = 140f
            val scrollDelta = when {
                listBottomRootY > listTopRootY && activeDragCurrentY > (listBottomRootY - edgeZone) -> {
                    ((activeDragCurrentY - (listBottomRootY - edgeZone)).coerceIn(10f, 160f)) * 0.35f
                }
                listBottomRootY > listTopRootY && activeDragCurrentY < (listTopRootY + edgeZone) -> {
                    -((listTopRootY + edgeZone - activeDragCurrentY).coerceIn(10f, 160f)) * 0.35f
                }
                else -> 0f
            }
            if (scrollDelta != 0f) {
                val consumed = lazyListState.scrollBy(scrollDelta)
                if (consumed != 0f) {
                    updateSelectionForRange(activeDragStartY, activeDragCurrentY)
                }
            }
            delay(16L)
        }
    }

    if (pendingDeleteTxIds.isNotEmpty()) {
        ConfirmDeleteTransactionsDialog(
            deleteCount = pendingDeleteTxIds.size,
            onDismiss = { pendingDeleteTxIds = emptySet() },
            onConfirmDelete = {
                val idsToDelete = pendingDeleteTxIds
                pendingDeleteTxIds = emptySet()
                if (idsToDelete.size == 1 && selectedTxIds.isEmpty()) {
                    onReverseTransaction(idsToDelete.first())
                } else {
                    onBulkReverseTransactions(idsToDelete)
                    selectedTxIds = selectedTxIds - idsToDelete
                }
            }
        )
    }

    if (showBulkCategorizeDialog && selectedTxIds.isNotEmpty()) {
        BulkCategorizeTransactionsDialog(
            selectedCount = selectedTxIds.size,
            customCategories = uiState.customOptionsMap["TX_CATEGORY"].orEmpty(),
            onAddCustomCategory = { onAddCustomOption("TX_CATEGORY", it) },
            onDismiss = { showBulkCategorizeDialog = false },
            onConfirmCategory = { newCat ->
                onBulkCategorizeTransactions(selectedTxIds, newCat)
                showBulkCategorizeDialog = false
                selectedTxIds = emptySet()
            }
        )
    }

    editingTransaction?.let { txToEdit ->
        EditTransactionDialog(
            tx = txToEdit,
            accounts = uiState.accountsWithBalances,
            customCategories = uiState.customOptionsMap["TX_CATEGORY"].orEmpty(),
            onAddCustomCategory = { onAddCustomOption("TX_CATEGORY", it) },
            onDismiss = { editingTransaction = null },
            onSave = { newDate, newAmt, newSrc, newDst, newCat, newDesc, newLoc, newNotes ->
                onEditTransaction(txToEdit.id, newDate, newAmt, newSrc, newDst, newCat, newDesc, newLoc, newNotes)
                editingTransaction = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Delete / Categorize menu appears right when the user lifts their finger off the screen
        if (isBatchSelectionMode && !isFingerPressedSelecting) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                TransactionBatchActionBar(
                    selectedCount = selectedTxIds.size,
                    totalSelectableCount = filteredTransactions.size,
                    onSelectAll = { selectedTxIds = filteredTransactions.map { it.id }.toSet() },
                    onClearSelection = { selectedTxIds = emptySet() },
                    onCategorizeSelected = { showBulkCategorizeDialog = true },
                    onDeleteSelected = {
                        pendingDeleteTxIds = selectedTxIds
                    }
                )
            }
        }

        LazyColumn(
            state = lazyListState,
            userScrollEnabled = !isFingerPressedSelecting,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    dragCoords[0] = pos.y
                    dragCoords[1] = pos.y + coords.size.height
                }
                .dismissKeyboardOnSwipeDown()
                .testTag("activity_screen"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Transaction Activity",
                            style = MaterialTheme.typography.headlineMedium,
                            color = VaultTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onOpenAddTransaction,
                        colors = ButtonDefaults.buttonColors(containerColor = VaultEmerald),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("activity_add_tx_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Record", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Single-tab "Choose" selector for Transaction Type Filter (with Reset option)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        VaultChooseSelector(
                            label = "Filter by Transaction Type (${filteredTransactions.size} shown)",
                            selectedItem = selectedTypeFilter,
                            options = TransactionType.entries,
                            onSelect = { selectedTypeFilter = it },
                            itemLabel = { it.userLabel },
                            itemIcon = { transactionTypeIcon(it) },
                            placeholder = "Choose",
                            testTag = "activity_type_choose_selector"
                        )
                    }
                    if (selectedTypeFilter != null) {
                        OutlinedButton(
                            onClick = { selectedTypeFilter = null },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Filter", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("All", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (filteredTransactions.isEmpty()) {
                item {
                    VaultGlassCard {
                        Text(
                            text = "No matching transactions recorded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultTextSecondary
                        )
                    }
                }
            } else {
                items(
                    items = filteredTransactions,
                    key = { it.id },
                    contentType = { "tx_row" }
                ) { tx ->
                    TransactionRowCard(
                        tx = tx,
                        isHidden = isHidden,
                        onReverseClick = { pendingDeleteTxIds = setOf(tx.id) },
                        onEditClick = { editingTransaction = tx },
                        isBatchSelectionMode = isBatchSelectionMode,
                        isSelected = tx.id in selectedTxIds,
                        onBoundsMeasured = { id, range -> itemBoundsInRoot[id] = range },
                        onToggleSelect = { id ->
                            selectedTxIds = if (id in selectedTxIds) selectedTxIds - id else selectedTxIds + id
                        },
                        onStartHoldDragSelection = { id ->
                            dragAnchorTxId = id
                            dragInitialIds = selectedTxIds + id
                            selectedTxIds = dragInitialIds
                        },
                        onDragSelectRange = { startY, currentY ->
                            dragCoords[2] = startY
                            dragCoords[3] = currentY
                            updateSelectionForRange(startY, currentY)
                        },
                        onDragStateChanged = { dragging, startY, currentY ->
                            isFingerPressedSelecting = dragging
                            dragCoords[2] = startY
                            dragCoords[3] = currentY
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(68.dp)) }
        }
    }
}

/**
 * Investments Screen (Equities, ETFs, Mutual Funds, SIPs, FD, RD, Bonds, Gold, Silver).
 * Strictly distinguishes Invested Amount, Current Recorded Value (never fabricated!),
 * Realized P&L, Unrealized P&L, Interest/Dividends, CAGR, XIRR, and FD/RD Projected Maturity.
 */
@Composable
fun InvestmentsScreen(
    uiState: FinanceVaultUiState,
    onAddInvestment: (
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
        dividendsReceivedPaise: Long,
        interestEarnedPaise: Long
    ) -> Unit,
    onUpdateRecordedValuation: (
        investmentId: Long,
        recordedValuePaise: Long?,
        updatedUnits: Double?,
        updatedBuyPricePerUnitPaise: Long?,
        updatedAnnualRatePercent: Double?,
        updatedCompounding: CompoundingFrequency?,
        updatedDurationMonths: Int?,
        updatedDividendsPaise: Long?,
        updatedInterestPaise: Long?
    ) -> Unit,
    onAddCustomOption: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    BackHandler { onBack() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingValuationInv by remember { mutableStateOf<InvestmentEntity?>(null) }
    val isHidden = uiState.settings.isPrivacyModeEnabled

    val analysesById = remember(uiState.investments) {
        val today = IndianFinancialYear.todayEpochDay()
        uiState.investments.associate { inv ->
            val holdingDays = (today - inv.purchaseDateEpochDay).coerceAtLeast(1L)
            val derivedUnitCurPrice = if (inv.isCurrentValueRecorded && inv.currentRecordedValuePaise != null && inv.units > 0.0) {
                (inv.currentRecordedValuePaise / inv.units).toLong()
            } else null
            inv.id to FinancialCalculators.analyzeInvestmentGrowth(
                units = inv.units,
                purchasePricePerUnitPaise = inv.purchasePricePerUnitPaise,
                investedAmountPaise = inv.investedAmountPaise,
                currentRecordedValuePaise = if (inv.isCurrentValueRecorded) inv.currentRecordedValuePaise else null,
                isCurrentValueRecorded = inv.isCurrentValueRecorded,
                currentUnitPricePaise = derivedUnitCurPrice,
                annualGrowthOrInterestRatePercent = inv.annualInterestRatePercent,
                compoundingPeriodsPerYear = inv.compoundingFrequency.periodsPerYear,
                durationMonths = inv.durationMonths,
                holdingDays = holdingDays,
                chargesPaise = inv.chargesPaise,
                dividendsAndInterestPaise = inv.dividendsReceivedPaise + inv.interestEarnedPaise,
                isRecurringMonthly = inv.category == InvestmentCategory.RECURRING_DEPOSIT || inv.category == InvestmentCategory.SIP
            )
        }
    }

    val totalInvested = remember(uiState.investments) { uiState.investments.sumOf { it.investedAmountPaise } }
    val totalCurrentRecorded = remember(uiState.investments, analysesById) {
        uiState.investments.sumOf { inv ->
            analysesById[inv.id]?.effectiveCurrentValuePaise ?: inv.investedAmountPaise
        }
    }
    val hasAnyRecordedValuation = remember(uiState.investments) {
        uiState.investments.isNotEmpty()
    }
    val totalUnrealized = if (hasAnyRecordedValuation) totalCurrentRecorded - totalInvested else 0L
    val totalRealized = remember(uiState.investments) { uiState.investments.sumOf { it.realizedProfitLossPaise } }
    val totalInterestAndDiv = remember(uiState.investments) {
        uiState.investments.sumOf { it.interestEarnedPaise + it.dividendsReceivedPaise }
    }
    val portfolioAbsReturn = remember(totalInvested, totalCurrentRecorded) {
        if (totalInvested > 0L) FinancialCalculators.calculateAbsoluteReturnPercent(totalInvested, totalCurrentRecorded) else 0.0
    }

    // Portfolio XIRR from actual investment purchase cash flows and current valuation
    val portfolioXirr = remember(uiState.investments, totalCurrentRecorded) {
        if (uiState.investments.isEmpty() || !hasAnyRecordedValuation) null
        else {
            val flows = mutableListOf<FinancialCalculators.CashFlowPoint>()
            uiState.investments.forEach { inv ->
                flows.add(FinancialCalculators.CashFlowPoint(inv.purchaseDateEpochDay, -(inv.investedAmountPaise + inv.chargesPaise)))
            }
            flows.add(FinancialCalculators.CashFlowPoint(IndianFinancialYear.todayEpochDay(), totalCurrentRecorded))
            FinancialCalculators.calculateXirrPercent(flows)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("investments_screen"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("investments_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = VaultTextPrimary
                        )
                    }
                    Text(
                        text = "Investment Portfolio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = VaultTextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultEmerald),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("add_investment_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Holding", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Portfolio Summary Card
        item {
            VaultGlassCard(accentColor = VaultCyan) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Invested Capital", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                            Text(
                                text = if (uiState.investments.isEmpty()) "Not recorded"
                                else InrMoney.formatPaise(totalInvested, isHidden = isHidden),
                                style = MaterialTheme.typography.titleLarge,
                                color = VaultTextPrimary
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text("Current Wealth Value", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                            Text(
                                text = when {
                                    uiState.investments.isEmpty() -> "Not recorded"
                                    else -> InrMoney.formatPaise(totalCurrentRecorded, isHidden = isHidden)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = VaultCyan
                            )
                        }
                    }

                    HorizontalDivider(color = VaultCardElevated)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unrealized P&L", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                            Text(
                                text = if (!hasAnyRecordedValuation) "Not recorded"
                                else "${InrMoney.formatPaise(totalUnrealized, isHidden = isHidden)} ($portfolioAbsReturn%)",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (totalUnrealized >= 0) VaultEmerald else VaultCoral
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Realized P&L", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                            Text(
                                text = if (uiState.investments.isEmpty()) "Not recorded"
                                else InrMoney.formatPaise(totalRealized, isHidden = isHidden),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (totalRealized >= 0) VaultEmerald else VaultCoral
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text("Int & Div", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                            Text(
                                text = if (uiState.investments.isEmpty()) "Not recorded"
                                else InrMoney.formatPaise(totalInterestAndDiv, isHidden = isHidden),
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultGold
                            )
                        }
                    }

                    if (portfolioXirr != null) {
                        Text(
                            text = "Portfolio Annualized Growing Rate (XIRR): $portfolioXirr% p.a. • Synced to Balance Sheet Net Worth",
                            style = MaterialTheme.typography.labelMedium,
                            color = VaultEmerald
                        )
                    }
                }
            }
        }

        if (uiState.investments.isEmpty()) {
            item {
                VaultGlassCard {
                    Text(
                        text = "No Investment Holdings Recorded",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultTextPrimary
                    )
                }
            }
        } else {
            items(
                items = uiState.investments,
                key = { it.id },
                contentType = { "investment_card" }
            ) { inv ->
                InvestmentHoldingCard(
                    inv = inv,
                    analysis = analysesById[inv.id],
                    isHidden = isHidden,
                    onEditValuation = { editingValuationInv = inv }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    if (showAddDialog) {
        AddInvestmentDialog(
            accounts = uiState.accountsWithBalances,
            customCategories = uiState.customOptionsMap["INVESTMENT_CATEGORY"].orEmpty(),
            onAddCustomCategory = { onAddCustomOption("INVESTMENT_CATEGORY", it) },
            onDismiss = { showAddDialog = false },
            onSave = { name, cat, date, qty, units, price, invested, charges, curVal, isCurRec, payAcc, rate, comp, dur, notes, divPaise, intPaise ->
                onAddInvestment(name, cat, date, qty, units, price, invested, charges, curVal, isCurRec, payAcc, rate, comp, dur, notes, divPaise, intPaise)
                showAddDialog = false
            }
        )
    }

    if (editingValuationInv != null) {
        val target = editingValuationInv!!
        UpdateInvestmentValuationDialog(
            inv = target,
            onDismiss = { editingValuationInv = null },
            onSave = { curValPaise, units, buyPricePaise, annualRate, compFreq, durMonths, divPaise, intPaise ->
                onUpdateRecordedValuation(
                    target.id,
                    curValPaise,
                    units,
                    buyPricePaise,
                    annualRate,
                    compFreq,
                    durMonths,
                    divPaise,
                    intPaise
                )
                editingValuationInv = null
            }
        )
    }
}

@Composable
private fun InvestmentHoldingCard(
    inv: InvestmentEntity,
    analysis: FinancialCalculators.InvestmentGrowthAnalysis?,
    isHidden: Boolean,
    onEditValuation: () -> Unit
) {
    val currentValPaise = analysis?.effectiveCurrentValuePaise
        ?: if (inv.isCurrentValueRecorded && inv.currentRecordedValuePaise != null) inv.currentRecordedValuePaise else inv.investedAmountPaise
    val currentUnitPricePaise = analysis?.currentPricePerUnitPaise
        ?: if (inv.units > 0.0) (currentValPaise / inv.units).toLong() else currentValPaise
    val diff = currentValPaise - inv.investedAmountPaise
    val absRet = analysis?.absoluteReturnPercent
        ?: FinancialCalculators.calculateAbsoluteReturnPercent(inv.investedAmountPaise, currentValPaise)
    val cagr = analysis?.annualizedGrowthRatePercent ?: inv.annualInterestRatePercent ?: 0.0
    val monthlyGrowth = analysis?.monthlyGrowthRatePercent ?: 0.0
    val oneYrProj = analysis?.projectedOneYearValuePaise ?: currentValPaise
    val matVal = analysis?.projectedMaturityValuePaise

    VaultGlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(VaultEmerald.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = investmentCategoryIcon(inv.category),
                            contentDescription = null,
                            tint = VaultEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(inv.name, style = MaterialTheme.typography.titleMedium, color = VaultTextPrimary)
                        Text(
                            text = "${inv.category.displayName} • ${inv.units} units • Purchased ${IndianFinancialYear.formatEpochDay(inv.purchaseDateEpochDay)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultTextSecondary
                        )
                    }
                }
                OutlinedButton(
                    onClick = onEditValuation,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("update_investment_value_${inv.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Calc / Edit", style = MaterialTheme.typography.labelSmall)
                }
            }

            HorizontalDivider(color = VaultCardElevated)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Invested Capital", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                    Text(
                        text = InrMoney.formatPaise(inv.investedAmountPaise, isHidden = isHidden),
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultTextPrimary
                    )
                    if (inv.purchasePricePerUnitPaise > 0L) {
                        Text(
                            text = "Buy @ ${InrMoney.formatPaise(inv.purchasePricePerUnitPaise, isHidden = isHidden)}/unit",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultTextMuted
                        )
                    }
                }
                Column {
                    Text("Current Value", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                    Text(
                        text = InrMoney.formatPaise(currentValPaise, isHidden = isHidden),
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultCyan
                    )
                    if (currentUnitPricePaise > 0L) {
                        Text(
                            text = "Now @ ${InrMoney.formatPaise(currentUnitPricePaise, isHidden = isHidden)}/unit",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultCyan
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Unrealized Gain", style = MaterialTheme.typography.labelSmall, color = VaultTextSecondary)
                    Text(
                        text = "${InrMoney.formatPaise(diff, isHidden = isHidden)} ($absRet%)",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (diff >= 0) VaultEmerald else VaultCoral
                    )
                    Text(
                        text = "Growth: $cagr% p.a.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cagr >= 0.0) VaultEmerald else VaultCoral
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Growing Rate: $cagr% p.a. (${monthlyGrowth}% / mo)",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultEmerald
                )
                Text(
                    text = "1-Yr Projected: ${InrMoney.formatPaise(oneYrProj, isHidden = isHidden)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultGold
                )
            }

            if (matVal != null && inv.durationMonths != null) {
                Text(
                    text = "Maturity Value (${inv.durationMonths}m @ ${inv.annualInterestRatePercent ?: cagr}% ${inv.compoundingFrequency.displayName}): ${InrMoney.formatPaise(matVal, isHidden = isHidden)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultGold
                )
            }
        }
    }
}

@Composable
private fun UpdateInvestmentValuationDialog(
    inv: InvestmentEntity,
    onDismiss: () -> Unit,
    onSave: (
        recordedValuePaise: Long?,
        updatedUnits: Double?,
        updatedBuyPricePerUnitPaise: Long?,
        updatedAnnualRatePercent: Double?,
        updatedCompounding: CompoundingFrequency?,
        updatedDurationMonths: Int?,
        updatedDividendsPaise: Long?,
        updatedInterestPaise: Long?
    ) -> Unit
) {
    val today = IndianFinancialYear.todayEpochDay()
    val holdingDays = (today - inv.purchaseDateEpochDay).coerceAtLeast(1L)

    var unitsInput by remember(inv) { mutableStateOf(inv.units.toString()) }
    var buyUnitPriceInput by remember(inv) {
        mutableStateOf(
            if (inv.purchasePricePerUnitPaise > 0L) InrMoney.paiseToPlainRupeeInput(inv.purchasePricePerUnitPaise) else ""
        )
    }
    var currentUnitPriceInput by remember(inv) {
        val curUnit = if (inv.isCurrentValueRecorded && inv.currentRecordedValuePaise != null && inv.units > 0.0) {
            (inv.currentRecordedValuePaise / inv.units).toLong()
        } else 0L
        mutableStateOf(if (curUnit > 0L) InrMoney.paiseToPlainRupeeInput(curUnit) else "")
    }
    var valInput by remember(inv) {
        mutableStateOf(
            inv.currentRecordedValuePaise?.let { InrMoney.paiseToPlainRupeeInput(it) } ?: ""
        )
    }
    var rateInput by remember(inv) {
        mutableStateOf(inv.annualInterestRatePercent?.toString() ?: "")
    }
    var durationMonthsInput by remember(inv) {
        mutableStateOf(inv.durationMonths?.toString() ?: "")
    }
    var compounding by remember(inv) { mutableStateOf(inv.compoundingFrequency) }
    var dividendsInput by remember(inv) {
        mutableStateOf(if (inv.dividendsReceivedPaise > 0L) InrMoney.paiseToPlainRupeeInput(inv.dividendsReceivedPaise) else "")
    }
    var interestInput by remember(inv) {
        mutableStateOf(if (inv.interestEarnedPaise > 0L) InrMoney.paiseToPlainRupeeInput(inv.interestEarnedPaise) else "")
    }

    val parsedUnits = unitsInput.toDoubleOrNull()?.coerceAtLeast(0.0001) ?: inv.units
    val parsedBuyUnitPaise = InrMoney.parseInrToPaise(buyUnitPriceInput) ?: inv.purchasePricePerUnitPaise
    val parsedCurUnitPaise = InrMoney.parseInrToPaise(currentUnitPriceInput)
    val parsedTotalCurPaise = InrMoney.parseInrToPaise(valInput)
    val parsedRate = rateInput.toDoubleOrNull()
    val parsedDuration = durationMonthsInput.toIntOrNull()
    val parsedDivPaise = InrMoney.parseInrToPaise(dividendsInput) ?: 0L
    val parsedIntPaise = InrMoney.parseInrToPaise(interestInput) ?: 0L

    val liveAnalysis = remember(
        inv, parsedUnits, parsedBuyUnitPaise, parsedCurUnitPaise, parsedTotalCurPaise,
        parsedRate, compounding, parsedDuration, parsedDivPaise, parsedIntPaise
    ) {
        FinancialCalculators.analyzeInvestmentGrowth(
            units = parsedUnits,
            purchasePricePerUnitPaise = parsedBuyUnitPaise,
            investedAmountPaise = inv.investedAmountPaise,
            currentRecordedValuePaise = parsedTotalCurPaise,
            isCurrentValueRecorded = parsedTotalCurPaise != null && parsedTotalCurPaise > 0L,
            currentUnitPricePaise = parsedCurUnitPaise,
            annualGrowthOrInterestRatePercent = parsedRate,
            compoundingPeriodsPerYear = compounding.periodsPerYear,
            durationMonths = parsedDuration,
            holdingDays = holdingDays,
            chargesPaise = inv.chargesPaise,
            dividendsAndInterestPaise = parsedDivPaise + parsedIntPaise,
            isRecurringMonthly = inv.category == InvestmentCategory.RECURRING_DEPOSIT || inv.category == InvestmentCategory.SIP
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VaultCardSurface,
        title = { Text("Valuation & Growth Calculator: ${inv.name}", color = VaultTextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unitsInput,
                        onValueChange = { unitsInput = it },
                        label = { Text("Shares / Units") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = buyUnitPriceInput,
                        onValueChange = { buyUnitPriceInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Buy Price / Unit (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentUnitPriceInput,
                        onValueChange = {
                            currentUnitPriceInput = InrMoney.sanitizeIndianNumericInput(it)
                            val unitP = InrMoney.parseInrToPaise(currentUnitPriceInput)
                            if (unitP != null && parsedUnits > 0.0) {
                                valInput = InrMoney.paiseToPlainRupeeInput((unitP * parsedUnits).toLong())
                            }
                        },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Current Share/NAV (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = valInput,
                        onValueChange = { valInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Current Total Value (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rateInput,
                        onValueChange = { rateInput = it },
                        label = { Text("Annual Growth/Int (% p.a.)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = durationMonthsInput,
                        onValueChange = { durationMonthsInput = it },
                        label = { Text("Tenure (Months)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                VaultChooseSelector(
                    label = "Compounding Frequency",
                    selectedItem = compounding,
                    options = CompoundingFrequency.entries,
                    onSelect = { compounding = it },
                    itemLabel = { it.displayName },
                    itemIcon = { Icons.Default.Autorenew },
                    placeholder = "Choose"
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dividendsInput,
                        onValueChange = { dividendsInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Dividends Received (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = interestInput,
                        onValueChange = { interestInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Interest Earned (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                VaultGlassCard(accentColor = VaultEmerald) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "LIVE VALUATION & GROWING RATE",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultEmerald
                        )
                        Text(
                            text = "Calculated Current Value: ${InrMoney.formatPaise(liveAnalysis.effectiveCurrentValuePaise)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultCyan
                        )
                        Text(
                            text = "Current Share/Unit Value: ${InrMoney.formatPaise(liveAnalysis.currentPricePerUnitPaise)} / unit",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultTextPrimary
                        )
                        Text(
                            text = "Annualized Growing Rate (CAGR): ${liveAnalysis.annualizedGrowthRatePercent}% p.a. (${liveAnalysis.monthlyGrowthRatePercent}% / mo)",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultEmerald
                        )
                        Text(
                            text = "Absolute Return: ${liveAnalysis.absoluteReturnPercent}% • Net Gain: ${InrMoney.formatPaise(liveAnalysis.totalNetGainPaise)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (liveAnalysis.totalNetGainPaise >= 0) VaultEmerald else VaultCoral
                        )
                        Text(
                            text = "1-Year Projected Value: ${InrMoney.formatPaise(liveAnalysis.projectedOneYearValuePaise)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultGold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        liveAnalysis.effectiveCurrentValuePaise,
                        parsedUnits,
                        parsedBuyUnitPaise,
                        parsedRate ?: liveAnalysis.annualizedGrowthRatePercent,
                        compounding,
                        parsedDuration,
                        parsedDivPaise,
                        parsedIntPaise
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = VaultEmerald)
            ) {
                Text("Sync Valuation & Growth")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = VaultTextSecondary)
            }
        }
    )
}

@Composable
private fun AddInvestmentDialog(
    accounts: List<AccountWithBalance>,
    customCategories: List<String> = emptyList(),
    onAddCustomCategory: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (
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
        dividendsReceivedPaise: Long,
        interestEarnedPaise: Long
    ) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val bankAndCashAccounts = remember(accounts) {
        accounts.map { it.account }.filter { !it.isArchived && it.accountType != AccountType.CREDIT_CARD }
    }

    var name by remember { mutableStateOf("") }
    // Always start unselected on "Choose" (null) — never pre-select a default!
    var category by remember { mutableStateOf<InvestmentCategory?>(null) }
    var customCategoryLabel by remember { mutableStateOf("") }
    var holdingDaysInput by remember { mutableStateOf("365") }
    var unitsInput by remember { mutableStateOf("1") }
    var buyUnitPriceInput by remember { mutableStateOf("") }
    var investedInput by remember { mutableStateOf("") }
    var currentUnitPriceInput by remember { mutableStateOf("") }
    var currentValInput by remember { mutableStateOf("") }
    var chargesInput by remember { mutableStateOf("") }
    var dividendsInput by remember { mutableStateOf("") }
    var interestInput by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var rateInput by remember { mutableStateOf("") }
    var durationMonthsInput by remember { mutableStateOf("12") }
    var compounding by remember { mutableStateOf<CompoundingFrequency?>(null) }
    var notes by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var highlightMissing by remember { mutableStateOf(false) }
    var catExpandTrigger by remember { mutableIntStateOf(0) }
    val nameFocusRequester = remember { FocusRequester() }
    val amountFocusRequester = remember { FocusRequester() }

    val parsedHoldingDays = holdingDaysInput.toLongOrNull()?.coerceAtLeast(1L) ?: 365L
    val parsedUnits = unitsInput.toDoubleOrNull()?.coerceAtLeast(0.0001) ?: 1.0
    val parsedBuyUnitPaise = InrMoney.parseInrToPaise(buyUnitPriceInput)
    val parsedInvestedPaise = InrMoney.parseInrToPaise(investedInput)
        ?: if (parsedBuyUnitPaise != null && parsedBuyUnitPaise > 0L) (parsedBuyUnitPaise * parsedUnits).toLong() else 0L
    val parsedCurUnitPaise = InrMoney.parseInrToPaise(currentUnitPriceInput)
    val parsedCurTotalPaise = InrMoney.parseInrToPaise(currentValInput)
    val parsedChargesPaise = InrMoney.parseInrToPaise(chargesInput) ?: 0L
    val parsedDivPaise = InrMoney.parseInrToPaise(dividendsInput) ?: 0L
    val parsedIntPaise = InrMoney.parseInrToPaise(interestInput) ?: 0L
    val parsedRate = rateInput.toDoubleOrNull()
    val parsedDurMonths = durationMonthsInput.toIntOrNull()

    val livePreview = remember(
        category, parsedInvestedPaise, parsedUnits, parsedBuyUnitPaise,
        parsedCurUnitPaise, parsedCurTotalPaise, parsedRate, parsedHoldingDays,
        compounding, parsedDurMonths, parsedChargesPaise, parsedDivPaise, parsedIntPaise
    ) {
        if (parsedInvestedPaise <= 0L) null
        else FinancialCalculators.analyzeInvestmentGrowth(
            units = parsedUnits,
            purchasePricePerUnitPaise = parsedBuyUnitPaise ?: (parsedInvestedPaise / parsedUnits).toLong(),
            investedAmountPaise = parsedInvestedPaise,
            currentRecordedValuePaise = parsedCurTotalPaise,
            isCurrentValueRecorded = parsedCurTotalPaise != null && parsedCurTotalPaise > 0L,
            currentUnitPricePaise = parsedCurUnitPaise,
            annualGrowthOrInterestRatePercent = parsedRate,
            compoundingPeriodsPerYear = (compounding ?: CompoundingFrequency.QUARTERLY).periodsPerYear,
            durationMonths = parsedDurMonths,
            holdingDays = parsedHoldingDays,
            chargesPaise = parsedChargesPaise,
            dividendsAndInterestPaise = parsedDivPaise + parsedIntPaise,
            isRecurringMonthly = category == InvestmentCategory.RECURRING_DEPOSIT || category == InvestmentCategory.SIP
        )
    }

    fun submitInvestment() {
        keyboardController?.hide()
        val chosenCategory = category
        if (chosenCategory == null) {
            highlightMissing = true
            errorMsg = "Please select an Investment Category from the opened menu."
            catExpandTrigger++
            return
        }
        if (name.isBlank()) {
            highlightMissing = true
            errorMsg = "Please enter an investment name."
            scope.launch {
                runCatching { nameFocusRequester.requestFocus() }
                keyboardController?.show()
            }
            return
        }
        val invPaise = parsedInvestedPaise
        if (invPaise <= 0L) {
            highlightMissing = true
            errorMsg = "Please enter a valid invested amount or buy price > ₹0."
            scope.launch {
                runCatching { amountFocusRequester.requestFocus() }
                keyboardController?.show()
            }
            return
        }
        val pricePerUnit = parsedBuyUnitPaise ?: (invPaise / parsedUnits).toLong()
        val computedCurVal = livePreview?.effectiveCurrentValuePaise ?: parsedCurTotalPaise
        val hasCurrentVal = computedCurVal != null && computedCurVal > 0L
        val finalNotes = if (customCategoryLabel.isNotBlank()) customCategoryLabel else notes
        val purchaseDateEpoch = (IndianFinancialYear.todayEpochDay() - parsedHoldingDays).coerceAtLeast(1L)

        onSave(
            name,
            chosenCategory,
            purchaseDateEpoch,
            parsedUnits,
            parsedUnits,
            pricePerUnit,
            invPaise,
            parsedChargesPaise,
            computedCurVal,
            hasCurrentVal,
            selectedAccountId,
            parsedRate ?: livePreview?.annualizedGrowthRatePercent,
            compounding ?: CompoundingFrequency.QUARTERLY,
            parsedDurMonths,
            finalNotes,
            parsedDivPaise,
            parsedIntPaise
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VaultCardSurface,
        title = { Text("Add Investment & Calculate Growth", color = VaultTextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .dismissKeyboardOnSwipeDown()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VaultChooseSelector(
                    label = "Investment Category",
                    selectedItem = category,
                    options = InvestmentCategory.entries,
                    onSelect = {
                        category = it
                        errorMsg = null
                    },
                    itemLabel = { it.displayName },
                    itemIcon = { investmentCategoryIcon(it) },
                    placeholder = "Choose",
                    testTag = "investment_category_choose_selector",
                    autoExpandTrigger = catExpandTrigger,
                    isHighlightedUnselected = highlightMissing && category == null,
                    customOptions = customCategories,
                    customSelectedLabel = customCategoryLabel.ifBlank { null },
                    onSelectCustomOption = { customCategoryLabel = it },
                    onAddCustomOption = {
                        onAddCustomCategory(it)
                        customCategoryLabel = it
                    }
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    isError = highlightMissing && name.isBlank(),
                    label = { Text("Investment / Share Name (Required)") },
                    placeholder = { Text("e.g. Reliance Shares, Nifty 50 SIP, SBI FD") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitInvestment() }),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                        .testTag("investment_name_input")
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unitsInput,
                        onValueChange = {
                            unitsInput = it
                            val u = it.toDoubleOrNull()
                            val bp = InrMoney.parseInrToPaise(buyUnitPriceInput)
                            if (u != null && u > 0.0 && bp != null && bp > 0L) {
                                investedInput = InrMoney.paiseToPlainRupeeInput((u * bp).toLong())
                            }
                            val cp = InrMoney.parseInrToPaise(currentUnitPriceInput)
                            if (u != null && u > 0.0 && cp != null && cp > 0L) {
                                currentValInput = InrMoney.paiseToPlainRupeeInput((u * cp).toLong())
                            }
                        },
                        label = { Text("Shares / Units / Qty") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = buyUnitPriceInput,
                        onValueChange = {
                            buyUnitPriceInput = InrMoney.sanitizeIndianNumericInput(it)
                            val bp = InrMoney.parseInrToPaise(buyUnitPriceInput)
                            if (bp != null && bp > 0L && parsedUnits > 0.0) {
                                investedInput = InrMoney.paiseToPlainRupeeInput((bp * parsedUnits).toLong())
                            }
                        },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Buy Price / Share (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = investedInput,
                    onValueChange = {
                        investedInput = InrMoney.sanitizeIndianNumericInput(it)
                        errorMsg = null
                    },
                    isError = highlightMissing && parsedInvestedPaise <= 0L,
                    visualTransformation = IndianCurrencyVisualTransformation,
                    label = { Text("Total Invested Amount in INR (₹)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitInvestment() }),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(amountFocusRequester)
                        .testTag("investment_amount_input")
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentUnitPriceInput,
                        onValueChange = {
                            currentUnitPriceInput = InrMoney.sanitizeIndianNumericInput(it)
                            val cp = InrMoney.parseInrToPaise(currentUnitPriceInput)
                            if (cp != null && cp > 0L && parsedUnits > 0.0) {
                                currentValInput = InrMoney.paiseToPlainRupeeInput((cp * parsedUnits).toLong())
                            }
                        },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Current Share Price (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = currentValInput,
                        onValueChange = { currentValInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Current Total Value (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitInvestment() }),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rateInput,
                        onValueChange = { rateInput = it },
                        label = { Text("Expected/Interest (% p.a.)") },
                        placeholder = { Text("Auto-calc if blank") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = holdingDaysInput,
                        onValueChange = { holdingDaysInput = it },
                        label = { Text("Days Held So Far") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = durationMonthsInput,
                        onValueChange = { durationMonthsInput = it },
                        label = { Text("Target Tenure (Months)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = chargesInput,
                        onValueChange = { chargesInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Brokerage / Charges (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                VaultChooseSelector(
                    label = "Compounding Frequency",
                    selectedItem = compounding,
                    options = CompoundingFrequency.entries,
                    onSelect = { compounding = it },
                    itemLabel = { it.displayName },
                    itemIcon = { Icons.Default.Autorenew },
                    placeholder = "Choose (Default: Quarterly)"
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dividendsInput,
                        onValueChange = { dividendsInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Dividends Received (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = interestInput,
                        onValueChange = { interestInput = InrMoney.sanitizeIndianNumericInput(it) },
                        visualTransformation = IndianCurrencyVisualTransformation,
                        label = { Text("Interest Earned (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                val fundingOptions = remember(bankAndCashAccounts) {
                    listOf<Long?>(null) + bankAndCashAccounts.map { it.id }
                }
                VaultChooseSelector(
                    label = "Funding Source",
                    selectedItem = selectedAccountId,
                    options = fundingOptions,
                    onSelect = { selectedAccountId = it },
                    itemLabel = { accId ->
                        if (accId == null) "Existing Holding (Opening Capital)"
                        else "Paid from ${bankAndCashAccounts.firstOrNull { it.id == accId }?.name ?: "Account"}"
                    },
                    itemIcon = { accId ->
                        val acc = bankAndCashAccounts.firstOrNull { it.id == accId }
                        if (acc != null) accountTypeIcon(acc.accountType) else Icons.Default.AccountBalanceWallet
                    },
                    placeholder = "Choose"
                )

                if (livePreview != null) {
                    VaultGlassCard(accentColor = VaultEmerald) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "CALCULATED CURRENT VALUE & GROWING RATE",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultEmerald
                            )
                            Text(
                                text = "Calculated Current Value: ${InrMoney.formatPaise(livePreview.effectiveCurrentValuePaise)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultCyan
                            )
                            Text(
                                text = "Current Share/Unit Value: ${InrMoney.formatPaise(livePreview.currentPricePerUnitPaise)} / unit",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultTextPrimary
                            )
                            Text(
                                text = "Growing Rate (CAGR): ${livePreview.annualizedGrowthRatePercent}% p.a. (${livePreview.monthlyGrowthRatePercent}% / mo)",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultEmerald
                            )
                            Text(
                                text = "Absolute Return: ${livePreview.absoluteReturnPercent}% • 1-Yr Projected: ${InrMoney.formatPaise(livePreview.projectedOneYearValuePaise)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultGold
                            )
                            if (livePreview.projectedMaturityValuePaise != null) {
                                Text(
                                    text = "Maturity Value: ${InrMoney.formatPaise(livePreview.projectedMaturityValuePaise)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultGold
                                )
                            }
                        }
                    }
                }

                if (errorMsg != null) {
                    Text(text = errorMsg!!, color = VaultCoral, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submitInvestment() },
                colors = ButtonDefaults.buttonColors(containerColor = VaultEmerald),
                modifier = Modifier.testTag("save_investment_button")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Holding", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = VaultTextSecondary)
            }
        }
    )
}
