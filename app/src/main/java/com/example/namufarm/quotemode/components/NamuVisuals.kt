package com.example.namufarm.quotemode.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.parseSpeciesCategory
import com.example.namufarm.data.suggestSpeciesPurposeLabels

/*
 * 색 아이콘(2026-10-31 사용자 확정 시안 2안). Decoration only: no data is read or written beyond names/categories.
 * Text keeps its existing size and dark colour; colour lives in the icon tiles.
 */

/** Stroke-only line icon from SVG path data (24×24), tinted by Icon(). */
private fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

private val FruitIcon = lineIcon("fruit",
    "M12 6.5c-1.5-1.2-4.6-1.6-6.2.6-2 2.8-.8 8 1.7 10.6 1.3 1.4 2.8 1.6 4.5.8 1.7.8 3.2.6 4.5-.8 2.5-2.6 3.7-7.8 1.7-10.6-1.6-2.2-4.7-1.8-6.2-.6z",
    "M12 6.5c0-1.8.7-3.2 2.3-4")
private val FlowerIcon = lineIcon("flower", "M12 9m-2.2 0a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0",
    "M12 6.8c0-2 1-3.3 2.4-3.3S17 5 15.7 6.6M14.2 9c2 0 3.3 1 3.3 2.4s-1.5 2.6-3.1 1.3M9.8 9c-2 0-3.3 1-3.3 2.4s1.5 2.6 3.1 1.3M12 6.8c0-2-1-3.3-2.4-3.3S7 5 8.3 6.6M12 11.2V21")
private val HedgeIcon = lineIcon("hedge", "M4 21V7l2-3 2 3v14M10 21V7l2-3 2 3v14M16 21V7l2-3 2 3v14M3 11h18M3 17h18")
private val ShadeIcon = lineIcon("shade", "M8 19h8M12 19v3M7.5 16a4.5 4.5 0 0 1-1.6-8.7A6 6 0 0 1 17.6 6a4.5 4.5 0 0 1-1.1 10z")
private val GardenIcon = lineIcon("garden", "M10 10v.2A3 3 0 0 1 8.9 16H5a3 3 0 0 1-1-5.8V10a3 3 0 0 1 6 0zM7 16v6M13 19v3",
    "M12 19h8.3a1 1 0 0 0 .7-1.7L18 14h.3a1 1 0 0 0 .7-1.7L16 9h.2a1 1 0 0 0 .8-1.7L13 3l-1.4 1.5")
private val LawnIcon = lineIcon("lawn", "M7 20h10M10 20c5.5-2.5.8-6.4 3-10",
    "M9.5 9.4c1.1.8 1.8 2.2 2.3 3.7-2 .4-3.5.4-4.8-.3-1.2-.6-2.3-1.9-3-4.2 2.8-.5 4.4 0 5.5.8z")
private val TreeIcon = lineIcon("tree", "M12 3 7 10h3l-4 6h12l-4-6h3zM12 16v5")
private val SproutIcon = lineIcon("sprout", "M12 21v-8M12 13c0-4 3-6 7-6 0 4-3 6-7 6zM12 15c0-3-2.5-5-6-5 0 3 2.5 5 6 5z")

internal class NamuVisual(val background: Color, val ink: Color, val icon: ImageVector)

/** 묘목 용도(홈페이지와 같은 분류)별 색. 용도가 없으면 회녹색 나무. */
private val PURPOSE_VISUALS = mapOf(
    "열매" to NamuVisual(Color(0xFFFBE5D8), Color(0xFFC2410C), FruitIcon),
    "꽃" to NamuVisual(Color(0xFFFBE3EB), Color(0xFFBE185D), FlowerIcon),
    "울타리" to NamuVisual(Color(0xFFE1EFDD), Color(0xFF2F6B2F), HedgeIcon),
    "그늘" to NamuVisual(Color(0xFFDDEDE4), Color(0xFF1E6B4A), ShadeIcon),
    "정원" to NamuVisual(Color(0xFFE1ECF5), Color(0xFF1D4F91), GardenIcon),
    "잔디" to NamuVisual(Color(0xFFECF3D5), Color(0xFF4D7C0F), LawnIcon))
private val DEFAULT_PURPOSE = NamuVisual(Color(0xFFEEF1EE), Color(0xFF4A5A52), TreeIcon)

/** Saved category first; otherwise the same name list the editor uses for suggestions ("감(대봉)" → 감). */
internal fun speciesVisual(name: String, category: String? = null): NamuVisual {
    val label = parseSpeciesCategory(category).selectedLabels.firstOrNull()
        ?: suggestSpeciesPurposeLabels(name.substringBefore('(').trim()).firstOrNull()
    return PURPOSE_VISUALS[label] ?: DEFAULT_PURPOSE
}

/** 업무 단어: same colour and shape wherever the word appears. */
internal enum class NamuConcept(val visual: NamuVisual) {
    QUOTE(NamuVisual(Color(0xFFFDE7D6), Color(0xFFB45309), Icons.AutoMirrored.Outlined.ReceiptLong)),
    INQUIRY(NamuVisual(Color(0xFFE0EDF9), Color(0xFF1D4F91), Icons.AutoMirrored.Outlined.Chat)),
    SALE(NamuVisual(Color(0xFFF7E1EC), Color(0xFF9D174D), Icons.Outlined.Handshake)),
    FARM(NamuVisual(Color(0xFFDDF0EE), Color(0xFF0B5F56), Icons.Outlined.Storefront)),
    SPECIES(NamuVisual(Color(0xFFE6F2DF), Color(0xFF2F6B2F), SproutIcon)),
    STOCK(NamuVisual(Color(0xFFFDF1D2), Color(0xFF8A5300), Icons.Outlined.Inventory2))
}

@Composable
internal fun NamuIconTile(visual: NamuVisual, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(visual.background, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        Icon(visual.icon, null, Modifier.size(size * 0.55f), tint = visual.ink)
    }
}

@Composable
internal fun NamuSpeciesTile(name: String, category: String? = null, size: Dp = 44.dp) =
    NamuIconTile(speciesVisual(name, category), size)

private val AVATAR_COLORS = listOf(Color(0xFFFDE7D6) to Color(0xFFB45309), Color(0xFFE0EDF9) to Color(0xFF1D4F91),
    Color(0xFFE6F2DF) to Color(0xFF2F6B2F), Color(0xFFF7E1EC) to Color(0xFF9D174D),
    Color(0xFFEDE7F8) to Color(0xFF5B3C9E), Color(0xFFDDF0EE) to Color(0xFF0B5F56))

/** 농원 첫 글자 동그라미. The colour is derived from the name, so a farm always keeps its colour. */
@Composable
internal fun NamuFarmAvatar(name: String, size: Dp = 44.dp) {
    val (bg, ink) = AVATAR_COLORS[Math.floorMod(name.fold(0) { h, c -> h * 31 + c.code }, AVATAR_COLORS.size)]
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.ExtraBold, color = ink)
    }
}

/** Small concept icon + text, e.g. [상자] 270주. */
@Composable
internal fun NamuCountChip(concept: NamuConcept, text: String, color: Color = Color(0xFF4A5A52), bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NamuIconTile(concept.visual, 20.dp)
        Text(text, fontSize = 14.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

/** 화면 위 요약 띠. */
@Composable
internal fun NamuSummaryBand(visual: NamuVisual, title: String, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().background(visual.background.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(Color.White, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(visual.icon, null, Modifier.size(24.dp), tint = visual.ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = NamuColorsText)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

private val NamuColorsText = Color(0xFF1F2A26)
