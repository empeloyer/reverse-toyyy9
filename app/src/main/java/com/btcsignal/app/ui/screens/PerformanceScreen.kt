package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.local.SignalEntity
import com.btcsignal.app.ui.components.GothicFilterChip
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.MetricCard
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.components.SimpleBarChart
import com.btcsignal.app.ui.components.SimpleLineChart
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabPerformanceColor
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val PERIODS = listOf(1, 3, 7, 14, 30, 90)

/** Same tag LiveMonitoringService/BacktestEngine give every Reversal-Zone (Checkpoint C,
 *  minute-3-to-4) secondary entry (see ReversalZoneScanner.kt). Used here to split those
 *  rows out of the primary Total/Win/Loss numbers below -- otherwise this screen would
 *  silently mix a different financial model (+$2/-$1) and a different entry rule into
 *  counts that have always meant "primary A/B signals only" (same reasoning BacktestEngine
 *  already documents for BacktestSummary.reversalZoneSignals/Wins/Losses). */
private const val REVERSAL_ZONE_STRATEGY_ID = "REVERSAL_ZONE"

@Composable
fun PerformanceScreen() {
    val context = LocalContext.current
    val repo = remember { AppContainer.signalRepository(context) }
    val database = remember { AppContainer.strategyDatabase(context) }
    val scope = rememberCoroutineScope()

    var selectedPeriod by remember { mutableStateOf(7) }
    var allRows by remember { mutableStateOf<List<SignalEntity>>(emptyList()) }

    LaunchedEffect(selectedPeriod) {
        val since = System.currentTimeMillis() - selectedPeriod * 24L * 60 * 60 * 1000
        allRows = repo.getLiveSince(since)
    }

    // Primary (Checkpoint A/B) rows only -- Reversal-Zone (Checkpoint C) rows are tallied
    // on their own further down, never folded into these.
    val rows = allRows.filter { it.activeStrategyId != REVERSAL_ZONE_STRATEGY_ID }
    val reversalRows = allRows.filter { it.activeStrategyId == REVERSAL_ZONE_STRATEGY_ID }

    val closed = rows.filter { it.status == "WON" || it.status == "LOST" }
    val wins = closed.count { it.status == "WON" }
    val losses = closed.count { it.status == "LOST" }
    val totalPnl = closed.sumOf { it.pnlUsd ?: 0.0 }
    val winRate = if (closed.isNotEmpty()) wins.toDouble() / closed.size * 100.0 else 0.0
    val balance = database.financialModel.startCapitalUsd + totalPnl
    val byStrategy = closed.groupBy { it.activeStrategyId }
        .mapValues { (_, v) -> v.sumOf { it.pnlUsd ?: 0.0 } }
    val best = byStrategy.maxByOrNull { it.value }
    val worst = byStrategy.minByOrNull { it.value }

    // Reversal-Zone (minute-3-to-4) secondary entries, counted only against themselves --
    // total fired, correct (WON) vs incorrect (LOST), and their own PnL under the +$2/-$1
    // model (see ReversalZoneScanner.kt / CoreSignalEngine.evaluateResult's Checkpoint.C
    // branch).
    val reversalClosed = reversalRows.filter { it.status == "WON" || it.status == "LOST" }
    val reversalWins = reversalClosed.count { it.status == "WON" }
    val reversalLosses = reversalClosed.count { it.status == "LOST" }
    val reversalPnl = reversalClosed.sumOf { it.pnlUsd ?: 0.0 }
    val reversalWinRate = if (reversalClosed.isNotEmpty()) reversalWins.toDouble() / reversalClosed.size * 100.0 else 0.0

    // Reversal-Zone re-entries broken down by the PRIMARY strategy that armed each scan
    // (SignalEntity.originStrategyId — see Signal.originStrategyId / §24). Rows saved
    // before this field existed have a null originStrategyId and are grouped under
    // "Unknown" rather than dropped, so old history doesn't just silently vanish from
    // this breakdown. Ranked BEST -> WORST by PnL (user request), each with its share of
    // the total Reversal-Zone PnL across all origin strategies.
    val reversalByOriginStrategy: Map<String, Double> = reversalClosed
        .groupBy { it.originStrategyId ?: "Unknown" }
        .mapValues { (_, v) -> v.sumOf { it.pnlUsd ?: 0.0 } }
    val reversalUsageByOriginStrategy: Map<String, Int> = reversalClosed
        .groupBy { it.originStrategyId ?: "Unknown" }
        .mapValues { (_, v) -> v.size }

    // Chronological equity curve from starting balance.
    val equityCurve = remember(closed) {
        var running = database.financialModel.startCapitalUsd
        closed.sortedBy { it.signalTimestampMillis }.map { running += (it.pnlUsd ?: 0.0); running }
    }
    val dailyPnl = remember(closed) {
        closed.groupBy { it.signalTimestampMillis / (24L * 60 * 60 * 1000) }
            .toSortedMap()
            .map { (_, v) -> v.sumOf { it.pnlUsd ?: 0.0 } }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GothicPageHeader(title = "Performance", accent = TabPerformanceColor)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PERIODS.forEach { days ->
                GothicFilterChip(
                    selected = selectedPeriod == days,
                    onClick = { selectedPeriod = days },
                    label = "${days}d",
                    role = TabPerformanceColor
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Total Signals", "${closed.size}", Modifier.weight(1f))
            MetricCard("Win Rate", "${"%.1f".format(winRate)}%", Modifier.weight(1f), valueColor = if (winRate >= 50) GreenSignal else RedSignal)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Total PnL", "$${"%,.2f".format(totalPnl)}", Modifier.weight(1f), valueColor = if (totalPnl >= 0) GreenSignal else RedSignal)
            MetricCard("Current Balance", "$${"%,.2f".format(balance)}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Wins", "$wins", Modifier.weight(1f), valueColor = GreenSignal)
            MetricCard("Losses", "$losses", Modifier.weight(1f), valueColor = RedSignal)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                "Avg PnL / Signal", if (closed.isNotEmpty()) "$${"%.2f".format(totalPnl / closed.size)}" else "\u2014",
                Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("Equity Curve") {
            if (equityCurve.size >= 2) SimpleLineChart(equityCurve) else Text("Not enough closed signals yet.")
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("Daily PnL") {
            if (dailyPnl.isNotEmpty()) SimpleBarChart(dailyPnl) else Text("Not enough closed signals yet.")
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("Best / Worst Strategy") {
            Text("Best: ${best?.key ?: "\u2014"}  (${best?.let { "$${"%.2f".format(it.value)}" } ?: ""})", color = GreenSignal)
            Text("Worst: ${worst?.key ?: "\u2014"}  (${worst?.let { "$${"%.2f".format(it.value)}" } ?: ""})", color = RedSignal)
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("Reversal-Zone Re-entries (Minute 3\u20134)") {
            if (reversalClosed.isEmpty()) {
                Text("No Reversal-Zone secondary entries have resolved in this period yet.", color = TextSecondary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("Total", "${reversalClosed.size}", Modifier.weight(1f))
                    MetricCard(
                        "Win Rate", "${"%.1f".format(reversalWinRate)}%", Modifier.weight(1f),
                        valueColor = if (reversalWinRate >= 50) GreenSignal else RedSignal
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("Correct (Won)", "$reversalWins", Modifier.weight(1f), valueColor = GreenSignal)
                    MetricCard("Incorrect (Lost)", "$reversalLosses", Modifier.weight(1f), valueColor = RedSignal)
                }
                Spacer(Modifier.height(10.dp))
                MetricCard(
                    "Reversal-Zone PnL", "$${"%,.2f".format(reversalPnl)}", Modifier.fillMaxWidth(),
                    valueColor = if (reversalPnl >= 0) GreenSignal else RedSignal
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("Reversal-Zone Strategy Usage (Minute 3\u20134)") {
            // Only positive-PnL (green) origin strategies are listed here, per user
            // request -- strategies whose Reversal-Zone re-entries lost money overall are
            // left out of this ranking entirely rather than shown in red.
            val profitable = reversalByOriginStrategy.filterValues { it > 0.0 }
            if (profitable.isEmpty()) {
                Text("No profitable Reversal-Zone origin strategies in this period yet.", color = TextSecondary)
            } else {
                val totalReversalStrategyPnl = profitable.values.sum()
                val ranked = profitable.entries.sortedByDescending { it.value }
                ranked.forEachIndexed { index, (originId, pnl) ->
                    val count = reversalUsageByOriginStrategy[originId] ?: 0
                    val sharePct = if (totalReversalStrategyPnl != 0.0) (pnl / totalReversalStrategyPnl) * 100.0 else 0.0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${index + 1}  $originId", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$count re-entries \u2022 $${"%,.2f".format(pnl)} \u2022 ${"%.1f".format(sharePct)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GreenSignal
                        )
                    }
                }
            }
        }
    }
}
