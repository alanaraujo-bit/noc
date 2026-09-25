package com.noc.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prepara imagens para o modelo de visão:
 * - corrige a rotação da câmera (EXIF);
 * - reduz o que é grande demais sem destruir texto: fotos até ~2,4 MP, capturas de tela altas mantêm
 *   a largura legível (lado maior até 2800 px);
 * - JPEG de alta qualidade (capturas/documentos 92, fotos 88) — nada de compressão agressiva.
 * O resultado leva o SHA-256, que o PC usa para guardar a imagem uma vez só.
 */
object Images {
    private const val MAX_PIXELS = 2_400_000
    private const val MAX_SIDE = 2048
    private const val MAX_SIDE_TALL = 2800

    fun prepare(context: Context, bytes: ByteArray, name: String, mime: String?): Attachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        val flip = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL).let { it == ExifInterface.ORIENTATION_FLIP_HORIZONTAL || it == ExifInterface.ORIENTATION_TRANSPOSE }

        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        val tall = max(w0, h0).toDouble() / minOf(w0, h0) > 1.8
        val sideCap = if (tall) MAX_SIDE_TALL else MAX_SIDE
        val scale = minOf(1.0, sideCap.toDouble() / max(w0, h0), sqrt(MAX_PIXELS.toDouble() / (w0.toDouble() * h0)))
        // decodifica já reduzido (potência de 2) para economizar memória, depois ajusta fino
        var sample = 1
        while (max(w0, h0) / (sample * 2) >= max(w0, h0) * scale) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val tw = (w0 * scale).roundToInt().coerceAtLeast(1)
        val th = (h0 * scale).roundToInt().coerceAtLeast(1)
        var bmp = if (decoded.width != tw || decoded.height != th) Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
        if (rotation != 0 || flip) {
            val m = Matrix().apply {
                if (flip) postScale(-1f, 1f)
                postRotate(rotation.toFloat())
            }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val documentLike = mime == "image/png" || mime == "image/webp" || looksFlat(bmp)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, if (documentLike) 92 else 88, out)
        val data = out.toByteArray()
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val sha = sha256(data)
        val file = File(dir, "$sha.jpg")
        if (!file.exists()) file.writeBytes(data)
        return Attachment(
            kind = "image", name = name, mime = "image/jpeg", size = data.size.toLong(), path = file.absolutePath,
            sha256 = sha, width = bmp.width, height = bmp.height, originalSize = bytes.size.toLong(),
        )
    }

    /** Capturas de tela e documentos têm muitas áreas de cor chapada. */
    private fun looksFlat(b: Bitmap): Boolean {
        val step = max(1, minOf(b.width, b.height) / 48)
        var same = 0
        var total = 0
        var y = 0
        while (y < b.height - step) {
            var x = 0
            while (x < b.width - step) {
                if (b.getPixel(x, y) == b.getPixel(x + step, y)) same++
                total++
                x += step
            }
            y += step
        }
        return total > 0 && same.toDouble() / total > 0.45
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Garante o hash de um anexo antigo (versões anteriores não guardavam). */
    fun ensureHash(a: Attachment): Attachment {
        if (a.kind != "image" || a.sha256 != null || a.path == null) return a
        val f = File(a.path)
        if (!f.exists()) return a
        return a.copy(sha256 = sha256(f.readBytes()))
    }

    /** Miniatura leve para a interface. */
    fun thumbnail(path: String, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    fun newTempName() = UUID.randomUUID().toString()
}
