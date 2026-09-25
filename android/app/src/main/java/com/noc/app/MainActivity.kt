package com.noc.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.noc.app.data.prefs.ThemeMode
import com.noc.app.ui.NocNavHost
import com.noc.app.ui.theme.NocTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Links recebidos (QR do pareamento, conversa vinda da notificação, texto compartilhado). */
    val incoming = MutableStateFlow<Incoming?>(null)

    sealed interface Incoming {
        data class PairLink(val uri: String) : Incoming
        data class OpenConversation(val id: String) : Incoming
        data class SharedText(val text: String) : Incoming
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NocApp).container
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        handle(intent)
        setContent {
            val prefs by container.prefs.flow.collectAsState(initial = null)
            val p = prefs
            ready = p != null
            val dark = when (p?.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }
            NocTheme(dark = dark) {
                if (p != null) NocNavHost(container, p, incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when {
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "noc" ->
                incoming.value = Incoming.PairLink(intent.dataString!!)
            intent.hasExtra(EXTRA_CONVERSATION) ->
                incoming.value = Incoming.OpenConversation(intent.getStringExtra(EXTRA_CONVERSATION)!!)
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { incoming.value = Incoming.SharedText(it) }
        }
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
    }
}
