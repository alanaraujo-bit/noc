package com.noc.app.core.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

enum class HandshakeMode(val code: Byte) { SESSION(0), PAIR_QR(1), PAIR_CODE(2) }

/** Quem assina pelo celular. No app é a chave do Android Keystore; nos testes, uma chave em software. */
interface DeviceSigner {
    val publicSpki: ByteArray
    fun sign(data: ByteArray): ByteArray
}

object Ec {
    const val POINT_LEN = 65

    val P256: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun encodePoint(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    fun decodePoint(point: ByteArray): ECPublicKey {
        if (point.size != POINT_LEN || point[0] != 0x04.toByte()) throw ProtocolException("bad_point")
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
    }

    fun publicFromSpki(spki: ByteArray): ECPublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey

    fun verify(spki: ByteArray, data: ByteArray, derSig: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(publicFromSpki(spki))
        s.update(data)
        s.verify(derSig)
    } catch (e: Exception) {
        false
    }
}

/**
 * Lado cliente do handshake Noc v1.
 * Para testes determinísticos, `ephemeralPrivate`/`ephemeralPoint`/`nonce` podem ser injetados.
 */
class HandshakeClient(
    mode: HandshakeMode,
    ephemeralPrivate: PrivateKey? = null,
    ephemeralPoint: ByteArray? = null,
    nonce: ByteArray? = null,
) {
    private val ephPrivate: PrivateKey
    val clientHello: ByteArray

    init {
        val point: ByteArray
        if (ephemeralPrivate != null && ephemeralPoint != null) {
            ephPrivate = ephemeralPrivate
            point = ephemeralPoint
        } else {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            ephPrivate = kp.private
            point = Ec.encodePoint(kp.public as ECPublicKey)
        }
        val n = nonce ?: ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        clientHello = Wire.concat(
            byteArrayOf(Wire.FRAME_CLIENT_HELLO, Wire.PROTOCOL_VERSION, mode.code), point, n,
        )
    }

    class Completed(
        val pcSpki: ByteArray,
        val transcriptHash: ByteArray,
        val channel: SecureChannel,
        val sas: String,
    )

    fun complete(serverHello: ByteArray): Completed {
        if (serverHello.isNotEmpty() && serverHello[0] == Wire.FRAME_ERROR) {
            throw ProtocolException(String(serverHello, 1, serverHello.size - 1, Charsets.UTF_8))
        }
        if (serverHello.size < 1 + Ec.POINT_LEN + NONCE_LEN + 8 || serverHello[0] != Wire.FRAME_SERVER_HELLO) {
            throw ProtocolException("bad_server_hello")
        }
        val ephS = serverHello.copyOfRange(1, 1 + Ec.POINT_LEN)
        val (pcSpki, afterSpki) = Wire.readLp(serverHello, 1 + Ec.POINT_LEN + NONCE_LEN)
        val (sig, end) = Wire.readLp(serverHello, afterSpki, 256)
        if (end != serverHello.size) throw ProtocolException("bad_server_hello")

        val th = transcriptHash(clientHello, serverHello.copyOfRange(0, afterSpki))
        if (!Ec.verify(pcSpki, Wire.concat(Wire.LABEL_SERVER, th), sig)) throw ProtocolException("server_signature")

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephPrivate)
        ka.doPhase(Ec.decodePoint(ephS), true)
        val z = ka.generateSecret()
        val okm = Wire.hkdf(z, th, Wire.INFO_KEYS, 64)
        val sas = deriveSas(z, th)
        z.fill(0)
        val channel = SecureChannel(
            sendKey = okm.copyOfRange(0, 32), recvKey = okm.copyOfRange(32, 64), sendDir = 1, recvDir = 2,
        )
        okm.fill(0)
        return Completed(pcSpki, th, channel, sas)
    }

    companion object {
        const val NONCE_LEN = 32

        fun transcriptHash(clientHello: ByteArray, serverHelloUnsigned: ByteArray): ByteArray =
            Wire.sha256(Wire.LABEL_TRANSCRIPT, Wire.lp(clientHello), Wire.lp(serverHelloUnsigned))

        fun deriveSas(z: ByteArray, th: ByteArray): String {
            val b = Wire.hkdf(z, th, Wire.INFO_SAS, 4)
            val v = ByteBuffer.wrap(b).int.toLong() and 0xffffffffL
            return "%06d".format(v % 1_000_000)
        }

        fun clientSignatureInput(th: ByteArray) = Wire.concat(Wire.LABEL_CLIENT, th)

        fun pairMac(secret: ByteArray, th: ByteArray, deviceSpki: ByteArray): ByteArray =
            Wire.hmacSha256(secret, Wire.concat(Wire.LABEL_PAIR, th, deviceSpki))
    }
}
