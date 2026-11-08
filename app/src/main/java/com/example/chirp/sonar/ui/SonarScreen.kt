package com.example.chirp.sonar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chirp.sonar.SonarConfig
import com.example.chirp.sonar.SonarFrame
import com.example.chirp.sonar.SonarState
import com.example.chirp.sonar.alerts.AlertLevel
import com.example.chirp.sonar.ranging.Ranging
import com.example.chirp.sonar.tracking.Trend
import com.example.chirp.ui.theme.StatusColors

@Composable
fun SonarScreen(vm: SonarViewModel, onStart: () -> Unit) {
    val frame by vm.frame.collectAsState()
    val status by vm.status.collectAsState()
    val running by vm.running.collectAsState()
    val error by vm.error.collectAsState()
    val config by vm.config.collectAsState()
    val vibrate by vm.vibrate.collectAsState()
    val handheld by vm.handheld.collectAsState()

    val state = if (running) frame?.state ?: status.state else null
    val level = frame?.alert?.level?.takeIf { running } ?: AlertLevel.NONE
    val accent by animateColorAsState(
        when (level) {
            AlertLevel.WARNING -> StatusColors.warning
            AlertLevel.CAUTION -> StatusColors.caution
            AlertLevel.NONE -> MaterialTheme.colorScheme.primary
        },
        label = "accent",
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.14f),
                    0.45f to MaterialTheme.colorScheme.background,
                ),
            ),
        ) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()

                error?.let { ErrorCard(it) }

                HeroCard(
                    frame = frame, running = running, state = state, level = level, accent = accent,
                    statusMessage = status.message, maxRangeM = config.maxRangeM,
                    onToggle = { if (running) vm.stop() else onStart() },
                    onRecalibrate = vm::recalibrate,
                )

                AnimatedVisibility(!running && frame == null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    WelcomeCard()
                }

                SettingsCard(
                    config = config, running = running, handheld = handheld, vibrate = vibrate,
                    vibrationAvailable = vm.vibrationAvailable,
                    onConfig = vm::selectConfig, onHandheld = vm::setHandheld, onVibrate = vm::setVibrate,
                )

                SignalLab(vm)

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val c = MaterialTheme.colorScheme.primary
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
            Canvas(Modifier.padding(10.dp)) {
                val o = Offset(size.width * 0.2f, size.height / 2)
                drawCircle(c, size.minDimension * 0.11f, o)
                for (k in 1..2) {
                    val r = size.minDimension * 0.38f * k
                    drawArc(c, -50f, 100f, false, Offset(o.x - r, o.y - r), Size(r * 2, r * 2),
                        style = Stroke(size.minDimension * 0.1f, cap = StrokeCap.Round))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Chirp", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sense what's nearby with sound",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroCard(
    frame: SonarFrame?,
    running: Boolean,
    state: SonarState?,
    level: AlertLevel,
    accent: Color,
    statusMessage: String,
    maxRangeM: Double,
    onToggle: () -> Unit,
    onRecalibrate: () -> Unit,
) {
    val track = frame?.track?.takeIf { running }
    val shown = (track?.takeIf { it.confirmed }?.distanceM ?: frame?.detection?.nearest?.distanceM)
        ?.takeIf { running && state == SonarState.RUNNING }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusPill(running, state, level, accent)

            AnimatedVisibility(level != AlertLevel.NONE && frame?.alert != null) {
                Text(
                    frame?.alert?.reason.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            RadarView(shown, maxRangeM, active = running, accent = accent)
            Spacer(Modifier.height(12.dp))

            Text(
                shown?.let { "%.2f m".format(it) } ?: "—",
                style = MaterialTheme.typography.displayMedium,
                color = if (shown != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    shown != null -> "%.1f ft".format(shown * Ranging.FEET_PER_METER) +
                        (track?.let { "  ·  " + trendLabel(it.trend) } ?: "")
                    !running -> "Tap start to begin scanning"
                    state == SonarState.RUNNING -> "All clear — nothing in range"
                    else -> statusMessage
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (state == SonarState.CALIBRATING) {
                val progress by animateFloatAsState(frame?.calibrationProgress ?: 0f, label = "cal")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large,
                colors = if (running) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) else ButtonDefaults.buttonColors(),
            ) {
                Text(if (running) "Stop scanning" else "Start scanning", style = MaterialTheme.typography.titleMedium)
            }
            AnimatedVisibility(running) {
                FilledTonalButton(
                    onClick = onRecalibrate,
                    enabled = state == SonarState.RUNNING,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.large,
                ) { Text("Recalibrate") }
            }
        }
    }
}

@Composable
private fun StatusPill(running: Boolean, state: SonarState?, level: AlertLevel, accent: Color) {
    val (label, dot) = when {
        !running -> "Ready" to MaterialTheme.colorScheme.outline
        level == AlertLevel.WARNING -> "Too close" to StatusColors.warning
        level == AlertLevel.CAUTION -> "Heads up" to StatusColors.caution
        state == SonarState.RUNNING -> "Scanning" to StatusColors.clear
        state == SonarState.CALIBRATING -> "Calibrating · keep the area clear" to accent
        state == SonarState.ACQUIRING -> "Finding the echo…" to accent
        state == SonarState.MEASURING_AMBIENT -> "Listening to the room…" to accent
        else -> "Starting…" to accent
    }
    Surface(shape = CircleShape, color = dot.copy(alpha = 0.15f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun trendLabel(t: Trend) = when (t) {
    Trend.APPROACHING -> "getting closer"
    Trend.RECEDING -> "moving away"
    Trend.STEADY -> "holding still"
    Trend.UNKNOWN -> "confirming…"
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Something went wrong", style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WelcomeCard() {
    SectionCard(title = "Getting a good reading") {
        Step(1, "Lay the phone flat", "Point the bottom edge (speaker and mic) toward open space.")
        Step(2, "Turn the volume to about half", "Loud enough to echo, quiet enough not to clip.")
        Step(3, "Keep it clear for ~3 seconds", "Chirp learns what the room sounds like while it calibrates.")
        Step(4, "Bring something close", "A book, a box or your hand. Watch the distance update live.")
    }
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(
    config: SonarConfig,
    running: Boolean,
    handheld: Boolean,
    vibrate: Boolean,
    vibrationAvailable: Boolean,
    onConfig: (SonarConfig) -> Unit,
    onHandheld: (Boolean) -> Unit,
    onVibrate: (Boolean) -> Unit,
) {
    SectionCard(title = "Settings") {
        Text("Sound", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val options = listOf("Silent" to SonarConfig.INAUDIBLE, "Audible" to SonarConfig.AUDIBLE)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, c) ->
                SegmentedButton(
                    selected = config == c,
                    onClick = { onConfig(c) },
                    enabled = !running,
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        Text(
            (if (config == SonarConfig.AUDIBLE) "8–14 kHz chirps you can hear; useful for checking it's working."
            else "15–20 kHz chirps most people can't hear.") +
                if (running) " Stop scanning to change." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            "Handheld mode",
            if (handheld) "Adapts to movement within ~1.5 s" else "Fixed baseline, best with the phone on a table",
            handheld, onHandheld,
        )
        if (vibrationAvailable) {
            Spacer(Modifier.height(8.dp))
            SwitchRow("Vibrate on alert", "Buzz when something gets too close", vibrate, onVibrate)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SignalLab(vm: SonarViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Signal lab", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Live plots, accuracy check and diagnostics",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("⌄", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rotation))
            }
            AnimatedVisibility(open) {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { DebugPanels(vm) }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
