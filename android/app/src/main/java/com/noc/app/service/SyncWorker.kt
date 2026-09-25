package com.noc.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noc.app.NocApp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Rede de segurança para quando o Android encerra o app (falta de memória) ou o celular reinicia
 * no meio de uma tarefa: o WorkManager (que sobrevive a isso) acorda o app quando houver rede,
 * reconecta, busca no PC o que ficou pronto, atualiza a conversa e notifica.
 * Não fica rodando à toa: só existe enquanto houver tarefa pendente.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NocApp).container
        val db = container.db
        if (db.messages().pending().isEmpty()) return Result.success()
        val release = container.connection.acquire()
        try {
            container.chat.resumePending()
            // espera as tarefas terminarem (o PC manda o fim assim que conectamos) — até ~8 min por rodada
            val deadline = System.currentTimeMillis() + 8 * 60_000
            while (System.currentTimeMillis() < deadline) {
                if (db.messages().pending().isEmpty()) return Result.success()
                delay(3_000)
            }
        } finally {
            release()
        }
        // ainda tem coisa rodando no PC: volta daqui a pouco
        schedule(applicationContext, delayMinutes = 1, append = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "noc-sync"

        /** [append]: chamado de dentro do próprio worker (um trabalho único em execução ignoraria o KEEP). */
        fun schedule(context: Context, delayMinutes: Long = 2, append: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
