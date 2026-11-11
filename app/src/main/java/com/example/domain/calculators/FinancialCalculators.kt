package com.example.domain.calculators

import com.example.domain.money.InrMoney
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pure, deterministic financial calculation engine for Finance Vault.
 * Handles EMI, Amortization Schedules, Fixed Deposits (FD), Recurring Deposits (RD),
 * Savings Rate, Absolute Return, CAGR, and XIRR (Newton-Raphson on cash flows).
 */
object FinancialCalculators {

    data class EmiBreakdownItem(
        val installmentNumber: Int,
        val openingPrincipalPaise: Long,
        val emiPaise: Long,
        val interestComponentPaise: Long,
        val principalComponentPaise: Long,
        val closingPrincipalPaise: Long
    )

    data class LoanSummaryResult(
        val monthlyEmiPaise: Long,
        val totalPaymentPaise: Long,
        val totalInterestPayablePaise: Long,
        val schedule: List<EmiBreakdownItem>
    )

    /**
     * Calculates standard monthly reducing-balance loan EMI and full amortization schedule.
     * Formula: EMI = [P * r * (1+r)^n] / [(1+r)^n - 1]
     * Handles zero-interest loans cleanly.
     *
     * @param principalPaise Principal P in paise
     * @param annualInterestRatePercent Annual interest rate (e.g., 10.5 for 10.5% p.a.)
     * @param tenureMonths Number of monthly installments n
     */
    fun calculateLoanAmortization(
        principalPaise: Long,
        annualInterestRatePercent: Double,
        tenureMonths: Int
    ): LoanSummaryResult {
        if (principalPaise <= 0L || tenureMonths <= 0) {
            return LoanSummaryResult(0L, 0L, 0L, emptyList())
        }

        if (annualInterestRatePercent <= 0.0) {
            val baseEmi = principalPaise / tenureMonths
            var rem = principalPaise
            val schedule = mutableListOf<EmiBreakdownItem>()
            for (i in 1..tenureMonths) {
                val principalPart = if (i == tenureMonths) rem else baseEmi
                val closing = rem - principalPart
                schedule.add(
                    EmiBreakdownItem(
                        installmentNumber = i,
                        openingPrincipalPaise = rem,
                        emiPaise = principalPart,
                        interestComponentPaise = 0L,
                        principalComponentPaise = principalPart,
                        closingPrincipalPaise = closing
                    )
                )
                rem = closing
            }
            return LoanSummaryResult(
                monthlyEmiPaise = baseEmi,
                totalPaymentPaise = principalPaise,
                totalInterestPayablePaise = 0L,
                schedule = schedule
            )
        }

        val monthlyRate = annualInterestRatePercent / (12.0 * 100.0)
        val onePlusRPowerN = (1.0 + monthlyRate).pow(tenureMonths.toDouble())
        val emiDouble = (principalPaise.toDouble() * monthlyRate * onePlusRPowerN) / (onePlusRPowerN - 1.0)
        val emiPaise = BigDecimal.valueOf(emiDouble).setScale(0, RoundingMode.HALF_UP).toLong()

        var remainingPrincipal = principalPaise
        var totalInterest = 0L
        var totalPayment = 0L
        val schedule = mutableListOf<EmiBreakdownItem>()

        for (month in 1..tenureMonths) {
            val interestForMonth = BigDecimal.valueOf(remainingPrincipal)
                .multiply(BigDecimal.valueOf(monthlyRate))
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()

            val principalForMonth: Long
            val actualEmi: Long
            if (month == tenureMonths || remainingPrincipal + interestForMonth <= emiPaise) {
                principalForMonth = remainingPrincipal
                actualEmi = principalForMonth + interestForMonth
            } else {
                principalForMonth = (emiPaise - interestForMonth).coerceAtLeast(0L)
                actualEmi = emiPaise
            }

            val closing = (remainingPrincipal - principalForMonth).coerceAtLeast(0L)
            totalInterest += interestForMonth
            totalPayment += actualEmi

            schedule.add(
                EmiBreakdownItem(
                    installmentNumber = month,
                    openingPrincipalPaise = remainingPrincipal,
                    emiPaise = actualEmi,
                    interestComponentPaise = interestForMonth,
                    principalComponentPaise = principalForMonth,
                    closingPrincipalPaise = closing
                )
            )
            remainingPrincipal = closing
            if (remainingPrincipal == 0L) break
        }

        return LoanSummaryResult(
            monthlyEmiPaise = emiPaise,
            totalPaymentPaise = totalPayment,
            totalInterestPayablePaise = totalInterest,
            schedule = schedule
        )
    }

    /**
     * Calculates Fixed Deposit (FD) projected maturity value based on explicit user-entered compounding frequency.
     * @param compoundingPerYear 0 for Simple Interest, 12 for Monthly, 4 for Quarterly, 2 for Half-Yearly, 1 for Annually
     */
    fun calculateFixedDepositMaturity(
        principalPaise: Long,
        annualRatePercent: Double,
        durationMonths: Int,
        compoundingPerYear: Int
    ): Pair<Long, Long> {
        if (principalPaise <= 0L || durationMonths <= 0 || annualRatePercent <= 0.0) {
            return principalPaise to 0L
        }
        val years = durationMonths / 12.0
        val maturityDouble = if (compoundingPerYear <= 0) {
            // Simple interest
            principalPaise * (1.0 + (annualRatePercent / 100.0) * years)
        } else {
            val r = (annualRatePercent / 100.0) / compoundingPerYear
            val n = compoundingPerYear * years
            principalPaise * (1.0 + r).pow(n)
        }
        val maturityPaise = BigDecimal.valueOf(maturityDouble).setScale(0, RoundingMode.HALF_UP).toLong()
        val projectedInterest = (maturityPaise - principalPaise).coerceAtLeast(0L)
        return maturityPaise to projectedInterest
    }

    /**
     * Calculates Recurring Deposit (RD) projected maturity value for monthly installments.
     */
    fun calculateRecurringDepositMaturity(
        monthlyInstallmentPaise: Long,
        annualRatePercent: Double,
        tenureMonths: Int,
        compoundingPerYear: Int = 4
    ): Pair<Long, Long> {
        if (monthlyInstallmentPaise <= 0L || tenureMonths <= 0) {
            return 0L to 0L
        }
        val totalPrincipal = monthlyInstallmentPaise * tenureMonths
        if (annualRatePercent <= 0.0) {
            return totalPrincipal to 0L
        }
        val freq = if (compoundingPerYear <= 0) 4 else compoundingPerYear
        val ratePerPeriod = (annualRatePercent / 100.0) / freq
        var maturityDouble = 0.0
        for (m in 1..tenureMonths) {
            val monthsRemaining = (tenureMonths - m + 1).toDouble()
            val periods = (monthsRemaining / 12.0) * freq
            maturityDouble += monthlyInstallmentPaise * (1.0 + ratePerPeriod).pow(periods)
        }
        val maturityPaise = BigDecimal.valueOf(maturityDouble).setScale(0, RoundingMode.HALF_UP).toLong()
        val interestPaise = (maturityPaise - totalPrincipal).coerceAtLeast(0L)
        return maturityPaise to interestPaise
    }

    /**
     * Savings Rate = ((Income - Expenses) / Income) * 100
     * Handles zero income safely without division errors.
     */
    fun calculateSavingsRate(incomePaise: Long, expensesPaise: Long): Double {
        if (incomePaise <= 0L) return 0.0
        val savings = incomePaise - expensesPaise
        return InrMoney.percentageRatio(savings, incomePaise)
    }

    /**
     * Absolute Return % = ((Current Value - Invested Capital) / Invested Capital) * 100
     */
    fun calculateAbsoluteReturnPercent(investedPaise: Long, currentValuePaise: Long): Double {
        if (investedPaise <= 0L) return 0.0
        val gain = currentValuePaise - investedPaise
        return InrMoney.percentageRatio(gain, investedPaise)
    }

    /**
     * CAGR % = ((Current Value / Invested Capital)^(1 / years) - 1) * 100
     * Returns null when holding period is < 30 days or values are non-positive.
     */
    fun calculateCagrPercent(
        investedPaise: Long,
        currentValuePaise: Long,
        holdingDays: Long
    ): Double? {
        if (investedPaise <= 0L || currentValuePaise <= 0L || holdingDays < 30L) return null
        val years = holdingDays / 365.25
        val ratio = currentValuePaise.toDouble() / investedPaise.toDouble()
        val cagr = (ratio.pow(1.0 / years) - 1.0) * 100.0
        if (cagr.isNaN() || cagr.isInfinite()) return null
        return BigDecimal.valueOf(cagr).setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    data class CashFlowPoint(
        val epochDay: Long,
        val amountPaise: Long // negative for outflows (investments), positive for inflows (redemptions/current value)
    )

    /**
     * XIRR calculation for irregular cash flows using Newton-Raphson iteration.
     * Returns annualized rate in percentage (e.g., 14.25 for 14.25%), or null if not applicable.
     */
    fun calculateXirrPercent(cashFlows: List<CashFlowPoint>): Double? {
        if (cashFlows.size < 2) return null
        val hasPositive = cashFlows.any { it.amountPaise > 0 }
        val hasNegative = cashFlows.any { it.amountPaise < 0 }
        if (!hasPositive || !hasNegative) return null

        val sorted = cashFlows.sortedBy { it.epochDay }
        val baseDay = sorted.first().epochDay
        var rate = 0.10 // Initial guess 10%

        for (iter in 0 until 100) {
            var fValue = 0.0
            var fDerivative = 0.0
            for (cf in sorted) {
                val years = (cf.epochDay - baseDay) / 365.0
                val base = 1.0 + rate
                if (base <= 0.0001) return null
                val discount = base.pow(years)
                val amt = cf.amountPaise / 100.0
                fValue += amt / discount
                if (years != 0.0) {
                    fDerivative -= (years * amt) / (discount * base)
                }
            }
            if (abs(fValue) < 1e-4) {
                return BigDecimal.valueOf(rate * 100.0).setScale(2, RoundingMode.HALF_UP).toDouble()
            }
            if (abs(fDerivative) < 1e-10) break
            val nextRate = rate - fValue / fDerivative
            if (abs(nextRate - rate) < 1e-6) {
                return BigDecimal.valueOf(nextRate * 100.0).setScale(2, RoundingMode.HALF_UP).toDouble()
            }
            rate = nextRate.coerceIn(-0.95, 10.0)
        }
        return null
    }

    data class InvestmentGrowthAnalysis(
        val units: Double,
        val purchasePricePerUnitPaise: Long,
        val currentPricePerUnitPaise: Long,
        val investedPaise: Long,
        val effectiveCurrentValuePaise: Long,
        val unrealizedGainPaise: Long,
        val totalNetGainPaise: Long,
        val holdingDays: Long,
        val absoluteReturnPercent: Double,
        val annualizedGrowthRatePercent: Double,
        val monthlyGrowthRatePercent: Double,
        val projectedOneYearValuePaise: Long,
        val projectedMaturityValuePaise: Long?,
        val projectedMaturityGainPaise: Long?
    )

    /**
     * Comprehensive investment valuation & growing-rate calculator.
     * Calculates current value of shares/units/holdings and their growing rate from:
     * 1. Share/Unit quantity & current unit price (if provided), OR
     * 2. Explicit current recorded value (if provided), OR
     * 3. Compound/simple growth from annual growth/interest rate over elapsed holding days (or tenure).
     */
    fun analyzeInvestmentGrowth(
        units: Double,
        purchasePricePerUnitPaise: Long,
        investedAmountPaise: Long,
        currentRecordedValuePaise: Long?,
        isCurrentValueRecorded: Boolean,
        currentUnitPricePaise: Long? = null,
        annualGrowthOrInterestRatePercent: Double? = null,
        compoundingPeriodsPerYear: Int = 4,
        durationMonths: Int? = null,
        holdingDays: Long = 365L,
        chargesPaise: Long = 0L,
        dividendsAndInterestPaise: Long = 0L,
        isRecurringMonthly: Boolean = false
    ): InvestmentGrowthAnalysis {
        val safeUnits = if (units > 0.0) units else 1.0
        val effectiveInvestedPaise = when {
            investedAmountPaise > 0L -> investedAmountPaise
            purchasePricePerUnitPaise > 0L -> BigDecimal.valueOf(safeUnits * purchasePricePerUnitPaise)
                .setScale(0, RoundingMode.HALF_UP).toLong()
            else -> 0L
        }
        val effectiveBuyPricePerUnit = when {
            purchasePricePerUnitPaise > 0L -> purchasePricePerUnitPaise
            effectiveInvestedPaise > 0L -> BigDecimal.valueOf(effectiveInvestedPaise.toDouble() / safeUnits)
                .setScale(0, RoundingMode.HALF_UP).toLong()
            else -> 0L
        }

        val safeHoldingDays = holdingDays.coerceAtLeast(1L)
        val elapsedYears = (safeHoldingDays / 365.25).coerceAtLeast(1.0 / 365.25)

        // Determine current value from: (1) current share/unit price, (2) recorded current value, (3) growth rate compounding
        val computedFromRatePaise: Long? = if (
            effectiveInvestedPaise > 0L &&
            annualGrowthOrInterestRatePercent != null &&
            annualGrowthOrInterestRatePercent != 0.0
        ) {
            val rateDec = annualGrowthOrInterestRatePercent / 100.0
            val rawVal = if (compoundingPeriodsPerYear <= 0) {
                effectiveInvestedPaise * (1.0 + rateDec * elapsedYears)
            } else {
                val n = compoundingPeriodsPerYear.toDouble()
                effectiveInvestedPaise * (1.0 + rateDec / n).pow(n * elapsedYears)
            }
            BigDecimal.valueOf(rawVal.coerceAtLeast(0.0)).setScale(0, RoundingMode.HALF_UP).toLong()
        } else null

        val effectiveCurrentValuePaise = when {
            currentUnitPricePaise != null && currentUnitPricePaise > 0L ->
                BigDecimal.valueOf(safeUnits * currentUnitPricePaise).setScale(0, RoundingMode.HALF_UP).toLong()
            isCurrentValueRecorded && currentRecordedValuePaise != null && currentRecordedValuePaise > 0L ->
                currentRecordedValuePaise
            computedFromRatePaise != null && computedFromRatePaise > 0L ->
                computedFromRatePaise
            else -> effectiveInvestedPaise
        }

        val effectiveCurrentUnitPrice = if (effectiveCurrentValuePaise > 0L) {
            BigDecimal.valueOf(effectiveCurrentValuePaise.toDouble() / safeUnits)
                .setScale(0, RoundingMode.HALF_UP).toLong()
        } else 0L

        val unrealizedGain = effectiveCurrentValuePaise - effectiveInvestedPaise
        val totalNetGain = unrealizedGain + dividendsAndInterestPaise - chargesPaise
        val absReturnPct = calculateAbsoluteReturnPercent(effectiveInvestedPaise, effectiveCurrentValuePaise)

        // Calculate annualized growing rate (CAGR % p.a.)
        val annualizedGrowthPct: Double = when {
            effectiveInvestedPaise > 0L && effectiveCurrentValuePaise > 0L && effectiveCurrentValuePaise != effectiveInvestedPaise -> {
                val effectiveDaysForCagr = safeHoldingDays.coerceAtLeast(30L)
                val yrs = effectiveDaysForCagr / 365.25
                val ratio = effectiveCurrentValuePaise.toDouble() / effectiveInvestedPaise.toDouble()
                val rawCagr = ((ratio.pow(1.0 / yrs) - 1.0) * 100.0).coerceIn(-99.0, 999.99)
                if (rawCagr.isNaN() || rawCagr.isInfinite()) (annualGrowthOrInterestRatePercent ?: 0.0)
                else BigDecimal.valueOf(rawCagr).setScale(2, RoundingMode.HALF_UP).toDouble()
            }
            annualGrowthOrInterestRatePercent != null ->
                BigDecimal.valueOf(annualGrowthOrInterestRatePercent).setScale(2, RoundingMode.HALF_UP).toDouble()
            else -> 0.0
        }

        val monthlyGrowthPct = if (annualizedGrowthPct != 0.0) {
            val mRate = ((1.0 + (annualizedGrowthPct / 100.0).coerceAtLeast(-0.95)).pow(1.0 / 12.0) - 1.0) * 100.0
            BigDecimal.valueOf(mRate).setScale(2, RoundingMode.HALF_UP).toDouble()
        } else 0.0

        val effectiveRateForProjection = annualGrowthOrInterestRatePercent ?: annualizedGrowthPct
        val projectedOneYearPaise = if (effectiveCurrentValuePaise > 0L && effectiveRateForProjection != 0.0) {
            val r = effectiveRateForProjection / 100.0
            val projected = if (compoundingPeriodsPerYear <= 0) {
                effectiveCurrentValuePaise * (1.0 + r)
            } else {
                val n = compoundingPeriodsPerYear.toDouble()
                effectiveCurrentValuePaise * (1.0 + r / n).pow(n)
            }
            BigDecimal.valueOf(projected.coerceAtLeast(0.0)).setScale(0, RoundingMode.HALF_UP).toLong()
        } else effectiveCurrentValuePaise

        val maturityPair: Pair<Long, Long>? = if (
            durationMonths != null && durationMonths > 0 && effectiveInvestedPaise > 0L && effectiveRateForProjection > 0.0
        ) {
            if (isRecurringMonthly) {
                val monthlyInst = (effectiveInvestedPaise / durationMonths.coerceAtLeast(1)).coerceAtLeast(1L)
                calculateRecurringDepositMaturity(
                    monthlyInstallmentPaise = monthlyInst,
                    annualRatePercent = effectiveRateForProjection,
                    tenureMonths = durationMonths,
                    compoundingPerYear = compoundingPeriodsPerYear
                )
            } else {
                calculateFixedDepositMaturity(
                    principalPaise = effectiveInvestedPaise,
                    annualRatePercent = effectiveRateForProjection,
                    durationMonths = durationMonths,
                    compoundingPerYear = compoundingPeriodsPerYear
                )
            }
        } else null

        return InvestmentGrowthAnalysis(
            units = safeUnits,
            purchasePricePerUnitPaise = effectiveBuyPricePerUnit,
            currentPricePerUnitPaise = effectiveCurrentUnitPrice,
            investedPaise = effectiveInvestedPaise,
            effectiveCurrentValuePaise = effectiveCurrentValuePaise,
            unrealizedGainPaise = unrealizedGain,
            totalNetGainPaise = totalNetGain,
            holdingDays = safeHoldingDays,
            absoluteReturnPercent = absReturnPct,
            annualizedGrowthRatePercent = annualizedGrowthPct,
            monthlyGrowthRatePercent = monthlyGrowthPct,
            projectedOneYearValuePaise = projectedOneYearPaise,
            projectedMaturityValuePaise = maturityPair?.first,
            projectedMaturityGainPaise = maturityPair?.second
        )
    }
}

