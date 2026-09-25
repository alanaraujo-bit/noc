package com.noc.app.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.noc.app.NocApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Ações das notificações: parar de verdade no PC, copiar a resposta, tentar de novo. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_MESSAGE) ?: return
        val container = (context.applicationContext as NocApp).container
        val pending = goAsync()
        container.scope.launch(Dispatchers.IO) {
            try {
                when (intent.action) {
                    STOP -> container.chat.stopFromNotification(id)
                    COPY -> {
                        val m = container.db.messages().get(id)
                        if (m != null) {
                            launch(Dispatchers.Main) {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("Resposta", m.content))
                                Toast.makeText(context, "Resposta copiada", Toast.LENGTH_SHORT).show()
                            }.join()
                        }
                        container.chat.notifier.cancelFor(id)
                    }
                    RETRY -> {
                        container.chat.notifier.cancelFor(id)
                        runCatching { container.chat.regenerate(id) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val STOP = "com.noc.app.action.STOP"
        const val COPY = "com.noc.app.action.COPY"
        const val RETRY = "com.noc.app.action.RETRY"
        const val EXTRA_MESSAGE = "message"
    }
}
