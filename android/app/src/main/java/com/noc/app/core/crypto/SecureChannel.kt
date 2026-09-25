package com.noc.app.core.crypto

import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM com contadores estritos por direção.
 * Frame: 0x10 ‖ counter(u64 BE) ‖ ciphertext ‖ tag(16) — o JCA já anexa a tag.
 */
class SecureChannel(
    sendKey: ByteArray,
    recvKey: ByteArray,
    private val sendDir: Int,
    private val recvDir: Int,
) {
    private val send = SecretKeySpec(sendKey.copyOf(), "AES")
    private val recv = SecretKeySpec(recvKey.copyOf(), "AES")
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val sendLock = Any()
    private val recvLock = Any()

    init {
        sendKey.fill(0)
        recvKey.fill(0)
    }

    private fun nonce(dir: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(dir).putLong(counter).array()

    fun seal(plaintext: ByteArray): ByteArray = synchronized(sendLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, send, GCMParameterSpec(128, nonce(sendDir, sendCounter)))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plaintext)
        val frame = ByteBuffer.allocate(HEADER + ct.size)
            .put(Wire.FRAME_ENCRYPTED).putLong(sendCounter).put(ct).array()
        sendCounter++
        frame
    }

    fun open(frame: ByteArray): ByteArray = synchronized(recvLock) {
        if (frame.size < HEADER + 16 || frame[0] != Wire.FRAME_ENCRYPTED) throw ProtocolException("bad_frame")
        val counter = ByteBuffer.wrap(frame, 1, 8).long
        if (counter != recvCounter) throw ProtocolException("replay")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, recv, GCMParameterSpec(128, nonce(recvDir, counter)))
        cipher.updateAAD(AAD)
        val pt = try {
            cipher.doFinal(frame, HEADER, frame.size - HEADER)
        } catch (e: AEADBadTagException) {
            throw ProtocolException("auth_tag")
        }
        recvCounter++
        pt
    }

    private companion object {
        const val HEADER = 9
        val AAD = byteArrayOf(Wire.FRAME_ENCRYPTED)
    }
}
