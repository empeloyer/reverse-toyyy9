package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.GothicSwitch
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabSettingsColor
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val notificationHelper = remember { AppContainer.notificationHelper(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GothicPageHeader(title = "Settings", accent = TabSettingsColor)

        SectionCard("Notifications") {
            SettingRow("Notifications", settings.notificationsEnabled) {
                scope.launch { settingsRepo.setNotificationsEnabled(it) }
            }
            SettingRow("Sound", settings.soundEnabled) {
                scope.launch { settingsRepo.setSoundEnabled(it) }
            }
            SettingRow("Vibration", settings.vibrationEnabled) {
                scope.launch { settingsRepo.setVibrationEnabled(it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GothicButton(
                    text = "Test Notification",
                    role = GothicRole.PURPLE,
                    filled = false,
                    compact = true,
                    onClick = {
                        notificationHelper.sendTestNotification(settings.soundEnabled, settings.vibrationEnabled)
                    }
                )
                GothicButton(
                    text = "Test Sound",
                    role = GothicRole.PURPLE,
                    filled = false,
                    compact = true,
                    onClick = {
                        notificationHelper.sendTestNotification(soundEnabled = true, vibrationEnabled = settings.vibrationEnabled)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ReversalZoneRangeCard(settingsRepo = settingsRepo, scope = scope)
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        GothicSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * "Rev Green" / "Rev Red" Reversal-Zone range editor (chat request): the minute-3-to-4
 * secondary-entry zone edges (see ReversalZoneScanner.kt / ReversalZoneConfig) — shipped
 * defaults -0.03%..-0.10% for a GREEN primary, +0.03%..+0.10% for a RED primary — made
 * user-editable here, applied to both Live and Backtest once Saved.
 *
 * Deliberately NOT bound live to `settingsRepo.settingsFlow` the way the switches above
 * are: these are typed numeric fields, so every keystroke would otherwise round-trip
 * through DataStore before the next character could be typed. Local text state is seeded
 * once from the persisted values (LaunchedEffect(Unit) below) and only written back to
 * SettingsRepository when the user taps Save, exactly as asked ("... و با زدن دکمه save
 * شوند").
 */
@Composable
private fun ReversalZoneRangeCard(
    settingsRepo: com.btcsignal.app.data.repository.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var loaded by remember { mutableStateOf(false) }
    var greenInnerText by remember { mutableStateOf("0.03") }
    var greenOuterText by remember { mutableStateOf("0.10") }
    var redInnerText by remember { mutableStateOf("0.03") }
    var redOuterText by remember { mutableStateOf("0.10") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val initial = settingsRepo.settingsFlow.first()
        greenInnerText = formatPct(initial.revGreenInnerPct)
        greenOuterText = formatPct(initial.revGreenOuterPct)
        redInnerText = formatPct(initial.revRedInnerPct)
        redOuterText = formatPct(initial.revRedOuterPct)
        loaded = true
    }

    fun onEdited() {
        justSaved = false
        errorText = null
    }

    SectionCard("Reversal-Zone Ranges (Minute 3\u20134)") {
        Text(
            "How far from candle open the minute-3-to-4 secondary entry watches, per primary " +
                "signal color. Applies to both Live and Backtest once saved.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(12.dp))

        if (!loaded) {
            Text("Loading\u2026", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            Text("Rev Green", style = MaterialTheme.typography.titleMedium, color = GreenSignal)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PctField(
                    label = "Start %", value = greenInnerText, modifier = Modifier.weight(1f),
                    onValueChange = { greenInnerText = it; onEdited() }
                )
                PctField(
                    label = "End %", value = greenOuterText, modifier = Modifier.weight(1f),
                    onValueChange = { greenOuterText = it; onEdited() }
                )
            }
            val greenInnerPreview = greenInnerText.toDoubleOrNull()
            val greenOuterPreview = greenOuterText.toDoubleOrNull()
            if (greenInnerPreview != null && greenOuterPreview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zone: -${formatPct(greenInnerPreview)}% to -${formatPct(greenOuterPreview)}% from candle open",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Rev Red", style = MaterialTheme.typography.titleMedium, color = RedSignal)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PctField(
                    label = "Start %", value = redInnerText, modifier = Modifier.weight(1f),
                    onValueChange = { redInnerText = it; onEdited() }
                )
                PctField(
                    label = "End %", value = redOuterText, modifier = Modifier.weight(1f),
                    onValueChange = { redOuterText = it; onEdited() }
                )
            }
            val redInnerPreview = redInnerText.toDoubleOrNull()
            val redOuterPreview = redOuterText.toDoubleOrNull()
            if (redInnerPreview != null && redOuterPreview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zone: +${formatPct(redInnerPreview)}% to +${formatPct(redOuterPreview)}% from candle open",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary
                )
            }

            errorText?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = RedSignal)
            }
            if (justSaved && errorText == null) {
                Spacer(Modifier.height(10.dp))
                Text("Saved \u2713", style = MaterialTheme.typography.bodySmall, color = GreenSignal)
            }

            Spacer(Modifier.height(12.dp))
            GothicButton(
                text = "Save",
                role = GothicRole.CYAN,
                onClick = {
                    val greenInner = greenInnerText.toDoubleOrNull()
                    val greenOuter = greenOuterText.toDoubleOrNull()
                    val redInner = redInnerText.toDoubleOrNull()
                    val redOuter = redOuterText.toDoubleOrNull()
                    if (greenInner == null || greenOuter == null || redInner == null || redOuter == null) {
                        errorText = "Enter a valid number in every field."
                    } else if (greenInner <= 0 || greenOuter <= 0 || redInner <= 0 || redOuter <= 0) {
                        errorText = "Values must be greater than 0."
                    } else if (greenInner >= greenOuter) {
                        errorText = "Rev Green: Start must be less than End."
                    } else if (redInner >= redOuter) {
                        errorText = "Rev Red: Start must be less than End."
                    } else {
                        errorText = null
                        scope.launch {
                            settingsRepo.setReversalZoneRanges(
                                greenInnerPct = greenInner,
                                greenOuterPct = greenOuter,
                                redInnerPct = redInner,
                                redOuterPct = redOuter
                            )
                            justSaved = true
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PctField(label: String, value: String, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

/** Trims a trailing ".0" (e.g. "0.1" instead of "0.10" round-tripping oddly) while still
 *  showing genuinely fractional values as typed -- purely a display nicety for the
 *  seeded/preview text, never used for the persisted or compared numeric value. */
private fun formatPct(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
