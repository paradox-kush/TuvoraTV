package com.nuvio.tv.core.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class DirectDebridConfigEncoderTest {
    private val encoder = DirectDebridConfigEncoder()

    @Test
    fun `encodes torbox direct debrid config as compact standard base64`() {
        val encoded = encoder.encodeTorbox("tb_key")
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)

        assertEquals(
            """{"cachedOnly":true,"debridServices":[{"service":"torbox","apiKey":"tb_key"}],"enableTorrent":false}""",
            decoded
        )
        assertFalse(encoded.contains('\n'))
    }

    @Test
    fun `encodes real debrid config separately`() {
        val encoded = encoder.encode(
            DebridServiceCredential(
                provider = DebridProviders.RealDebrid,
                apiKey = "rd_key"
            )
        )
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)

        assertEquals(
            """{"cachedOnly":true,"debridServices":[{"service":"realdebrid","apiKey":"rd_key"}],"enableTorrent":false}""",
            decoded
        )
    }

    @Test
    fun `escapes api key before base64 encoding`() {
        val encoded = encoder.encodeTorbox("a\"b\\c")
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)

        assertEquals(
            """{"cachedOnly":true,"debridServices":[{"service":"torbox","apiKey":"a\"b\\c"}],"enableTorrent":false}""",
            decoded
        )
    }
}
