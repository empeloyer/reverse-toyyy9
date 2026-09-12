package com.btcsignal.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "btc_signal_settings")

/**
 * Blocking a strategy here (Strategies screen "Block" button, [SettingsRepository
 * .setStrategyBlocked]) excludes it from `CoreSignalEngine`'s vote entirely (see
 * `CoreSignalEngine.evaluateCheckpoint`'s `blockedStrategyIds` check) — it can never win
 * a primary Checkpoint A/B signal while blocked. Since a Reversal-Zone (Checkpoint C)
 * re-entry can only ever be armed off of an already-fired primary (see
 * `ReversalZoneScanner.arm` / `LiveMonitoringService.evaluateCheckpoint`), blocking a
 * strategy here also fully and permanently stops it from producing ANY Reversal-Zone
 * re-entry — not just from being shown/counted in the "Reversal-Zone Strategy Usage"
 * list, but from firing at all, on both Live and Backtest.
 *
 * These 21 ids are exactly the strategies that showed a net-negative Reversal-Zone PnL
 * in the user's own backtest/live run (chat handoff, 2026-09-12 — the "red" rows the
 * Reversal-Zone Strategy Usage list used to show before it was changed to list only
 * profitable origin strategies). Shipped as the DEFAULT for a fresh install of this
 * build only — i.e. this is what a person sees before ever touching the Strategies
 * screen, not a change to what's already stored for someone upgrading in place (a
 * pre-existing stored preference in DataStore always wins over this default, see
 * `settingsFlow` below). Any of the 21 can still be individually un-blocked from the
 * Strategies screen at any time; this is a starting point, not a hardcoded restriction.
 */
private val DEFAULT_BLOCKED_STRATEGY_IDS: Set<String> = setOf(
    "STRAT-001", "STRAT-002", "STRAT-007", "STRAT-009", "STRAT-017", "STRAT-025",
    "STRAT-N1B01", "STRAT-N1B02", "STRAT-N1B03",
    "STRAT-N2H02", "STRAT-N2H03",
    "STRAT-N4D02", "STRAT-N4D03",
    "STRAT-N5L01", "STRAT-N5L02",
    "STRAT-N6W01", "STRAT-N6W02",
    "STRAT-N7A01", "STRAT-N7A02", "STRAT-N7A03",
    "STRAT-N9S01"
)

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val blockedStrategyIds: Set<String> = DEFAULT_BLOCKED_STRATEGY_IDS,
    // Reversal-Zone (minute-3-to-4, Checkpoint C) zone edges, user-editable in Settings
    // as "Rev Green" / "Rev Red" (chat request). Defaults match ReversalZoneConfig's own
    // shipped defaults (-0.03%..-0.10% for Green, +0.03%..+0.10% for Red) so a person who
    // never opens this Settings section gets identical behavior to before this was
    // configurable. Applied to BOTH Live (LiveMonitoringService) and Backtest
    // (BacktestEngine.run) once saved.
    val revGreenInnerPct: Double = 0.03,
    val revGreenOuterPct: Double = 0.10,
    val revRedInnerPct: Double = 0.03,
    val revRedOuterPct: Double = 0.10
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val BLOCKED_STRATEGY_IDS = stringSetPreferencesKey("blocked_strategy_ids")
        val REV_GREEN_INNER_PCT = doublePreferencesKey("rev_green_inner_pct")
        val REV_GREEN_OUTER_PCT = doublePreferencesKey("rev_green_outer_pct")
        val REV_RED_INNER_PCT = doublePreferencesKey("rev_red_inner_pct")
        val REV_RED_OUTER_PCT = doublePreferencesKey("rev_red_outer_pct")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            soundEnabled = prefs[Keys.SOUND] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            blockedStrategyIds = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: DEFAULT_BLOCKED_STRATEGY_IDS,
            revGreenInnerPct = prefs[Keys.REV_GREEN_INNER_PCT] ?: 0.03,
            revGreenOuterPct = prefs[Keys.REV_GREEN_OUTER_PCT] ?: 0.10,
            revRedInnerPct = prefs[Keys.REV_RED_INNER_PCT] ?: 0.03,
            revRedOuterPct = prefs[Keys.REV_RED_OUTER_PCT] ?: 0.10
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    /** Toggles a strategy's block state (Strategies screen "Block" button). A blocked
     *  strategy is skipped by CoreSignalEngine's vote entirely, on BOTH Live
     *  (LiveMonitoringService) and Backtest (BacktestState/BacktestEngine) -- it cannot
     *  produce a new primary signal, and therefore also cannot produce a Reversal-Zone
     *  re-entry (Checkpoint C), which only ever arms off of an already-fired primary --
     *  until unblocked again. Falls back to [DEFAULT_BLOCKED_STRATEGY_IDS], not
     *  emptySet(), when no block key has ever been written yet, so un-blocking a single
     *  strategy on a fresh install (where the *effective* set is already the 21
     *  defaults) toggles only that one id rather than silently persisting an explicit
     *  empty set and un-blocking all 21 at once. */
    suspend fun setStrategyBlocked(strategyId: String, blocked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: DEFAULT_BLOCKED_STRATEGY_IDS
            prefs[Keys.BLOCKED_STRATEGY_IDS] = if (blocked) current + strategyId else current - strategyId
        }
    }

    /** Persists all four Reversal-Zone range edges at once (Settings screen "Save"
     *  button, chat request) -- one atomic write rather than four, so a Live candle
     *  boundary or a Backtest run reading settingsFlow mid-save can never observe a
     *  half-updated combination (e.g. a new green-inner paired with the old green-outer). */
    suspend fun setReversalZoneRanges(
        greenInnerPct: Double,
        greenOuterPct: Double,
        redInnerPct: Double,
        redOuterPct: Double
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REV_GREEN_INNER_PCT] = greenInnerPct
            prefs[Keys.REV_GREEN_OUTER_PCT] = greenOuterPct
            prefs[Keys.REV_RED_INNER_PCT] = redInnerPct
            prefs[Keys.REV_RED_OUTER_PCT] = redOuterPct
        }
    }
}
