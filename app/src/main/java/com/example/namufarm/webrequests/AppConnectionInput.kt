package com.example.namufarm.webrequests

/** Manual credentials; never expose the common key through a data-class toString. */
class AppConnectionInput private constructor(val url: String, val connectionKey: String) {
    companion object {
        fun validUrl(url: String) = Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec").matches(url)
        fun validKey(key: String) = Regex("[a-f0-9]{64}").matches(key)
        fun fromInput(url: String, key: String): AppConnectionInput {
            val endpoint = url.trim()
            val password = key.trim()
            require(validUrl(endpoint)) { "나무팜 서버의 HTTPS /exec 주소를 확인해주세요." }
            require(validKey(password)) { "공통 연결 암호를 확인해주세요. 현재 연결키 64자리를 입력합니다." }
            return AppConnectionInput(endpoint, password)
        }
    }
}
