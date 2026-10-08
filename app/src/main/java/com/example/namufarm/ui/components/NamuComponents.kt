package com.example.namufarm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.namufarm.domain.order.OrderLineSource
import java.util.Locale

internal fun money(amount: Long): String = String.format(Locale.KOREA, "%,d원", amount)
internal fun shippingLabel(isPrepaid: Boolean, fee: Int): String =
    if (isPrepaid) "선불 · ${money(fee.toLong())}" else "착불"

/** A single scrollable body with a fixed, keyboard-aware action area. */
@Composable
internal fun NamuDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.96f).imePadding().testTag("namu_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                NamuDialogHeader(title, subtitle, onDismiss)
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                if (snackbarHostState != null) SnackbarHost(snackbarHostState)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions
                    )
                }
            }
        }
    }
}

@Composable
internal fun NamuDialogHeader(title: String, subtitle: String, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "닫기") }
        }
    }
}

@Composable
internal fun NamuSectionTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun NamuBadge(text: String, manual: Boolean = false, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when { error -> colors.errorContainer; manual -> colors.tertiaryContainer; else -> colors.primaryContainer },
        contentColor = when { error -> colors.onErrorContainer; manual -> colors.onTertiaryContainer; else -> colors.onPrimaryContainer }
    ) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun SourceLabel(source: OrderLineSource, farm: String) {
    val manual = source == OrderLineSource.MANUAL
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NamuBadge(if (manual) "수기 견적" else farm.ifBlank { "기존 재고 품목" }, manual = manual)
        if (manual) Text(
            "실제 재고 차감·복원 없음",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun NamuEmptyState(title: String, description: String, icon: ImageVector = Icons.Outlined.Inventory2) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(18.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ChosungPicker(
    selected: String,
    onSelect: (String) -> Unit,
    includeAll: Boolean = false,
    available: (String) -> Boolean = { true }
) {
    val initials = listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(if (includeAll) listOf("전체") + initials else initials) { initial ->
            FilterChip(
                selected = initial == selected,
                onClick = { onSelect(initial) },
                enabled = initial == "전체" || available(initial),
                label = { Text(initial, fontWeight = FontWeight.Bold) },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp)
            )
        }
    }
}

@Composable
internal fun AmountSummary(total: Long, quantity: Long, isPrepaid: Boolean, shippingFee: Int, label: String = "합계") {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("총 ${String.format(Locale.KOREA, "%,d", quantity)}주", style = MaterialTheme.typography.bodyMedium)
                Text("배송 ${shippingLabel(isPrepaid, shippingFee)}", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(money(total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
internal fun CommerceItemCard(
    species: String,
    spec: String,
    quantity: Int,
    price: Int,
    wholesale: Int,
    farm: String,
    source: OrderLineSource,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(species, style = MaterialTheme.typography.titleMedium)
                    if (spec.isNotBlank()) Text(spec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trailing?.invoke()
            }
            SourceLabel(source, farm)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("${quantity}주 × ${money(price.toLong())}", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (source == OrderLineSource.INVENTORY) "도매 ${money(wholesale.toLong())}" else "수기 입력 단가",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(money(quantity.toLong() * price), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
