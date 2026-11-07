package com.omniroute.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omniroute.app.ui.theme.ClayTheme

/**
 * Raised clay card with bevel edges. The inset-bevel look is approximated with
 * two stacked borders: an outer top-light stroke (+12% white) and an inner
 * bottom-dark stroke (+30% black). Look is tuned later from screenshots.
 */
@Composable
fun ClayCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = ClayTheme.colors
    val elevation = ClayTheme.elevation
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation.dp,
                shape,
                spotColor = Color.Black.copy(alpha = 0.5f),
                ambientColor = Color.Black.copy(alpha = 0.5f),
            )
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .border(1.dp, Color.Black.copy(alpha = 0.30f), shape)
            .padding(16.dp),
    ) {
        Column(content = content)
    }
}

/** Material3 Switch with cyan track/thumb per the mockup. No hand-rolled gesture code. */
@Composable
fun ClayToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.primary,
            checkedTrackColor = colors.primary.copy(alpha = 0.35f),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.onSurface,
            uncheckedTrackColor = colors.surface,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun ClayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ClayTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = Color(0xFF06222B),
        ),
        content = content,
    )
}

/**
 * Chip that reads as recessed (inset) when [selected], raised otherwise.
 */
@Composable
fun ClayChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val container = if (selected) colors.primary.copy(alpha = 0.16f) else colors.surface
    val textColor = if (selected) colors.primary else colors.onSurface
    Box(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .background(container)
            .border(
                1.dp,
                if (selected) colors.primary.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.30f),
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Inset container — the negative of [ClayCard]. */
@Composable
fun RecessedWell(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = ClayTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.background.copy(alpha = 0.6f))
            .border(1.dp, colors.primary.copy(alpha = 0.15f), shape)
            .padding(12.dp),
        content = content,
    )
}
