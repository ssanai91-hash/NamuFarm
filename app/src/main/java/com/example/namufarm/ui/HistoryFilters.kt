package com.example.namufarm.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val historyZone: TimeZone get() = TimeZone.getTimeZone("Asia/Seoul")
private val historyPeriods = listOf("today" to "오늘", "week" to "최근 7일", "month" to "이번 달",
    "year" to "올해", "custom" to "직접 선택", "all" to "전체 기간")

internal fun historyDay(stamp: Long): String? = if (stamp <= 0) null else
    SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { timeZone = historyZone }.format(Date(stamp))

internal fun historyDate(value: String): String? {
    val day = value.take(10)
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) return null
    return runCatching {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply {
            timeZone = historyZone; isLenient = false
        }
        format.parse(day)?.let { format.format(it) }?.takeIf { it == day }
    }.getOrNull()
}

internal fun historyRange(key: String, customFrom: String = "", customTo: String = ""): Pair<String?, String?> {
    val calendar = Calendar.getInstance(historyZone)
    val end = historyDay(calendar.timeInMillis)!!
    when (key) {
        "all" -> return null to null
        "custom" -> return historyDate(customFrom) to historyDate(customTo)
        "week" -> calendar.add(Calendar.DAY_OF_MONTH, -6)
        "month" -> calendar.set(Calendar.DAY_OF_MONTH, 1)
        "year" -> { calendar.set(Calendar.DAY_OF_YEAR, 1) }
    }
    return historyDay(calendar.timeInMillis) to end
}

internal fun historyMatches(day: String?, key: String, from: String, to: String): Boolean {
    if (key == "all") return true
    val (start, end) = historyRange(key, from, to)
    return day != null && start != null && end != null && day >= start && day <= end
}

internal fun historySearch(query: String, vararg values: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    if (values.any { it.contains(trimmed, ignoreCase = true) }) return true
    val digits = trimmed.filter(Char::isDigit)
    return digits.isNotEmpty() && trimmed.all { it.isDigit() || it in " -()" } &&
        values.any { it.filter(Char::isDigit).contains(digits) }
}

/** Display-only filter: stored records and synchronization are never restricted. */
@Composable
internal fun HistoryFilters(
    search: String = "", onSearch: ((String) -> Unit)? = null,
    period: String, from: String, to: String, status: String = "",
    statuses: List<String> = emptyList(), count: Int, basis: String,
    defaultPeriod: String = "month", defaultStatus: String = "",
    onApply: (String, String, String, String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val (start, end) = historyRange(period, from, to)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onSearch != null) OutlinedTextField(search, onSearch,
                placeholder = { Text("고객·전화·번호 검색") }, singleLine = true, modifier = Modifier.weight(1f))
            else Text("조회 기간", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { keyboard?.hide(); open = true }) { Text("필터") }
        }
        Text("총 ${count}건 · ${if (period == "all") "전체 기간" else "$start ~ $end"}" +
            (if (status.isBlank()) "" else " · $status") + " · $basis",
            style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        var picked by remember { mutableStateOf(period) }
        var pickedStatus by remember { mutableStateOf(status) }
        var pickedFrom by remember { mutableStateOf(start ?: historyDay(System.currentTimeMillis())!!) }
        var pickedTo by remember { mutableStateOf(end ?: historyDay(System.currentTimeMillis())!!) }
        val context = LocalContext.current
        fun chooseDate(value: String, onDate: (String) -> Unit) {
            val parts = value.split("-").map { it.toInt() }
            DatePickerDialog(context, { _, year, month, day ->
                onDate(String.format(Locale.KOREA, "%04d-%02d-%02d", year, month + 1, day))
            }, parts[0], parts[1] - 1, parts[2]).show()
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("조회 필터") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    historyPeriods.chunked(2).forEach { choices ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            choices.forEach { (key, title) ->
                                FilterChip(selected = picked == key, onClick = { picked = key },
                                    label = { Text(title) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (picked == "custom") {
                        OutlinedButton(onClick = { chooseDate(pickedFrom) { pickedFrom = it } }, modifier = Modifier.fillMaxWidth()) {
                            Text("시작일 $pickedFrom")
                        }
                        OutlinedButton(onClick = { chooseDate(pickedTo) { pickedTo = it } }, modifier = Modifier.fillMaxWidth()) {
                            Text("종료일 $pickedTo")
                        }
                        if (pickedFrom > pickedTo) Text("종료일은 시작일 이후로 선택해주세요.")
                    }
                    statuses.chunked(2).forEach { choices ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            choices.forEach { value ->
                                FilterChip(selected = pickedStatus == value, onClick = {
                                    pickedStatus = value
                                    if (value == "미배송") picked = "all"
                                    if (value == "배송완료") picked = "month"
                                }, label = { Text(value) })
                            }
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = {
                    onApply(picked, pickedFrom, pickedTo, pickedStatus); open = false
                }, enabled = picked != "custom" || pickedFrom <= pickedTo) { Text("적용") }
            }, dismissButton = {
                Row {
                    TextButton(onClick = {
                        onSearch?.invoke("")
                        onApply(defaultPeriod, "", "", defaultStatus); open = false
                    }) { Text("초기화") }
                    TextButton(onClick = { open = false }) { Text("닫기") }
                }
            })
    }
}
