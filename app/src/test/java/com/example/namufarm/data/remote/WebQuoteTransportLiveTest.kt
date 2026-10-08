package com.example.namufarm.data.remote

import com.example.namufarm.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicitly enabled diagnostic: invalid credentials never claim an invite or read customer rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebQuoteTransportLiveTest {
    @Test fun readOnlyProductionPairingProbe() = runBlocking {
        assumeTrue(System.getenv("NAMUFARM_LIVE_TRANSPORT_CHECK") == "1")
        val response = WebQuoteTransport.api.request(BuildConfig.WEB_REQUEST_SCRIPT_URL, mapOf(
            "action" to "pair_device", "invite" to "0".repeat(64),
            "deviceToken" to "0".repeat(64), "label" to "read-only transport probe"
        ))
        assertEquals(false, response.ok); assertEquals("UNAUTHORIZED", response.code)
        println("Production Android transport: HTTP JSON received; invalid credentials rejected; no invite claimed.")
    }
}
