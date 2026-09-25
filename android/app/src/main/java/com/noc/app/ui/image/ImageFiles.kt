package com.noc.app.ui.image

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compartilhar, copiar e salvar na galeria as imagens criadas (arquivo original, sem nada do prompt dentro). */
object ImageFiles {
    private fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    private fun displayName() = "Noc_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"

    /** Abre o compartilhamento nativo do Android (WhatsApp, Instagram, Telegram, editores…). */
    fun share(context: Context, files: List<File>) {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) return
        val uris = ArrayList(existing.map { uriOf(context, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/png"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        send.clipData = ClipData.newUri(context.contentResolver, "Imagem", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Compartilhar imagem").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copia a imagem (colar em outro app que aceita imagens). */
    fun copy(context: Context, file: File) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Imagem", uriOf(context, file)))
    }

    /** No Android 8 e 9 salvar em Imagens precisa da permissão de armazenamento; do 10 em diante, não. */
    fun needsPermission(context: Context) = Build.VERSION.SDK_INT < 29 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Salva em Imagens/Noc (aparece na Galeria/Google Fotos). */
    suspend fun saveToGallery(context: Context, file: File): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Noc")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("sem acesso à galeria")
                try {
                    cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                    cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    cr.delete(uri, null, null)
                    throw e
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Noc").apply { mkdirs() }
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = false)
                MediaScannerConnection.scanFile(context, arrayOf(dest.path), arrayOf("image/png"), null)
                Uri.fromFile(dest)
            }
        }
    }
}
