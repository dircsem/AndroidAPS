package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import app.aaps.core.ui.compose.icons.IcCarbs
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.ui.compose.AapsSpacing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.plugins.main.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import app.aaps.core.ui.R as CoreUiR

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusAgoraCard(
    state: GlassUiState,
    isDark: Boolean,
    onOpenLoop: () -> Unit,
    onOpenRunningMode: () -> Unit,
    onOpenLoopDashboard: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenInsulin: () -> Unit,
    onOpenPump: () -> Unit,
    onOpenCannula: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenBasal: () -> Unit,
    onOpenSensorInsert: () -> Unit,
    onOpenSensorQuality: () -> Unit,
    onOpenTools: () -> Unit,
    selectedPills: List<GlassPillId>,
) {
    // Minute clock: keeps "last value N min ago" and the stale grey moving between view-model refreshes.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val glucoseStale = GlassCgmLinkStatus.isStale(state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val linkBanner = GlassCgmLinkStatus.banner(state.warmup, state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val neutralColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // T17: the places come from layoutGlassPills (plain, tested); this code only draws them. Fill top-down beside
            // the ring, then rows of 3 below it, so the card uses the least height for the user's selection.
            val layout = layoutGlassPills(
                shownGlassSlots(selectedPills, hasMedLinkBattery = state.medLinkBattery != null, hasDelivered = state.medLinkDeliveredPercent != null)
            )
            val drawPill: @Composable (GlassSlot) -> Unit = { slot ->
                GlassSlotPill(
                    slot = slot, state = state, isDark = isDark,
                    onOpenLoop = onOpenLoop, onOpenLoopDashboard = onOpenLoopDashboard, onOpenTarget = onOpenTarget,
                    onOpenInsulin = onOpenInsulin, onOpenPump = onOpenPump, onOpenCannula = onOpenCannula,
                    onOpenBattery = onOpenBattery, onOpenBasal = onOpenBasal, onOpenSensorInsert = onOpenSensorInsert,
                    onOpenSensorQuality = onOpenSensorQuality, onOpenTools = onOpenTools
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    layout.left.forEach { drawPill(it) }
                }

                // CENTER: glucose inside today's TIR ring, and the TIR line just under the ring (not inside: the ring
                // stroke crossed it at the narrow bottom of the circle, user 2026-11-14)
                Column(
                    modifier = Modifier.weight(TIR_COLUMN_WEIGHT).offset(y = TIR_RING_LIFT),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // T22: the whole ring is the touch target (big enough for shaky hands). The tap only opens the
                    // running mode screen; the BOLUS protection asks first. No long press here.
                    Box(
                        modifier = Modifier
                            .size(TIR_RING_SIZE)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = stringResource(CoreUiR.string.running_mode)) { onOpenRunningMode() },
                        contentAlignment = Alignment.Center
                    ) {
                        TirRing(
                            tir = state.tir,
                            trackColor = (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)).copy(alpha = 0.18f),
                            modifier = Modifier.matchParentSize()
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            // An old value stays visible, greyed and struck through, like the classic overview.
                            Text(
                                text = state.currentBg,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (glucoseStale) neutralColor else Color(state.glucoseColor),
                                textDecoration = if (glucoseStale) TextDecoration.LineThrough else null,
                                letterSpacing = (-1.2).sp,
                                lineHeight = 34.sp
                            )
                            Text(
                                text = state.unit,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.padding(top = 1.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (state.trendArrowRes != null) {
                                    Icon(
                                        painter = painterResource(id = state.trendArrowRes),
                                        contentDescription = null,
                                        tint = Color(state.glucoseColor),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = state.deltaText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(state.glucoseColor),
                                    modifier = Modifier.padding(start = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = state.timeAgo,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }
                    TirTrendText(
                        tir = state.tir,
                        neutralColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    layout.right.forEach { drawPill(it) }
                }
            }

            linkBanner?.let { banner ->
                Spacer(modifier = Modifier.height(8.dp))
                CgmLinkBannerText(banner)
            }

            // Rows below the ring: the same 3 columns as above (left, under the ring, right), so the pills line up
            layout.rows.forEach { row ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    for (column in 0 until GLASS_PILLS_PER_ROW) {
                        Box(
                            modifier = Modifier.weight(if (column == 1) TIR_COLUMN_WEIGHT else 1f),
                            contentAlignment = when (column) {
                                0    -> Alignment.TopStart
                                1    -> Alignment.TopCenter
                                else -> Alignment.TopEnd
                            }
                        ) {
                            row.getOrNull(column)?.let { drawPill(it) }
                        }
                    }
                }
            }

            // MedLink only: next pump operation on the left, the MedLink command running now on the right, as plain text
            // (T16, T17: user 2026-11-14). An unknown value leaves its side empty; both unknown: no row. Keep this when merging upstream.
            if (state.medLinkDeliveredPercent != null) {
                val next = state.medLinkCommandNext?.takeIf { it.isNotBlank() }
                val now = state.medLinkCommandNow?.takeIf { it.isNotBlank() }
                if (next != null || now != null) {
                    Spacer(modifier = Modifier.height(AapsSpacing.medium))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                        Text(
                            text = next?.let { boldLabel(stringResource(R.string.dashboard_glass_command_next_line, it), it) } ?: AnnotatedString(""),
                            style = MaterialTheme.typography.bodySmall,
                            color = neutralColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = now?.let { boldLabel(stringResource(R.string.dashboard_glass_command_now_line, it), it) } ?: AnnotatedString(""),
                            style = MaterialTheme.typography.bodySmall,
                            color = neutralColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** One Glass pill: its title, value, color and tap action (T17: the place is decided by `layoutGlassPills`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassSlotPill(
    slot: GlassSlot,
    state: GlassUiState,
    isDark: Boolean,
    onOpenLoop: () -> Unit,
    onOpenLoopDashboard: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenInsulin: () -> Unit,
    onOpenPump: () -> Unit,
    onOpenCannula: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenBasal: () -> Unit,
    onOpenSensorInsert: () -> Unit,
    onOpenSensorQuality: () -> Unit,
    onOpenTools: () -> Unit,
) {
    when (slot) {
        GlassSlot.RESERVOIR       -> SidePill(
            stringResource(R.string.dashboard_glass_insulin_age_label), state.insulinAge, R.drawable.ic_glyco_insulin, isDark,
            onOpenPump, statusLevelToColor(state.insulinStatus)
        )

        GlassSlot.CANNULA         -> SidePill(
            stringResource(R.string.dashboard_glass_cannula_age_label), state.cannulaAge, R.drawable.ic_glyco_cannula, isDark,
            onOpenCannula, statusLevelToColor(state.cannulaAgeStatus)
        )

        GlassSlot.BATTERY         -> SidePill(
            stringResource(R.string.dashboard_glass_pump_battery_short), state.batteryValue, R.drawable.ic_glyco_battery, isDark,
            onOpenBattery, statusLevelToColor(state.batteryStatus)
        )

        // MedLink only: pump and MedLink battery in ONE pill (user, 2026-11-14), each value in its own warning color
        GlassSlot.BATTERY_PAIR    -> {
            val pump = state.batteryValue
            val medLink = state.medLinkBattery.orEmpty()
            val text = stringResource(R.string.dashboard_glass_two_values, pump, medLink)
            val pumpColor = statusLevelToColor(state.batteryStatus)
            val medLinkColor = statusLevelToColor(state.medLinkBatteryStatus)
            val styled = buildAnnotatedString {
                append(text)
                // the template decides the order (also right-to-left): color each value where it really is
                text.indexOf(pump).takeIf { it >= 0 && pump.isNotEmpty() }?.let { addStyle(SpanStyle(color = pumpColor), it, it + pump.length) }
                text.lastIndexOf(medLink).takeIf { it >= 0 && medLink.isNotEmpty() }?.let { addStyle(SpanStyle(color = medLinkColor), it, it + medLink.length) }
            }
            GlassPill(
                label = stringResource(R.string.dashboard_glass_batteries_label),
                value = text,
                styledValue = styled,
                isDark = isDark,
                modifier = Modifier.width(GLASS_PILL_WIDTH).clickable { onOpenBattery() },
                leadingIcon = { PillIcon(R.drawable.ic_glyco_battery, isDark) }
            )
        }

        GlassSlot.TBR             -> SidePill(
            stringResource(R.string.dashboard_glass_tbr_short), state.basalPercentText, R.drawable.ic_glyco_insulin, isDark, onOpenBasal
        )

        // MedLink only: basal delivered in the last minutes
        GlassSlot.DELIVERED       -> SidePill(
            stringResource(R.string.dashboard_glass_delivered_label, state.medLinkDeliveredMinutes),
            state.medLinkDeliveredPercent?.let { stringResource(CoreUiR.string.format_percent, it) }.orEmpty(),
            R.drawable.ic_glyco_insulin, isDark, onOpenBasal
        )

        GlassSlot.SENSOR          -> GlassPill(
            label = stringResource(R.string.dashboard_glass_sensor_age_label),
            value = state.sensorAge,
            isDark = isDark,
            valueColor = statusLevelToColor(state.sensorAgeStatus),
            modifier = Modifier.width(GLASS_PILL_WIDTH).combinedClickable(onClick = onOpenSensorQuality, onLongClick = onOpenSensorInsert),
            leadingIcon = { PillIcon(R.drawable.ic_glyco_sensor, isDark) }
        )

        GlassSlot.LOOP            -> GlassPill(
            label = stringResource(R.string.dashboard_glass_loop_label),
            value = state.loopStatusText,
            isDark = isDark,
            modifier = Modifier.width(GLASS_PILL_WIDTH).combinedClickable(onClick = onOpenLoopDashboard, onLongClick = onOpenLoop),
            leadingIcon = { LoopStatusDot(state.loopIsRunning) }
        )

        GlassSlot.ACTIVITY        -> SidePill(
            stringResource(R.string.dashboard_glass_activity_label),
            stringResource(R.string.dashboard_glass_activity_value, state.stepsText, state.hrText),
            R.drawable.ic_glyco_settings, isDark, onClick = null
        )

        // a round target with a dot in the center (user, 2026-11-14)
        GlassSlot.TARGET          -> SidePill(
            stringResource(R.string.dashboard_glass_target_label), state.targetText, R.drawable.ic_glyco_sensor, isDark,
            onOpenTarget, if (state.isTempTargetActive) TEMP_TARGET_COLOR else null, iconVector = Icons.Filled.Adjust
        )

        GlassSlot.LAST_BOLUS      -> SidePill(
            stringResource(R.string.dashboard_glass_last_bolus_short), state.lastBolusText, R.drawable.ic_glyco_insulin, isDark, onOpenInsulin
        )

        GlassSlot.IOB             -> SidePill(
            stringResource(R.string.dashboard_glass_iob_label), state.iobText, R.drawable.ic_glyco_insulin, isDark, onOpenInsulin
        )

        // the shared AAPS carbs icon, drawn in the Glass icon tint and size
        GlassSlot.LAST_CARBS      -> SidePill(
            stringResource(R.string.dashboard_glass_last_carbs_short), state.lastCarbsText, R.drawable.ic_glyco_insulin, isDark, onOpenInsulin,
            iconVector = IcCarbs
        )

        GlassSlot.TOOLS           -> SidePill(
            stringResource(R.string.dashboard_glass_tools_label), "", R.drawable.ic_glyco_settings, isDark, onOpenTools
        )
    }
}

@Composable
private fun PillIcon(@DrawableRes iconRes: Int, isDark: Boolean) {
    Icon(
        painter = painterResource(id = iconRes),
        contentDescription = null,
        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
        modifier = Modifier.size(14.dp)
    )
}

/** The same look for a Compose icon of `core/ui` (for example the carbs icon, which the Glass set does not have). */
@Composable
private fun PillIcon(icon: ImageVector, isDark: Boolean) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
        modifier = Modifier.size(14.dp)
    )
}

/** Green pulsing dot while the loop runs, grey dot when it does not. */
@Composable
private fun LoopStatusDot(isRunning: Boolean) {
    if (isRunning) {
        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
        // pulseAlpha is read in the draw phase only: reading it in composition
        // recomposed this pill on every frame and kept the render thread busy.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .drawBehind { drawCircle(Color(0xFF22C55E), alpha = pulseAlpha) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(Color(0xFF10B981), CircleShape)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFF94A3B8), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(Color(0xFF94A3B8), CircleShape)
            )
        }
    }
}

/** Width of every Glass pill (was repeated as 100.dp in each pill). */
private val GLASS_PILL_WIDTH = 100.dp


/** Weight of the middle column (the glucose ring) against the two side columns (1 each). */
private const val TIR_COLUMN_WEIGHT = 1.3f

/**
 * "Agora: M - Read bolus status" with the label bold and the value normal (user, 2026-11-15). The label comes from the
 * translated template, so everything outside [value] is the label (any language, any order).
 */
private fun boldLabel(text: String, value: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val start = text.indexOf(value)
    if (start < 0) {
        addStyle(bold, 0, text.length)
    } else {
        if (start > 0) addStyle(bold, 0, start)
        if (start + value.length < text.length) addStyle(bold, start + value.length, text.length)
    }
}

/** Yellow of an active temporary target. */
private val TEMP_TARGET_COLOR = Color(0xFFF4D700)

/**
 * Small Glass pill (label over value, icon); [onClick] null = not clickable.
 * @param iconVector a Compose icon used in place of [iconRes] (null = [iconRes])
 */
@Composable
private fun SidePill(
    label: String,
    value: String,
    @DrawableRes iconRes: Int,
    isDark: Boolean,
    onClick: (() -> Unit)?,
    valueColor: Color? = null,
    iconVector: ImageVector? = null
) {
    GlassPill(
        label = label,
        value = value,
        isDark = isDark,
        valueColor = valueColor,
        modifier = Modifier.width(GLASS_PILL_WIDTH).let { if (onClick != null) it.clickable { onClick() } else it },
        leadingIcon = { if (iconVector != null) PillIcon(iconVector, isDark) else PillIcon(iconRes, isDark) }
    )
}

/**
 * Thin ring with today's time-in-range split: in range (green) from 12 o'clock, then high (amber),
 * then low (red), clockwise. A small gap separates the parts. Without data ([tir] null) only the empty
 * track is drawn.
 */
@Composable
private fun TirRing(tir: GlassTir?, trackColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokePx = TIR_RING_STROKE.toPx()
        val diameter = size.minDimension - strokePx
        if (diameter <= 0f) return@Canvas
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx)
        )
        if (tir == null) return@Canvas
        val parts = listOf(
            tir.inRangePct to GlassColors.emerald,
            tir.abovePct to GlassColors.amber,
            tir.belowPct to GlassColors.red,
        ).filter { it.first > 0f }
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        val gap = if (parts.size > 1) TIR_RING_GAP_DEG else 0f
        var start = -90f
        for ((pct, color) in parts) {
            val sweep = pct / total * 360f
            // A few minutes low must stay visible: never draw a part thinner than the minimum.
            drawArc(
                color = color,
                startAngle = start + gap / 2f,
                sweepAngle = (sweep - gap).coerceAtLeast(TIR_RING_MIN_PART_DEG),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
            )
            start += sweep
        }
    }
}

/** "TIR 78% ↑6" under the glucose: green when better than yesterday, amber when worse. */
@Composable
private fun TirTrendText(tir: GlassTir?, neutralColor: Color, modifier: Modifier = Modifier) {
    val delta = tir?.deltaVsYesterday
    val percent = tir?.let { stringResource(CoreUiR.string.format_percent, it.inRangePct.roundToInt()) }
    val text = when {
        percent == null             -> stringResource(R.string.dashboard_glass_tir_empty)
        delta == null || delta == 0 -> stringResource(R.string.dashboard_glass_tir_value, percent)
        delta > 0                   -> stringResource(R.string.dashboard_glass_tir_up, percent, delta)
        else                        -> stringResource(R.string.dashboard_glass_tir_down, percent, abs(delta))
    }
    val color = when {
        delta == null || delta == 0 -> neutralColor
        delta > 0                   -> GlassColors.emerald
        else                        -> GlassColors.amber
    }
    Text(
        text = text,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
    )
}

/** Fixed ring size: the glucose block is laid out to fit inside it, so the card height stays small. */
private val TIR_RING_SIZE = 130.dp
/** Moves the ring a little up, into the card top padding, without making the card taller. */
private val TIR_RING_LIFT = (-6).dp
private val TIR_RING_STROKE = 5.dp
private const val TIR_RING_GAP_DEG = 3f
private const val TIR_RING_MIN_PART_DEG = 2f

/** "Reconnecting… · last value 14 min ago", in the attention colour of the other dashboard skins. */
@Composable
private fun CgmLinkBannerText(banner: GlassCgmLinkStatus.Banner) {
    val phase = stringResource(
        when (banner.phase) {
            CgmWarmupStatus.Phase.CONNECTING -> R.string.dashboard_warmup_phase_connecting
            CgmWarmupStatus.Phase.RECONNECTING -> R.string.dashboard_warmup_phase_reconnecting
            CgmWarmupStatus.Phase.PAIRING -> R.string.dashboard_warmup_phase_pairing
            CgmWarmupStatus.Phase.WARMING,
            CgmWarmupStatus.Phase.OTHER -> R.string.dashboard_warmup_label
        }
    )
    Text(
        text = banner.minutesSinceLastValue?.let { stringResource(R.string.dashboard_glass_cgm_link_banner, phase, it.toInt()) } ?: phase,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = colorResource(R.color.dashboard_metric_attention),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
