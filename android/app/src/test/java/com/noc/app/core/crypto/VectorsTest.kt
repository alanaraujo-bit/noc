package com.noc.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec

/** Valida a implementação Kotlin contra protocol/vectors.json, gerado pelo C#. */
class VectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("vectors.json")!!.readText())
    private fun h(k: String) = Wire.unhex(v.getString(k))
    private fun priv(k: String) = KeyFactory.getInstance("EC")
        .generatePrivate(ECPrivateKeySpec(BigInteger(1, h(k)), Ec.P256))

    private fun client() = HandshakeClient(
        HandshakeMode.PAIR_QR,
        ephemeralPrivate = priv("ephC_d"),
        ephemeralPoint = h("clientHello").copyOfRange(3, 68),
        nonce = h("nonceC"),
    )

    @Test fun ids() {
        assertEquals(v.getString("pcId"), Wire.idFromSpki(h("pcSpki")))
        assertEquals(v.getString("deviceId"), Wire.idFromSpki(h("devSpki")))
        assertEquals("mzxw6ytboi", Wire.base32("foobar".toByteArray()))
    }

    @Test fun hkdf_rfc5869_case1() {
        val okm = Wire.hkdf(
            Wire.unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            Wire.unhex("000102030405060708090a0b0c"),
            Wire.unhex("f0f1f2f3f4f5f6f7f8f9"), 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Wire.hex(okm))
    }

    @Test fun handshake_matches_csharp() {
        val c = client()
        assertArrayEquals(h("clientHello"), c.clientHello)
        val done = c.complete(h("serverHello"))
        assertArrayEquals(h("transcriptHash"), done.transcriptHash)
        assertArrayEquals(h("pcSpki"), done.pcSpki)
        assertEquals(v.getString("sas"), done.sas)
        // primeiro frame cifrado celular→PC tem que sair idêntico ao do C#
        assertArrayEquals(h("frameC2S0"), done.channel.seal(v.getString("frameC2S0_plain").toByteArray()))
        // e o frame PC→celular gerado pelo C# tem que abrir
        assertEquals(v.getString("frameS2C0_plain"), String(done.channel.open(h("frameS2C0")), Charsets.UTF_8))
    }

    @Test fun pair_mac_and_signatures() {
        val th = h("transcriptHash")
        assertArrayEquals(h("pairMac"), HandshakeClient.pairMac(h("pairSecret"), th, h("devSpki")))
        // assinatura DER gerada pelo .NET verifica no JCA
        assertTrue(Ec.verify(h("devSpki"), HandshakeClient.clientSignatureInput(th), h("deviceSig")))
    }

    @Test fun tampered_server_hello_fails() {
        val bad = h("serverHello").also { it[40] = (it[40].toInt() xor 1).toByte() }
        try { client().complete(bad); fail() } catch (e: Exception) { }
    }
}
