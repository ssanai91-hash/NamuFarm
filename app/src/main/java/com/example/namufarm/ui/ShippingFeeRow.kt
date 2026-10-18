package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.domain.order.ShippingCharge
import java.util.Locale

@Composable
internal fun ShippingFeeRow(
    charge: ShippingCharge,
    onChange: (ShippingCharge) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("택배비", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false)
            listOf(false to "후불", true to "선불").forEach { (prepaid, title) ->
                val chosen = prepaid == charge.isPrepaid
                OutlinedButton(
                    onClick = { onChange(charge.select(prepaid)) },
                    modifier = Modifier.weight(1f).height(44.dp).semantics { selected = chosen },
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (chosen) Color(0xFFE8F5E9) else Color.White,
                        contentColor = if (chosen) Color(0xFF2E7D32) else Color.DarkGray
                    ),
                    border = BorderStroke(if (chosen) 1.5.dp else 1.dp,
                        if (chosen) Color(0xFF2E7D32) else Color(0xFFBDBDBD))
                ) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false)
                }
            }
            OutlinedIconButton(
                onClick = { onChange(charge.decrease()) },
                enabled = charge.isPrepaid && charge.fee > 0,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Remove, "택배비 500원 내리기", modifier = Modifier.size(20.dp))
            }
            Surface(
                modifier = Modifier.weight(1.4f).semantics { if (!charge.isPrepaid) disabled() },
                shape = RoundedCornerShape(8.dp),
                color = if (charge.isPrepaid) Color(0xFFE3F2FD) else Color(0xFFEEEEEE)
            ) {
                Text(String.format(Locale.KOREA, "%,d원", charge.fee),
                    modifier = Modifier.padding(vertical = 10.dp),
                    textAlign = TextAlign.Center, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (charge.isPrepaid) Color(0xFF1565C0) else Color(0xFF888888),
                    maxLines = 1, softWrap = false)
            }
            OutlinedIconButton(
                onClick = { onChange(charge.increase()) },
                enabled = charge.isPrepaid && charge.fee < ShippingCharge.MAX_FEE,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, "택배비 500원 올리기", modifier = Modifier.size(20.dp))
            }
        }
    }
}
