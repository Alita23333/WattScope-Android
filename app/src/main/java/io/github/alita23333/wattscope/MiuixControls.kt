package io.github.alita23333.wattscope

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiButtonDefaults

internal val LocalDialogAction = compositionLocalOf { 0 }

@Composable
fun WButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MiButton(
        onClick = onClick,
        modifier = if (LocalDialogAction.current != 0) modifier.fillMaxWidth() else modifier,
        enabled = enabled,
        colors = MiButtonDefaults.buttonColorsPrimary(),
        cornerRadius = 16.dp,
        minHeight = 42.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides androidx.compose.ui.graphics.Color.White
        ) {
            content()
        }
    }
}

@Composable
fun WSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MiButton(
        onClick = onClick,
        modifier = if (LocalDialogAction.current != 0) modifier.fillMaxWidth() else modifier,
        enabled = enabled,
        cornerRadius = 14.dp,
        minHeight = 38.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
fun WTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogAction.current != 0) {
        if (LocalDialogAction.current == 1)
            WButton(onClick, modifier.fillMaxWidth(), enabled, content)
        else WSecondaryButton(onClick, modifier.fillMaxWidth(), enabled, content)
        return
    }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 32.dp)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .4f)
        ) {
            content()
        }
    }
}

@Composable
fun WChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MiButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
            if (selected) MiButtonDefaults.buttonColorsPrimary()
            else MiButtonDefaults.buttonColors(),
        cornerRadius = 10.dp,
        minHeight = 32.dp,
        minWidth = 36.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                if (selected) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurface
        ) {
            label()
        }
    }
}

@Composable
fun WSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    decimalPlaces: Int = 0,
) {
    top.yukonga.miuix.kmp.basic.Slider(
        progress = value,
        onProgressChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        minValue = valueRange.start,
        maxValue = valueRange.endInclusive,
        decimalPlaces = decimalPlaces,
    )
}
