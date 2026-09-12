package com.btcsignal.app.backtest

import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private const val FIVE_MIN_MILLIS = 5 * 60_000L

data class BacktestSummary(
    val periodDays: Int,
    val totalSignals: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val totalPnlUsd: Double,
    val finalBalanceUsd: Double,
    val strategyUsage: Map<String, Int>,
    val strategyPnl: Map<String, Double>,
    val bestStrategyId: String?,
    // Reversal-Zone secondary entries (Checkpoint C, minute-4 scan) are kept as their own
    // tally rather than folded into the primary totals above, the same way the app's
    // existing nexa/magic dual-engine reporting keeps each stream separate -- these are a
    // different financial model (+$2/-$1) and are approximated from OHLC-only historical
    // data (see ReversalZoneScanner.kt / syntheticIntraMinutePath below), so mixing them
    // into `totalSignals`/`wins`/`losses`/`totalPnlUsd` would silently change what those
    // numbers have always meant. The rows are still saved to signalRepository (tagged
    // Checkpoint.C, activeStrategyId "REVERSAL_ZONE") alongside the primary ones, so
    // History/Performance can query them directly.
    val reversalZoneSignals: Int = 0,
    val reversalZoneWins: Int = 0,
    val reversalZoneLosses: Int = 0,
    val reversalZonePnlUsd: Double = 0.0,
    // "Reversal-Zone Strategy Usage" (§24, user request): every Reversal-Zone (Checkpoint
    // C) fire is tagged activeStrategyId "REVERSAL_ZONE" itself (see above), but each one
    // is armed by a specific PRIMARY strategy (Signal.originStrategyId). These two maps
    // are keyed by that ORIGIN strategy id, not "REVERSAL_ZONE" -- i.e. "how many/how much
    // PnL did the Reversal-Zone re-entries that started FROM strategy X produce", the same
    // shape as strategyUsage/strategyPnl above but scoped to the minute-3-to-4 signal only.
    val reversalZoneStrategyUsage: Map<String, Int> = emptyMap(),
    val reversalZoneStrategyPnl: Map<String, Double> = emptyMap()
)

enum class BacktestPhase { FETCHING, REPLAYING, SAVING, DONE }

/** One resolved signal's outcome, kept chronologically per strategy during a replay so
 *  statsFor() can derive both a rolling PnL average and a rolling win/loss z-score -
 *  the same two things SignalRepository.recentWindowStatsFor derives from Room live. */
private data class RollingOutcome(val pnl: Double, val won: Boolean)

/** Reported to the UI so a long backtest (30-90 days) shows real progress instead of an
 *  indefinite spinner the person can't tell apart from a freeze. */
data class BacktestProgress(
    val phase: BacktestPhase,
    val percent: Int,      // 0-100, overall progress across the whole run() call
    val message: String
)

/**
 * Runs the SAME CoreSignalEngine as LiveMonitoringService against historical Binance
 * data (spec sections 35-39). This is not a second implementation of any strategy
 * logic — it feeds historical 1-minute klines through the identical
 * MarketDataStore -> CandleAggregator -> CoreSignalEngine pipeline used live, chronolog-
 * ically, one candle at a time, so the engine can never see data timestamped after the
 * moment it's evaluating (spec section 36).
 */
class BacktestEngine(
    private val restClient: BinanceRestClient = BinanceRestClient(),
    private val signalRepository: SignalRepository? = null
) {

    /** [warmupDays] gives the indicator engine enough closed-candle history before
     *  [periodDays] actually starts scoring, mirroring LiveMonitoringService.warmUp().
     *  [onProgress] is called from a background thread with 0-100% progress; fetching
     *  historical data is weighted 0-50%, chronological replay is weighted 50-100%. */
    suspend fun run(
        database: StrategyDatabase,
        periodDays: Int,
        warmupDays: Int = 8,
        persist: Boolean = true,
        blockedStrategyIds: Set<String> = emptySet(),
        // User-configurable Rev Green / Rev Red minute-3-to-4 zone edges (Settings screen)
        // -- defaults to the shipped -0.03%/-0.10% (green) and +0.03%/+0.10% (red) ranges
        // when the caller doesn't override them.
        reversalZoneConfig: ReversalZoneConfig = ReversalZoneConfig(),
        onProgress: (BacktestProgress) -> Unit = {}
    ): BacktestSummary = withContext(Dispatchers.Default) {
        val rawNow = System.currentTimeMillis()
        // Round down to the start of the 5-minute bucket that is CURRENTLY forming as of
        // `rawNow`, then step back 1ms so the fetch/replay window covers only fully closed
        // 5-minute candles. BinanceRestClient.getKlines independently drops any candle that
        // still hasn't actually closed (e.g. if the exchange hasn't published it yet by the
        // time this call reaches it) as a second, defense-in-depth guard against the same
        // failure mode.
        val end = (rawNow / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS - 1
        val periodStart = end - periodDays * 24L * 60 * 60 * 1000
        val fetchStart = periodStart - warmupDays * 24L * 60 * 60 * 1000
        val totalRangeMinutes = ((end - fetchStart) / 60_000L).toInt().coerceAtLeast(1)

        onProgress(BacktestProgress(BacktestPhase.FETCHING, 0, "Fetching historical data from Binance\u2026"))
        val candles = restClient.getKlines("BTCUSDT", "1m", fetchStart, end) { fetchedSoFar ->
            val pct = ((fetchedSoFar.toFloat() / totalRangeMinutes) * 50f).toInt().coerceIn(0, 50)
            onProgress(
                BacktestProgress(
                    BacktestPhase.FETCHING, pct,
                    "Fetching historical data\u2026 ($fetchedSoFar / ~$totalRangeMinutes candles)"
                )
            )
        }
        onProgress(BacktestProgress(BacktestPhase.REPLAYING, 50, "Replaying ${candles.size} candles through the signal engine\u2026"))

        val store = MarketDataStore()
        val aggregator = CandleAggregator()
        val lockedCandles = HashSet<String>()
        val pendingSignals = HashMap<String, Signal>() // candleId -> active signal awaiting result
        val strategyRollingStats = HashMap<String, MutableList<RollingOutcome>>() // strategyId -> chronological outcomes, no lookahead

        // Reversal-Zone (Checkpoint C) replay state. pendingSecondarySignals mirrors
        // pendingSignals but for the minute-4 scan; reversalZoneScannedCandles guards
        // against re-scanning the same candle's 3rd sub-candle twice (shouldn't happen
        // given each 1m candle is only ever visited once by this loop, but matches the
        // same defensive style as `lockedCandles` above).
        val pendingSecondarySignals = HashMap<String, Signal>()
        val reversalZoneScannedCandles = HashSet<String>()
        var reversalZoneWins = 0
        var reversalZoneLosses = 0
        var reversalZonePnl = 0.0
        val reversalZoneStrategyUsage = HashMap<String, Int>()
        val reversalZoneStrategyPnl = HashMap<String, Double>()

        val allSignals = ArrayList<Signal>()
        var wins = 0
        var losses = 0
        var totalPnl = 0.0
        val strategyUsage = HashMap<String, Int>()
        val strategyPnl = HashMap<String, Double>()

        fun statsFor(strategyId: String): RecentWindowStats {
            val history = strategyRollingStats[strategyId] ?: return RecentWindowStats(0, 0.0)
            if (history.isEmpty()) return RecentWindowStats(0, 0.0)
            val recent = history.takeLast(200) // rolling window within this replay, chronological only
            val winsInWindow = recent.count { it.won }
            val z = DynamicScore.zScoreVsBreakeven(winsInWindow, recent.size, database.financialModel.breakevenWinRatePct)
            return RecentWindowStats(recent.size, recent.map { it.pnl }.average(), z)
        }

        val totalCandles = candles.size.coerceAtLeast(1)
        var lastReportedPct = 50
        // Report roughly 200 times over the whole replay - frequent enough to feel live,
        // rare enough not to flood the UI with recompositions.
        val progressStride = (totalCandles / 200).coerceAtLeast(1)

        for ((index, candle) in candles.withIndex()) {
            store.addClosed1m(candle)
            val events = aggregator.onClosed1mCandle(candle)
            for (event in events) {
                when (event) {
                    is CandleEvent.CheckpointReached -> {
                        if (event.timestampMillis < periodStart) continue // still in warm-up window
                        val candleId = Instant.ofEpochMilli(event.candleOpenTimeMillis).toString()
                        if (candleId in lockedCandles) continue // signal lock (section 9)

                        val result = CoreSignalEngine.evaluateCheckpoint(
                            database = database,
                            store = store,
                            candleOpenTimeMillis = event.candleOpenTimeMillis,
                            candleOpen = event.candleOpen,
                            checkpoint = event.checkpoint,
                            referencePrice = event.referencePrice,
                            minute1Candle = event.minute1,
                            minute2Candle = event.minute2,
                            timestampMillis = event.timestampMillis,
                            statsProvider = ::statsFor,
                            blockedStrategyIds = blockedStrategyIds
                        )
                        val signal = result.signal ?: continue
                        lockedCandles.add(candleId)
                        pendingSignals[candleId] = signal
                        allSignals.add(signal)
                        strategyUsage[signal.activeStrategyId] = (strategyUsage[signal.activeStrategyId] ?: 0) + 1
                    }
                    is CandleEvent.FiveMinuteCandleClosed -> {
                        val candleId = Instant.ofEpochMilli(event.candle.openTimeMillis).toString()
                        val pending = pendingSignals.remove(candleId) ?: continue
                        val (status, pnl) = CoreSignalEngine.evaluateResult(database, pending, event.candle.close)
                        pending.status = status
                        pending.finalClose = event.candle.close
                        pending.pnlUsd = pnl
                        if (status == SignalStatus.WON) wins++ else losses++
                        totalPnl += pnl
                        strategyPnl[pending.activeStrategyId] = (strategyPnl[pending.activeStrategyId] ?: 0.0) + pnl
                        strategyRollingStats.getOrPut(pending.activeStrategyId) { ArrayList() }
                            .add(RollingOutcome(pnl, status == SignalStatus.WON))

                        // Resolve this same candle's Reversal-Zone secondary too, if the
                        // minute-4 scan below fired one for it.
                        val pendingSecondary = pendingSecondarySignals.remove(candleId)
                        if (pendingSecondary != null) {
                            val (secStatus, secPnl) = CoreSignalEngine.evaluateResult(database, pendingSecondary, event.candle.close)
                            pendingSecondary.status = secStatus
                            pendingSecondary.finalClose = event.candle.close
                            pendingSecondary.pnlUsd = secPnl
                            if (secStatus == SignalStatus.WON) reversalZoneWins++ else reversalZoneLosses++
                            reversalZonePnl += secPnl
                            pendingSecondary.originStrategyId?.let { originId ->
                                reversalZoneStrategyPnl[originId] = (reversalZoneStrategyPnl[originId] ?: 0.0) + secPnl
                            }
                        }
                    }
                    else -> {}
                }
            }

            // Reversal-Zone (Checkpoint C) minute-4 scan. `candle` here is exactly the
            // 4th 1-minute sub-candle (t+3min -> t+4min) of its 5-minute bucket the
            // instant minuteOffset == 3 -- see syntheticIntraMinutePath's KDoc for why
            // this is an OHLC-based approximation of the tick-level scan
            // LiveMonitoringService runs, not a byte-for-byte replay of it. (Window
            // moved from minute 2-3 to minute 3-4 per a later chat request; see
            // ReversalZoneScanner.arm's KDoc.)
            val bucketStart = (candle.openTimeMillis / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS
            val minuteOffset = ((candle.openTimeMillis - bucketStart) / 60_000L).toInt()
            if (minuteOffset == 3) {
                val candleId = Instant.ofEpochMilli(bucketStart).toString()
                val primary = pendingSignals[candleId]
                if (primary != null && candleId !in reversalZoneScannedCandles) {
                    reversalZoneScannedCandles.add(candleId)
                    val scanner = ReversalZoneScanner(reversalZoneConfig)
                    scanner.arm(primary.direction, bucketStart)
                    for ((price, tMillis) in syntheticIntraMinutePath(candle)) {
                        val movePct = if (primary.candleOpen != 0.0)
                            (price - primary.candleOpen) / primary.candleOpen * 100.0 else 0.0
                        val fireResult = scanner.onTick(movePct, price, tMillis)
                        if (fireResult != null) {
                            val secondary = Signal(
                                signalId = java.util.UUID.randomUUID().toString(),
                                candleId = candleId,
                                candleOpenTimeMillis = bucketStart,
                                signalTimestampMillis = tMillis,
                                candleOpen = primary.candleOpen,
                                signalPrice = price,
                                direction = fireResult.direction,
                                activeStrategyId = "REVERSAL_ZONE",
                                activeStrategyName = "Reversal-Zone re-entry (minute 4, same direction as ${primary.activeStrategyId})",
                                marketRegime = primary.marketRegime,
                                strategyScore = primary.strategyScore,
                                confidencePct = primary.confidencePct,
                                entryMovePct = fireResult.movePct,
                                checkpoint = Checkpoint.C,
                                originStrategyId = primary.activeStrategyId,
                                originStrategyName = primary.activeStrategyName
                            )
                            pendingSecondarySignals[candleId] = secondary
                            allSignals.add(secondary)
                            reversalZoneStrategyUsage[primary.activeStrategyId] =
                                (reversalZoneStrategyUsage[primary.activeStrategyId] ?: 0) + 1
                            break
                        }
                    }
                }
            }

            if (index % progressStride == 0 || index == totalCandles - 1) {
                val pct = (50 + (index.toFloat() / totalCandles * 50f).toInt()).coerceIn(50, 99)
                if (pct != lastReportedPct) {
                    lastReportedPct = pct
                    onProgress(
                        BacktestProgress(
                            BacktestPhase.REPLAYING, pct,
                            "Replaying candle ${index + 1} / $totalCandles \u2022 ${allSignals.size} signals so far"
                        )
                    )
                }
            }
        }

        if (persist && signalRepository != null) {
            onProgress(BacktestProgress(BacktestPhase.SAVING, 99, "Saving ${allSignals.size} signals\u2026"))
            signalRepository.clearBacktestResults()
            for (s in allSignals) signalRepository.saveSignal(s, isBacktest = true)
        }

        val totalSignals = wins + losses
        onProgress(BacktestProgress(BacktestPhase.DONE, 100, "Done"))
        BacktestSummary(
            periodDays = periodDays,
            totalSignals = totalSignals,
            wins = wins,
            losses = losses,
            winRatePct = if (totalSignals > 0) wins.toDouble() / totalSignals * 100.0 else 0.0,
            totalPnlUsd = totalPnl,
            finalBalanceUsd = database.financialModel.startCapitalUsd + totalPnl,
            strategyUsage = strategyUsage,
            strategyPnl = strategyPnl,
            bestStrategyId = strategyPnl.maxByOrNull { it.value }?.key,
            reversalZoneSignals = reversalZoneWins + reversalZoneLosses,
            reversalZoneWins = reversalZoneWins,
            reversalZoneLosses = reversalZoneLosses,
            reversalZonePnlUsd = reversalZonePnl,
            reversalZoneStrategyUsage = reversalZoneStrategyUsage,
            reversalZoneStrategyPnl = reversalZoneStrategyPnl
        )
    }
}

/**
 * Historical klines only give one OHLC bar per minute, not the tick-by-tick path Live
 * scans against with a real 10-second timer (see ReversalZoneScanner). To let a backtest
 * still exercise the SAME Reversal-Zone rules against SOME approximation of what
 * happened inside the fourth 1-minute sub-candle (t+3min -> t+4min), this reconstructs a
 * plausible 4-point intra-minute path -- open, the two extremes (low/high), close --
 * spread evenly across that 60-second span, ordered by whichever extreme more plausibly
 * came first given how the candle ultimately resolved (a candle that closes above its
 * own open is assumed to have dipped to its low before rallying to its high; one that
 * closes below is assumed the reverse). This is a stated approximation, not real tick
 * data -- exact 10-second timing and which extreme actually came first cannot be
 * recovered from OHLC alone, so a backtest's Reversal-Zone numbers should be read as
 * indicative, not as exact as its primary-signal numbers (which need no such
 * reconstruction).
 */
private fun syntheticIntraMinutePath(candle: Candle): List<Pair<Double, Long>> {
    val t0 = candle.openTimeMillis
    val span = (candle.closeTimeMillis - candle.openTimeMillis).coerceAtLeast(1L)
    val extremesInOrder = if (candle.close >= candle.open)
        listOf(candle.low, candle.high) else listOf(candle.high, candle.low)
    return listOf(
        candle.open to t0,
        extremesInOrder[0] to t0 + span / 3,
        extremesInOrder[1] to t0 + span * 2 / 3,
        candle.close to candle.closeTimeMillis
    )
}
