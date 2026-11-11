package io.github.alita23333.wattscope

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun pageSpacing(): Dp {
    val settings = (LocalContext.current.applicationContext as WattApp).settings
    return remember(LocalSettingsRevision.current) {
        if (settings.bool("compact")) 12.dp else 16.dp
    }
}

@Composable
fun WSnackbar(data: SnackbarData) {
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                data.visuals.message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            data.visuals.actionLabel?.let { label ->
                WTextButton(onClick = data::performAction) { Text(label) }
            }
        }
    }
}

val MiuiTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 25.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 22.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Medium,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 21.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Medium,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Medium,
            ),
        bodyLarge =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
        labelMedium =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
        labelSmall =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 17.sp),
    )

@Composable
fun WTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
) {
    top.yukonga.miuix.kmp.basic.TextField(
        value,
        onValueChange,
        modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        label = label.ifBlank { placeholder },
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        useLabelAsPlaceholder = label.isBlank(),
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        leadingIcon =
            leadingIcon?.let { icon ->
                { Box(Modifier.padding(start = 16.dp, end = 8.dp)) { icon() } }
            },
    )
}

@Composable
fun WDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .88f).dp
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier =
                Modifier.padding(24.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
            color = MaterialTheme.colorScheme.surface,
            cornerRadius = 28.dp,
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalTextStyle provides MiuiTypography.titleLarge) {
                        title()
                    }
                }
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { text() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dismissButton != null)
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalDialogAction provides 2) {
                                dismissButton()
                            }
                        }
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalDialogAction provides 1) { confirmButton() }
                    }
                }
            }
        }
    }
}

@Composable
fun WDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val edge = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position =
        remember(edge) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x =
                        anchorBounds.left.coerceIn(
                            edge,
                            (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge),
                        )
                    val below = anchorBounds.bottom + edge
                    val y =
                        if (below + popupContentSize.height <= windowSize.height - edge) below
                        else (anchorBounds.top - popupContentSize.height - edge).coerceAtLeast(edge)
                    return IntOffset(x, y)
                }
            }
        }
    Popup(position, onDismissRequest, PopupProperties(focusable = true)) {
        Card(
            color = MaterialTheme.colorScheme.surface,
            cornerRadius = 18.dp,
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(
                Modifier.widthIn(min = 180.dp, max = 280.dp)
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
                content = content,
            )
        }
    }
}

@Composable
fun WDropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides MiuiTypography.bodyLarge,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            text()
        }
    }
}

@Composable
fun WIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun WCircularProgressIndicator(modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.CircularProgressIndicator(modifier = modifier)
}

@Composable
fun WLinearProgressIndicator(modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.LinearProgressIndicator(modifier = modifier)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit = {},
) {
    androidx.compose.material3.RangeSlider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = Modifier.fillMaxWidth(),
        colors =
            SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    )
}
