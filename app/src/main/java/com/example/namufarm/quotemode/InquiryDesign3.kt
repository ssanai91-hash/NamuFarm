package com.example.namufarm.quotemode

import com.example.namufarm.ui.theme.*
import com.example.namufarm.quotemode.components.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.remote.CustomerInquiryRemote
import com.example.namufarm.data.remote.inquiryLocalTime

/** The selected colorful outline design, scoped to inquiries and mode navigation. */
internal object InquiryDesign {
    val ink = NamuColors.text
    val muted = NamuColors.muted
    val green = ModeDesign.inquiry
    val mint = NamuColors.soft
    val blue = Color(0xFF0969C1)
    val sky = Color(0xFFEFF6FF)
    val amber = Color(0xFFA85900)
    val honey = Color(0xFFFFF7E6)
    val coral = NamuColors.primary
    val canvas = NamuColors.background
    val line = NamuColors.border
    val shape = RoundedCornerShape(14.dp)
    fun status(value: String?) = if (value == "closed") "완료" else "미처리"
    fun categoryColor(value: String?) = when (value) {
        "묘목·재배" -> green
        "구매·배송" -> blue
        else -> coral
    }
    fun categoryIcon(value: String?): ImageVector = when (value) {
        "묘목·재배" -> Icons.Outlined.Spa
        "구매·배송" -> Icons.Outlined.LocalShipping
        else -> Icons.AutoMirrored.Outlined.Chat
    }
}

@Composable
internal fun inquiryFieldColors(color: Color, tint: Color = Color.White) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = color, unfocusedBorderColor = color, cursorColor = color,
    focusedContainerColor = tint, unfocusedContainerColor = tint,
    focusedTextColor = InquiryDesign.ink, unfocusedTextColor = InquiryDesign.ink,
    focusedPlaceholderColor = InquiryDesign.muted, unfocusedPlaceholderColor = InquiryDesign.muted)

@Composable
internal fun InquiryChoice(label: String, count: Int, selected: Boolean, color: Color,
                           tint: Color, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(color = if (selected) InquiryDesign.mint else Color.White, shape = InquiryDesign.shape,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) ModeDesign.inquiry else ModeDesign.line), modifier = modifier) {
        Column(Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 50.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, tint = if (selected) ModeDesign.inquiry else InquiryDesign.muted, modifier = Modifier.size(16.dp))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (selected) ModeDesign.inquiry else InquiryDesign.muted)
            }
            Text("${count}건", fontSize = 13.sp, color = if (selected) ModeDesign.inquiry else InquiryDesign.muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
internal fun InquiryStatus(status: String?) {
    val closed = status == "closed"
    val color = if (closed) InquiryDesign.green else InquiryDesign.amber
    Surface(color = if (closed) InquiryDesign.mint else InquiryDesign.honey,
        border = BorderStroke(1.dp, color), shape = RoundedCornerShape(7.dp)) {
        Text(InquiryDesign.status(status), Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun InquiryListCard(row: CustomerInquiryRemote, pending: Boolean, hasDraft: Boolean = false, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val customer = row.inquiry?.customerName.orEmpty().ifBlank { "보관 종료" }
    val category = row.inquiry?.category.orEmpty().ifBlank { "기타" }
    val at = runCatching { com.example.namufarm.data.remote.inquiryTimestamp(row.createdAt) }.getOrDefault(0L)
    if (row.status == "closed") {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color(0xFFFAFBFA),
            shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, NamuColors.border)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NamuStatusBadge(category)
                val channel = if (row.delivery?.channel == "email") "메일" else "문자"
                Text("$customer · " + if (row.delivery?.state in setOf("sent", "success")) "${channel}로 답변 완료" else "처리 완료",
                    Modifier.weight(1f), fontSize = 15.sp)
                Text(shortNamuTime(runCatching { com.example.namufarm.data.remote.inquiryTimestamp(row.answeredAt ?: row.updatedAt) }
                    .getOrDefault(at)), fontSize = 13.sp, color = NamuColors.muted)
            }
        }
        return
    }
    NamuCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NamuStatusBadge(category)
                Text(shortNamuTime(at), Modifier.weight(1f), fontSize = 13.sp, color = NamuColors.muted)
                val checking = pending || row.blockedDelivery
                Text(if (checking) "결과 확인 중" else if (hasDraft) "초안 저장됨" else "답변 대기",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (checking) Color(0xFF1D4F91) else if (hasDraft) Color(0xFF8A5300) else NamuColors.danger)
            }
            Text(customer, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(row.inquiry?.message.orEmpty(), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,
                        android.net.Uri.fromParts("tel", row.inquiry?.customerPhone.orEmpty(), null))) }
                        .onFailure { android.widget.Toast.makeText(context, "전화 앱을 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show() }
                }, enabled = !row.inquiry?.customerPhone.isNullOrBlank(),
                    modifier = Modifier.size(44.dp).background(NamuColors.soft, RoundedCornerShape(12.dp))) {
                    Icon(Icons.Outlined.Phone, "전화", tint = NamuColors.primary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(row.inquiry?.customerPhone.orEmpty())) },
                    enabled = !row.inquiry?.customerPhone.isNullOrBlank(),
                    modifier = Modifier.size(44.dp).background(NamuColors.soft, RoundedCornerShape(12.dp))) {
                    Icon(Icons.Outlined.ContentCopy, "연락처 복사", tint = NamuColors.primary, modifier = Modifier.size(20.dp))
                }
                NamuPrimaryButton(if (hasDraft) "이어서 답변" else "답변하기", onClick, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun InquiryEmpty(noRecords: Boolean) {
    Surface(color = Color.White, shape = InquiryDesign.shape, border = BorderStroke(1.2.dp, InquiryDesign.line),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = InquiryDesign.sky, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, InquiryDesign.blue)) {
                Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = InquiryDesign.blue,
                    modifier = Modifier.padding(12.dp).size(30.dp))
            }
            Text(if (noRecords) "고객문의를 불러와주세요" else "조회 결과가 없습니다", fontSize = 18.sp,
                fontWeight = FontWeight.Bold, color = InquiryDesign.ink)
            Text(if (noRecords) "상단 불러오기 버튼으로 문의를 확인하세요." else "검색어 또는 처리 상태를 바꿔보세요.",
                fontSize = 14.sp, color = InquiryDesign.muted)
        }
    }
}

@Composable
internal fun InquirySendButton(label: String, icon: ImageVector, color: Color, enabled: Boolean,
                               modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = InquiryDesign.shape, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.12f), disabledContentColor = color.copy(alpha = 0.7f))) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun InquiryChannel(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean,
                            color: Color, modifier: Modifier, onClick: () -> Unit) {
    val content = if (enabled) color else InquiryDesign.muted
    Surface(color = if (selected) color.copy(alpha = 0.07f) else Color.White,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) content else InquiryDesign.line),
        shape = RoundedCornerShape(7.dp), modifier = modifier) {
        Row(Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                null, tint = content, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp)); Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp)); Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

