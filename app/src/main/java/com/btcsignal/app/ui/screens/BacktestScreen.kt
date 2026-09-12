package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.backtest.BacktestPhase
import com.btcsignal.app.backtest.BacktestProgress
import com.btcsignal.app.backtest.BacktestState
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicFilterChip
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.MetricCard
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabBacktestColor
import com.btcsignal.app.ui.theme.TextSecondary

private val PERIODS = listOf(1, 3, 7, 14, 30, 90, 180, 365)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BacktestScreen() {
    val context = LocalContext.current
    // Backed by BacktestState (a singleton outside the composition) instead of
    // composable-local `remember`, so switching bottom-nav tabs and coming back
    // doesn't lose progress or cancel an in-flight run -- see BacktestState.kt.
    val selectedPeriod by BacktestState.selectedPeriod.collectAsState()
    val isRunning by BacktestState.isRunning.collectAsState()
    val result by BacktestState.result.collectAsState()
    val error by BacktestState.error.collectAsState()
    val progress by BacktestState.progress.collectAsState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GothicPageHeader(
            title = "Backtest Engine",
            subtitle = "Uses the same Core Signal Engine as the Live screen, replayed chronologically over historical Binance data.",
            accent = TabBacktestColor
        )

        Text("Period", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // Was a plain Row -- with 8 chips (1d..365d) it overflowed the screen width and
        // the last ones (180d/365d) were simply clipped off-screen with no way to reach
        // them. FlowRow wraps chips onto a second line instead of running off-screen.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PERIODS.forEach { days ->
                GothicFilterChip(
                    selected = selectedPeriod == days,
                    onClick = { BacktestState.selectedPeriod.value = days },
                    enabled = !isRunning,
                    label = "${days}d",
                    role = TabBacktestColor
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        GothicButton(
            text = if (isRunning) "Running\u2026" else "Run Backtest",
            onClick = { BacktestState.start(context) },
            enabled = !isRunning,
            role = GothicRole.CYAN
        )

        if (isRunning) {
            Spacer(Modifier.height(14.dp))
            BacktestProgressCard(progress)
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text("Error: $it \u2014 check network access to api.binance.com", color = RedSignal)
        }

        result?.let { r ->
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Total Signals", "${r.totalSignals}", Modifier.weight(1f))
                MetricCard(
                    "Win Rate", "${"%.1f".format(r.winRatePct)}%", Modifier.weight(1f),
                    valueColor = if (r.winRatePct >= 50) GreenSignal else RedSignal
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    "Total PnL", "$${"%,.2f".format(r.totalPnlUsd)}", Modifier.weight(1f),
                    valueColor = if (r.totalPnlUsd >= 0) GreenSignal else RedSignal
                )
                MetricCard("Final Balance", "$${"%,.2f".format(r.finalBalanceUsd)}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Wins", "${r.wins}", Modifier.weight(1f), valueColor = GreenSignal)
                MetricCard("Losses", "${r.losses}", Modifier.weight(1f), valueColor = RedSignal)
            }
            Spacer(Modifier.height(16.dp))
            // r.reversalZoneSignals/Wins/Losses/PnlUsd were already computed by
            // BacktestEngine (minute-3-to-4 Reversal-Zone re-entries, Checkpoint C, kept
            // as their own tally rather than folded into r.totalSignals/wins/losses
            // above) but this screen never surfaced them -- shown here, counted only
            // against themselves: total fired, correct (Won) vs incorrect (Lost).
            SectionCard("Reversal-Zone Re-entries (Minute 3\u20134)") {
                if (r.reversalZoneSignals == 0) {
                    Text("No Reversal-Zone secondary entries fired in this period.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val reversalWinRate = r.reversalZoneWins.toDouble() / r.reversalZoneSignals * 100.0
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard("Total", "${r.reversalZoneSignals}", Modifier.weight(1f))
                        MetricCard(
                            "Win Rate", "${"%.1f".format(reversalWinRate)}%", Modifier.weight(1f),
                            valueColor = if (reversalWinRate >= 50) GreenSignal else RedSignal
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard("Correct (Won)", "${r.reversalZoneWins}", Modifier.weight(1f), valueColor = GreenSignal)
                        MetricCard("Incorrect (Lost)", "${r.reversalZoneLosses}", Modifier.weight(1f), valueColor = RedSignal)
                    }
                    Spacer(Modifier.height(10.dp))
                    MetricCard(
                        "Reversal-Zone PnL", "$${"%,.2f".format(r.reversalZonePnlUsd)}", Modifier.fillMaxWidth(),
                        valueColor = if (r.reversalZonePnlUsd >= 0) GreenSignal else RedSignal
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            // Reversal-Zone re-entries broken down by the PRIMARY strategy that armed
            // each scan (Signal.originStrategyId — see BacktestEngine.reversalZoneStrategyUsage/
            // reversalZoneStrategyPnl), ranked BEST -> WORST by PnL (user request), each row
            // also showing its share of the total Reversal-Zone PnL across all origin
            // strategies so it's clear how much of the minute-3-to-4 result any one
            // strategy is responsible for relative to the rest.
            SectionCard("Reversal-Zone Strategy Usage (Minute 3\u20134)") {
                // Only positive-PnL (green) origin strategies are listed here, per user
                // request -- strategies whose Reversal-Zone re-entries lost money overall
                // are left out of this ranking entirely rather than shown in red.
                val profitable = r.reversalZoneStrategyPnl.filterValues { it > 0.0 }
                if (profitable.isEmpty()) {
                    Text("No profitable Reversal-Zone origin strategies in this period.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val totalReversalStrategyPnl = profitable.values.sum()
                    val ranked = profitable.entries.sortedByDescending { it.value }
                    ranked.forEachIndexed { index, (originId, pnl) ->
                        val count = r.reversalZoneStrategyUsage[originId] ?: 0
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

            Spacer(Modifier.height(16.dp))
            SectionCard("Strategy Usage") {
                r.strategyUsage.entries.sortedByDescending { it.value }.forEach { (id, count) ->
                    val pnl = r.strategyPnl[id] ?: 0.0
                    val pnlColor = if (pnl >= 0) GreenSignal else RedSignal
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$count signals \u2022 $${"%,.2f".format(pnl)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = pnlColor
                        )
                    }
                }
                if (r.strategyUsage.isEmpty()) {
                    Text("No strategies fired in this period.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            r.bestStrategyId?.let {
                Spacer(Modifier.height(10.dp))
                Text("Best strategy this period: $it", style = MaterialTheme.typography.titleMedium, color = GreenSignal)
            }
        }
    }
}

@Composable
private fun BacktestProgressCard(progress: BacktestProgress?) {
    val pct = progress?.percent ?: 0
    val phaseLabel = when (progress?.phase) {
        BacktestPhase.FETCHING -> "Downloading data"
        BacktestPhase.REPLAYING -> "Replaying signal engine"
        BacktestPhase.SAVING -> "Saving results"
        BacktestPhase.DONE -> "Done"
        null -> "Starting"
    }

    SectionCard(title = "") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(phaseLabel, style = MaterialTheme.typography.titleMedium)
            Text("$pct%", style = MaterialTheme.typography.titleLarge, color = GreenSignal)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = GreenSignal,
            trackColor = TextSecondary.copy(alpha = 0.2f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            progress?.message ?: "Starting\u2026",
            style = MaterialTheme.typography.bodyMedium
        )
        if (progress?.phase == BacktestPhase.FETCHING) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Longer periods (30-365d) can take several minutes \u2014 Binance returns at most " +
                    "1000 candles per request, so this is sequential network pagination, not a freeze. " +
                    "You can switch tabs while it runs; the backtest keeps going in the background.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
