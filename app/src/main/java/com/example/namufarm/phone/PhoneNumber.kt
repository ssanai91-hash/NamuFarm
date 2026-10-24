package com.example.namufarm.phone

fun normalizePhoneNumber(raw: String): String? {
    val compact = raw.trim().replace(Regex("[\\s()-]"), "")
    val number = if (compact.startsWith("+82")) "0" + compact.drop(3) else compact
    return number.takeIf { Regex("0[0-9]{8,10}").matches(it) }
}
