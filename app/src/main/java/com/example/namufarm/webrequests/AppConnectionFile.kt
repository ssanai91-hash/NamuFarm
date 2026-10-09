package com.example.namufarm.webrequests

import org.json.JSONObject

/** Shared with the PC app; deliberately does not include credentials in toString(). */
class AppConnectionFile private constructor(val url: String, val connectionKey: String) {
    companion object {
        fun validUrl(url: String) = Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec").matches(url)
        fun parse(bytes: ByteArray): AppConnectionFile {
            require(bytes.size <= 8192) { "연결파일 크기를 확인해주세요." }
            val value = try { JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) }
                catch (_: Exception) { error("나무팜 연결파일을 선택해주세요.") }
            require(value.optString("format") == "namufarm-connection" && value.opt("version") == 1) { "나무팜 연결파일을 선택해주세요." }
            val url = value.optString("url")
            val key = value.optString("connectionKey")
            require(validUrl(url) && Regex("[a-f0-9]{64}").matches(key)) { "연결파일이 올바르지 않습니다." }
            return AppConnectionFile(url, key)
        }
    }
}
