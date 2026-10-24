package com.example.namufarm.quotemode

import android.content.Intent
import com.example.namufarm.phone.smsComposeIntent
import com.example.namufarm.quotation.QuoteReply
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuoteComposerTest {
    @Test fun smsOpensComposerWithRecipientAndBody() {
        val intent = smsComposeIntent("010-1234-5678", "가죽나무 2주 견적")!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto:01012345678", intent.data.toString())
        assertEquals("가죽나무 2주 견적", intent.getStringExtra("sms_body"))
    }
    @Test fun invalidPhoneNeverCreatesComposer() { assertNull(smsComposeIntent("invalid", "견적")) }
    @Test fun emailUsesMailtoRecipientSubjectAndBody() {
        val intent = QuoteReply.textEmailIntent("quote@example.com", "가죽나무 2주 = 12,000원")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data!!.scheme)
        assertEquals("가죽나무 2주 = 12,000원", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("quote@example.com", intent.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.single())
    }
}
