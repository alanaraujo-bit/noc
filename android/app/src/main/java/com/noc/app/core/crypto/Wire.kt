package com.noc.app.core.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Primitivas de codificação do protocolo Noc v1 (ver protocol/PROTOCOL.md). */
object Wire {
    const val FRAME_CLIENT_HELLO: Byte = 0x01
    const val FRAME_SERVER_HELLO: Byte = 0x02
    const val FRAME_ENCRYPTED: Byte = 0x10
    const val FRAME_ERROR: Byte = 0x7F
    const val PROTOCOL_VERSION: Byte = 1

    val LABEL_TRANSCRIPT = "NOC/1/transcript".toByteArray(Charsets.US_ASCII)
    val LABEL_SERVER = "NOC/1/server".toByteArray(Charsets.US_ASCII)
    val LABEL_CLIENT = "NOC/1/client".toByteArray(Charsets.US_ASCII)
    val LABEL_PAIR = "NOC/1/pair".toByteArray(Charsets.US_ASCII)
    val INFO_KEYS = "NOC/1/keys".toByteArray(Charsets.US_ASCII)
    val INFO_SAS = "NOC/1/sas".toByteArray(Charsets.US_ASCII)

    fun lp(data: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Lê um campo lp; devolve (valor, próximo offset). */
    fun readLp(data: ByteArray, offset: Int, maxLen: Int = 4096): Pair<ByteArray, Int> {
        if (data.size - offset < 4) throw ProtocolException("truncated")
        val len = ByteBuffer.wrap(data, offset, 4).int
        if (len < 0 || len > maxLen || data.size - offset - 4 < len) throw ProtocolException("truncated")
        return data.copyOfRange(offset + 4, offset + 4 + len) to offset + 4 + len
    }

    private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"

    fun base32(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(value shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(value shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun idFromSpki(spki: ByteArray): String = base32(sha256(spki)).substring(0, 26)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream(length)
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmacSha256(prk, concat(t, info, byteArrayOf(counter.toByte())))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun b64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    fun unb64Url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

class ProtocolException(val code: String) : Exception(code)
