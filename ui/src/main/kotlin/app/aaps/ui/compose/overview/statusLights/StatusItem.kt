package app.aaps.ui.compose.overview.statusLights

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.ui.compose.StatusLevel

/**
 * Status item for sensor/insulin/cannula/battery display in Overview.
 *
 * Compact flags control what is shown in the collapsed (Overview-like) row.
 * Expanded flags control what is shown in the expanded (Actions-like) detail rows.
 */
data class StatusItem(
    val label: String,
    val age: String,
    // MedLink fork: when the age started (for example the reservoir change), so small pills can build a short age
    val ageTimestamp: Long? = null,
    val ageStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val agePercent: Float = -1f, // 0-1 progress toward critical threshold
    val level: String? = null,
    // MedLink fork: the level number only, without the unit (for example `86`, `200+`), for small pills
    val levelNumber: String? = null,
    val levelStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val levelPercent: Float = -1f, // -1 means no level, 0-1 for progress (inverted: 100% = empty/critical)
    val icon: ImageVector,
    val compactAge: Boolean = true,     // Show age in collapsed view
    val compactLevel: Boolean = true,   // Show level in collapsed view
    val expandedLevel: Boolean = true   // Show level in expanded view
)
