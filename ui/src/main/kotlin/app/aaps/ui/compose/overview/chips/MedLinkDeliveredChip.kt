package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme

/**
 * MedLink only: basal delivered in the last minutes, next to the TBR chip on the classic main screen.
 * Same look as [TbrChip]. Keep this when merging upstream.
 *
 * @param label box title, for example "Last 30 min"
 * @param value delivered basal, for example "0.975 U/h (117%)"
 */
@Composable
fun MedLinkDeliveredChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val color = AapsTheme.elementColors.tempBasal
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = color.copy(alpha = 0.2f),
        modifier = modifier.height(AapsSpacing.chipHeight)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            modifier = Modifier.padding(horizontal = AapsSpacing.medium)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
