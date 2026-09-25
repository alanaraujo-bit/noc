package com.noc.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.noc.app.AppContainer
import com.noc.app.MainActivity
import com.noc.app.data.prefs.AppPrefs
import com.noc.app.ui.chat.ChatScreen
import com.noc.app.ui.diagnostics.DiagnosticsScreen
import com.noc.app.ui.history.HistoryScreen
import com.noc.app.ui.home.HomeScreen
import com.noc.app.ui.library.PresetEditScreen
import com.noc.app.ui.library.PresetsScreen
import com.noc.app.ui.library.PromptEditScreen
import com.noc.app.ui.library.PromptsScreen
import com.noc.app.ui.models.ModelsScreen
import com.noc.app.ui.onboarding.OnboardingScreen
import com.noc.app.ui.onboarding.SetupCheckScreen
import com.noc.app.ui.pairing.PairScreen
import com.noc.app.ui.settings.ComputersScreen
import com.noc.app.ui.settings.SecurityScreen
import com.noc.app.ui.settings.SettingsScreen
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object Routes {
    const val WELCOME = "welcome"
    const val PAIR = "pair?link={link}"
    const val SETUP_CHECK = "setup-check"
    const val HOME = "home"
    const val CHAT = "chat/{id}?draft={draft}&preset={preset}"
    const val HISTORY = "history"
    const val MODELS = "models"
    const val PRESETS = "presets"
    const val PRESET = "preset/{id}"
    const val PROMPTS = "prompts?pick={pick}"
    const val PROMPT = "prompt/{id}"
    const val SETTINGS = "settings"
    const val COMPUTERS = "computers"
    const val SECURITY = "security"
    const val DIAGNOSTICS = "diagnostics"

    fun chat(id: String, draft: String? = null, preset: String? = null): String {
        val q = listOfNotNull(draft?.let { "draft=" + Uri.encode(it) }, preset?.let { "preset=" + Uri.encode(it) })
        return "chat/$id" + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    /** Conversa nova: só é gravada no banco quando a primeira mensagem for enviada. */
    const val NEW = "new"
    fun pair(link: String? = null) = "pair" + (link?.let { "?link=" + Uri.encode(it) } ?: "")
    fun preset(id: String) = "preset/$id"
    fun prompt(id: String) = "prompt/$id"
    fun prompts(pick: Boolean = false) = "prompts?pick=$pick"
}

private const val DUR = 280

@Composable
fun NocNavHost(container: AppContainer, prefs: AppPrefs, incoming: MutableStateFlow<MainActivity.Incoming?>) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val pending by incoming.collectAsState()
    // Fixada na primeira composição: mudar a startDestination depois reinicia a pilha inteira.
    val start = remember {
        when {
            prefs.onboardingDone -> Routes.HOME
            prefs.activePcId != null -> Routes.SETUP_CHECK // pareou mas não terminou a configuração
            else -> Routes.WELCOME
        }
    }

    LaunchedEffect(pending) {
        when (val i = pending) {
            is MainActivity.Incoming.PairLink -> nav.navigate(Routes.pair(i.uri))
            is MainActivity.Incoming.OpenConversation -> nav.navigate(Routes.chat(i.id)) { launchSingleTop = true }
            is MainActivity.Incoming.SharedText -> if (prefs.onboardingDone) nav.navigate(Routes.chat(Routes.NEW, i.text))
            null -> Unit
        }
        if (pending != null) incoming.value = null
    }

    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier.fillMaxSize().background(Noc.colors.bg),
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR, easing = FastOutSlowInEasing)) { it / 6 } +
                fadeIn(tween(DUR))
        },
        exitTransition = { fadeOut(tween(DUR / 2)) },
        popEnterTransition = { fadeIn(tween(DUR)) },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR, easing = FastOutSlowInEasing)) { it / 6 } +
                fadeOut(tween(DUR))
        },
    ) {
        composable(Routes.WELCOME) {
            OnboardingScreen(onPair = { nav.navigate(Routes.pair()) })
        }
        composable(
            Routes.PAIR,
            arguments = listOf(navArgument("link") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            PairScreen(
                container = container,
                initialLink = entry.arguments?.getString("link"),
                onBack = { if (!nav.popBackStack()) nav.navigate(Routes.WELCOME) },
                onPaired = {
                    nav.navigate(Routes.SETUP_CHECK) { popUpTo(0) }
                },
            )
        }
        composable(Routes.SETUP_CHECK) {
            SetupCheckScreen(
                container = container,
                onDone = { convId ->
                    scope.launch {
                        container.prefs.setOnboardingDone(true)
                        nav.navigate(Routes.HOME) { popUpTo(0) }
                        if (convId != null) nav.navigate(Routes.chat(convId))
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(container = container, prefs = prefs, nav = nav)
        }
        composable(
            Routes.CHAT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("draft") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("preset") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ChatScreen(
                container = container,
                prefs = prefs,
                conversationId = entry.arguments!!.getString("id")!!,
                initialDraft = entry.arguments?.getString("draft"),
                initialPreset = entry.arguments?.getString("preset"),
                nav = nav,
            )
        }
        composable(Routes.HISTORY) { HistoryScreen(container, nav) }
        composable(Routes.MODELS) { ModelsScreen(container, onBack = { nav.popBackStack() }) }
        composable(Routes.PRESETS) { PresetsScreen(container, nav) }
        composable(Routes.PRESET, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            PresetEditScreen(container, e.arguments!!.getString("id")!!, nav)
        }
        composable(Routes.PROMPTS, arguments = listOf(navArgument("pick") { type = NavType.BoolType; defaultValue = false })) { e ->
            PromptsScreen(container, nav, pickMode = e.arguments?.getBoolean("pick") == true)
        }
        composable(Routes.PROMPT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            PromptEditScreen(container, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(container, prefs, nav) }
        composable(Routes.COMPUTERS) { ComputersScreen(container, nav) }
        composable(Routes.SECURITY) { SecurityScreen(container, onBack = { nav.popBackStack() }) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(container, nav) }
    }
}

fun NavHostController.newChat(presetId: String? = null, draft: String? = null) {
    navigate(Routes.chat(Routes.NEW, draft, presetId))
}
