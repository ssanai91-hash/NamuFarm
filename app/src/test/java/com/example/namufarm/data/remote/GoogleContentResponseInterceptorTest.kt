package com.example.namufarm.data.remote

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoogleContentResponseInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val delays = mutableListOf<Long>()
    private val path = "/macros/echo?user_content_key=temporary-response-key&lib=test-library"
    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("script.google.com")
            .addSubjectAlternativeName("script.googleusercontent.com").addSubjectAlternativeName("example.test").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = WebQuoteTransport.clientBuilder().apply {
            interceptors().clear()
            addInterceptor(GoogleContentResponseInterceptor { delays += it })
        }.sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager())
            .dns(Dns { listOf(InetAddress.getLoopbackAddress()) }).build()
    }
    @After fun teardown() { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); server.shutdown() }
    private fun url(host: String, path: String) = server.url(path).newBuilder().host(host).build()
    private fun original() = Request.Builder().url(url("script.google.com", "/macros/s/test-deployment/exec"))
        .header("Authorization", "never-forward-this").header("X-Device-Token", "never-forward-this")
        .post(RequestBody.create(MediaType.parse("application/json"), "{\"action\":\"save_sale\",\"eventId\":\"unchanged\"}")).build()
    private fun redirect(host: String = "script.googleusercontent.com", target: String = path) {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url(host, target)))
    }
    @Test fun temporary404RetriesOnlyTheSameResultGetWithoutRepeatingSalePost() {
        redirect()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not propagated"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"saved\":true}"))
        client.newCall(original()).execute().use { assertEquals(200, it.code()); assertTrue(it.body()!!.string().contains("saved")) }
        assertEquals(listOf(2000L, 3000L), delays)
        assertEquals("POST", server.takeRequest().method)
        val redirected = server.takeRequest(); assertEquals("GET", redirected.method); assertEquals(path, redirected.path)
        repeat(2) {
            val retry = server.takeRequest()
            assertEquals("GET", retry.method); assertEquals(path, retry.path); assertEquals(0L, retry.bodySize)
            assertNull(retry.getHeader("Authorization")); assertNull(retry.getHeader("X-Device-Token"))
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun normalRedirectReturnsWithoutRetry() {
        redirect(); server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(original()).execute().use { assertEquals(200, it.code()) }
        assertTrue(delays.isEmpty()); assertEquals(2, server.requestCount)
    }
    @Test fun entryPoint404IsNotRetriedAndExplainsDeploymentAddress() {
        server.enqueue(MockResponse().setResponseCode(404))
        val error = runCatching { client.newCall(original()).execute().close() }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("운영 서버 주소")); assertEquals(1, server.requestCount); assertTrue(delays.isEmpty())
    }
    @Test fun persistentContent404StopsAfterTwoResultRetriesAndDoesNotExposeKeys() {
        redirect(); repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
        val error = runCatching { client.newCall(original()).execute().close() }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("응답 조회")); assertFalse(error.message!!.contains("temporary-response-key"))
        assertEquals(4, server.requestCount)
    }
    @Test fun otherHttpErrorsAreNotRetried() {
        redirect(); server.enqueue(MockResponse().setResponseCode(403))
        client.newCall(original()).execute().use { assertEquals(403, it.code()) }
        assertTrue(delays.isEmpty()); assertEquals(2, server.requestCount)
    }
    @Test fun unrelatedHost404IsNotRetried() {
        redirect("example.test"); server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { client.newCall(original()).execute().close() }.isFailure)
        assertTrue(delays.isEmpty()); assertEquals(2, server.requestCount)
    }
    @Test fun contentUrlWithoutBothResponseParametersIsNotRetried() {
        redirect(target = "/macros/echo?user_content_key=temporary-response-key")
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { client.newCall(original()).execute().close() }.isFailure)
        assertTrue(delays.isEmpty()); assertEquals(2, server.requestCount)
    }
    @Test fun canceledCallNeverStartsAnotherRequest() {
        lateinit var call: okhttp3.Call
        val cancelClient = client.newBuilder().apply {
            interceptors().clear(); addInterceptor(GoogleContentResponseInterceptor { call.cancel() })
        }.build()
        redirect(); server.enqueue(MockResponse().setResponseCode(404))
        call = cancelClient.newCall(original())
        assertTrue(runCatching { call.execute().close() }.isFailure)
        assertEquals(2, server.requestCount)
    }
    @Test fun interruptedWaitClosesTheResponseWithoutResending() {
        val interruptedClient = client.newBuilder().apply {
            interceptors().clear(); addInterceptor(GoogleContentResponseInterceptor { throw InterruptedException() })
        }.build()
        redirect(); server.enqueue(MockResponse().setResponseCode(404))
        try {
            val error = runCatching { interruptedClient.newCall(original()).execute().close() }.exceptionOrNull()
            assertTrue(error is java.io.InterruptedIOException); assertEquals(2, server.requestCount)
        } finally { Thread.interrupted() }
    }
}
