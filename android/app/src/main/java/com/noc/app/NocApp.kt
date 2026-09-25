package com.noc.app

import android.app.Application
import android.net.Uri
import com.noc.app.chat.ChatEngine
import com.noc.app.chat.Library
import com.noc.app.chat.ModelOps
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ConnectionManager
import com.noc.app.core.net.Problem
import com.noc.app.data.db.NocDatabase
import com.noc.app.data.prefs.Prefs
import com.noc.app.service.Notifier
import com.noc.app.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Dependências do app (injeção manual: simples, rápida de iniciar e fácil de seguir). */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = NocDatabase.build(app)
    val prefs = Prefs(app)
    val connection = ConnectionManager(app, db, prefs, scope, BuildConfig.DEFAULT_RELAY)
    val chat = ChatEngine(app, db, prefs, connection, scope)
    val modelOps = ModelOps(app, connection, scope) { chat.notifier }
    val voice = VoiceController(app, connection, prefs, scope)

    /** Imagens compartilhadas de outro app esperando a tela de conversa abrir. */
    val pendingShare = MutableStateFlow<List<Uri>>(emptyList())
}

class NocApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.ensureChannels(this)
        container.scope.launch(Dispatchers.IO) {
            Library.seed(container.db)
            container.chat.resumePending()
        }
        container.connection.start()
        watchPc()
    }

    /** Avisos opcionais "seu PC ficou disponível/offline" (só enquanto o app está vivo). */
    private fun watchPc() = container.scope.launch {
        var wasOnline: Boolean? = null
        container.connection.state.collect { s ->
            val online = s is ConnState.Online
            val offline = s is ConnState.Offline && s.problem is Problem.PcOffline
            if (wasOnline == true && offline) container.chat.notifier.onPcStatus(false, (s as ConnState.Offline).pcName)
            if (wasOnline == false && online) container.chat.notifier.onPcStatus(true, (s as ConnState.Online).pc.name)
            if (online) wasOnline = true else if (offline) wasOnline = false
        }
    }
}
