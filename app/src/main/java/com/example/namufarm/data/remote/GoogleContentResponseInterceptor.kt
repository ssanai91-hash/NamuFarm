package com.example.namufarm.data.remote

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/** Retry the already-created ContentService response, never the original POST. */
internal class GoogleContentResponseInterceptor(
    private val waitForResponse: (Long) -> Unit = { Thread.sleep(it) }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        var response = chain.proceed(original)
        if (!isScriptEndpoint(original)) return response

        // Apps Script first commits the action, then redirects to a temporary GET URL.
        // That response can briefly be unavailable while Google's content layer propagates it.
        // Retrying the POST here would risk repeating a completed business operation.
        val contentRequest = response.request()
        if (response.code() == 404 && isContentResponse(contentRequest) && response.priorResponse() != null) {
            val retry = Request.Builder().url(contentRequest.url()).get()
                .header("Accept", "application/json")
                .cacheControl(CacheControl.FORCE_NETWORK).build()
            for (delay in longArrayOf(2000, 3000)) {
                response.close()
                try {
                    if (chain.call().isCanceled()) throw IOException("요청을 취소했습니다.")
                    waitForResponse(delay)
                    if (chain.call().isCanceled()) throw IOException("요청을 취소했습니다.")
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("요청을 취소했습니다.")
                }
                response = chain.proceed(retry)
                if (response.code() != 404 || response.request().url() != contentRequest.url()) break
            }
        }
        if (response.code() == 404) {
            val message = if (isContentResponse(response.request())) {
                "Google 응답 조회가 지연되고 있습니다(HTTP 404). 잠시 후 다시 시도해주세요."
            } else {
                "운영 서버 주소를 찾지 못했습니다(HTTP 404). 최신 설치 파일과 웹 앱 배포 주소를 확인해주세요."
            }
            response.close()
            throw IOException(message)
        }
        return response
    }

    private fun isScriptEndpoint(request: Request): Boolean {
        val url = request.url()
        return url.isHttps && url.host() == "script.google.com" &&
            Regex("/macros/s/[A-Za-z0-9_-]+/exec").matches(url.encodedPath())
    }

    private fun isContentResponse(request: Request): Boolean {
        val url = request.url()
        return request.method() == "GET" && url.isHttps && url.host() == "script.googleusercontent.com" &&
            url.encodedPath() == "/macros/echo" &&
            !url.queryParameter("user_content_key").isNullOrBlank() && !url.queryParameter("lib").isNullOrBlank()
    }
}
