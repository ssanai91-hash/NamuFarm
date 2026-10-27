package com.example.namufarm.quotemode.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.ui.unit.sp
import com.example.namufarm.ui.theme.NamuColors
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
import androidx.compose.ui.graphics.Color
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
    compact: Boolean = false,
    documentStyle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(if (documentStyle) 1f else 0.96f).imePadding().testTag("namu_dialog"),
            shape = if (documentStyle) RoundedCornerShape(12.dp) else MaterialTheme.shapes.extraLarge,
            color = if (documentStyle) Color.White else MaterialTheme.colorScheme.background
        ) {
            Column {
                NamuDialogHeader(title, subtitle, onDismiss, compact = compact, documentStyle = documentStyle)
                if (documentStyle) HorizontalDivider(color = Color(0xFFE2E6ED))
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                if (snackbarHostState != null) SnackbarHost(snackbarHostState)
                Surface(color = if (documentStyle) Color.White else MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (documentStyle) HorizontalDivider(color = Color(0xFFE2E6ED))
                        actions()
                    }
                }
            }
        }
    }
}

@Composable
internal fun NamuDialogHeader(title: String, subtitle: String, onClose: () -> Unit, compact: Boolean = false,
                              documentStyle: Boolean = false) {
    Surface(color = if (documentStyle) Color.White else MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 20.dp,
                top = if (compact) 6.dp else 12.dp, end = 8.dp, bottom = if (compact) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = if (documentStyle) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = if (documentStyle) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = if (documentStyle) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun NamuEmptyState(title: String, description: String = "", icon: ImageVector = Icons.Outlined.Inventory2) {
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




/** Shared presentation components; they never save, confirm or sync records themselves. */
@Composable
internal fun NamuCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    if (onClick == null) Surface(modifier, shape = shape, color = NamuColors.surface,
        border = BorderStroke(1.dp, NamuColors.border)) {
        Column(Modifier.fillMaxWidth(), content = content)
    } else Surface(onClick = onClick, modifier = modifier, shape = shape,
        color = NamuColors.surface, border = BorderStroke(1.dp, NamuColors.border)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun NamuPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: ImageVector? = null) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled,
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NamuColors.primary)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun NamuSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick, modifier.heightIn(min = 52.dp), enabled = enabled,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.5.dp, NamuColors.primary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

internal data class NamuSegment(val label: String, val count: Int? = null, val countColor: Color = NamuColors.primary)

@Composable
internal fun NamuSegmented(options: List<NamuSegment>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color(0xFFEEF2EF), RoundedCornerShape(12.dp))
        .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            Surface(onClick = { onSelect(index) }, modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                shape = RoundedCornerShape(9.dp), color = if (active) Color.White else Color.Transparent,
                shadowElevation = if (active) 1.dp else 0.dp) {
                Row(Modifier.padding(horizontal = 3.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(option.label, fontSize = 15.sp, color = if (active) NamuColors.text else Color(0xFF4A5A52),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false))
                    option.count?.let { Text("$it", Modifier.padding(start = 3.dp), fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, color = option.countColor) }
                }
            }
        }
    }
}

@Composable
internal fun NamuTopBar(title: String, syncText: String? = null, onSync: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = Color.White) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NamuColors.text)
            actions()
            syncText?.let { Text(it, fontSize = 13.sp, color = NamuColors.muted) }
            onSync?.let { sync -> IconButton(onClick = sync, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.CloudSync, "동기화", Modifier.size(24.dp), tint = NamuColors.primary)
            } }
        }
    }
}

@Composable
internal fun NamuCallButton(phone: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    val number = android.telephony.PhoneNumberUtils.normalizeNumber(phone)
    OutlinedIconButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
            .onFailure { Toast.makeText(context, "전화 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show() }
    }, modifier = modifier.size(48.dp), enabled = enabled && number.isNotBlank(),
        border = BorderStroke(1.5.dp, NamuColors.primary)) {
        Icon(Icons.Outlined.Phone, "전화", Modifier.size(20.dp), tint = NamuColors.primary)
    }
}

@Composable
internal fun NamuStatusBadge(stage: String) {
    val pair = when (stage) {
        "신규", "구매", "배송", "구매·배송" -> Color(0xFF1D4F91) to Color(0xFFE7F0FB)
        "견적확정", "공식견적서", "공식견적서 요청" -> Color(0xFF8A5300) to Color(0xFFFDF1DC)
        "입금확인", "묘목", "재배", "묘목·재배" -> Color(0xFF1E6B3F) to Color(0xFFE3F2E8)
        "배송준비" -> Color(0xFF0B5F56) to Color(0xFFDFF1EF)
        "송장등록" -> Color(0xFF5B3C9E) to Color(0xFFEFE9FB)
        "취소", "오류" -> Color(0xFF9A2A22) to Color(0xFFFBE9E7)
        else -> Color(0xFF46545C) to Color(0xFFECEFF1)
    }
    Surface(color = pair.second, shape = RoundedCornerShape(7.dp)) {
        Text(stage, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pair.first)
    }
}

@Composable
internal fun NamuDashedAdd(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
        val width = 1.5.dp.toPx()
        drawRoundRect(Color(0xFF6FA184), cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
    }, shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun masterSyncLabel(at: Long?): String? = at?.let {
    java.text.SimpleDateFormat("HH:mm", Locale.KOREA).format(java.util.Date(it)) + " 동기화"
}
