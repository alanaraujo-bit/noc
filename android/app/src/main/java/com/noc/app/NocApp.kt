package com.noc.app

import android.app.Application
import com.noc.app.chat.ChatEngine
import com.noc.app.chat.Library
import com.noc.app.core.net.ConnectionManager
import com.noc.app.data.db.NocDatabase
import com.noc.app.data.prefs.Prefs
import com.noc.app.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Dependências do app (injeção manual: simples, rápida de iniciar e fácil de seguir). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = NocDatabase.build(app)
    val prefs = Prefs(app)
    val connection = ConnectionManager(app, db, prefs, scope, BuildConfig.DEFAULT_RELAY)
    val chat = ChatEngine(app, db, prefs, connection, scope)
}

class NocApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        GenerationService.ensureChannels(this)
        container.scope.launch(Dispatchers.IO) {
            Library.seed(container.db)
            container.chat.resumePending()
        }
        container.connection.start()
    }
}
