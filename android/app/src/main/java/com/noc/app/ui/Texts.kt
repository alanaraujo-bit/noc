package com.noc.app.ui

import com.noc.app.core.net.ConnState
import com.noc.app.core.net.LmState
import com.noc.app.core.net.PcStatus
import com.noc.app.core.net.Problem
import com.noc.app.core.net.Route
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Tudo que o usuário lê sobre erros e estados passa por aqui: nada de mensagem técnica crua. */
object Texts {

    data class Explain(val title: String, val body: String, val action: String? = null)

    fun problem(p: Problem): Explain = when (p) {
        Problem.NoInternet -> Explain("Sem internet no celular", "Conecte-se a uma rede Wi-Fi ou aos dados móveis. Suas conversas continuam aqui.")
        is Problem.PcOffline -> Explain(
            "Seu PC está offline",
            (p.lastSeen?.let { "Visto por último ${relative(it)}. " } ?: "") +
                "Verifique se o computador está ligado, com internet, e se o Noc Companion está aberto.",
            "Tentar agora",
        )
        Problem.CompanionNotResponding -> Explain(
            "O Companion não respondeu",
            "O PC está conectado, mas o Noc Companion demorou para atender. Ele pode estar travado ou reiniciando.",
            "Tentar agora",
        )
        Problem.RelayUnreachable -> Explain(
            "Acesso remoto indisponível",
            "O serviço de conexão remota não respondeu. Na mesma Wi-Fi do PC, a conexão direta continua funcionando.",
            "Tentar agora",
        )
        Problem.LanUnreachable -> Explain(
            "PC fora de alcance",
            "Este PC só aceita conexões pela rede local. Conecte-se à mesma Wi-Fi ou ative o acesso remoto no Companion.",
            "Tentar agora",
        )
        Problem.Revoked -> Explain(
            "Este celular foi desvinculado",
            "O acesso deste aparelho foi revogado no PC. Para voltar a usar, faça o pareamento de novo.",
            "Parear novamente",
        )
        Problem.PcForgotDevice -> Explain(
            "O PC não reconhece este celular",
            "O Companion pode ter sido reinstalado ou os dados foram apagados. Faça o pareamento de novo.",
            "Parear novamente",
        )
        Problem.SecurityMismatch -> Explain(
            "Conexão bloqueada por segurança",
            "O computador respondeu com uma identidade diferente da que foi pareada. Por segurança, nada foi enviado. " +
                "Se você reinstalou o Companion, pareie de novo.",
            "Parear novamente",
        )
        Problem.Incompatible -> Explain(
            "Versões incompatíveis",
            "O Noc do celular e o Companion do PC não falam a mesma versão. Atualize os dois.",
        )
        Problem.RateLimited -> Explain(
            "Muitas tentativas",
            "Por proteção, novas conexões foram pausadas por alguns minutos.",
            "Tentar agora",
        )
        Problem.Timeout -> Explain("A conexão demorou demais", "A rede está lenta ou instável. Vamos tentar de novo.", "Tentar agora")
        is Problem.Other -> Explain("Não foi possível conectar", "Algo interrompeu a conexão com o seu PC. Vamos tentar de novo.", "Tentar agora")
    }

    /** Frase curta de estado para a Home. */
    fun headline(state: ConnState, status: PcStatus?): String = when (state) {
        ConnState.NoPc -> "Nenhum computador pareado."
        ConnState.Paused -> "Pronto para conectar."
        is ConnState.Connecting -> "Conectando a ${state.pcName}…"
        is ConnState.Offline -> problem(state.problem).title + "."
        is ConnState.Online -> when {
            status == null -> "Conectado a ${state.pc.name}."
            status.lm == LmState.NOT_INSTALLED -> "LM Studio não encontrado no PC."
            status.lm != LmState.RUNNING -> "LM Studio não está disponível."
            status.op?.kind == "loading" -> "Carregando modelo…"
            status.jobs.isNotEmpty() -> "Seu PC está respondendo."
            status.loaded.isEmpty() -> "Nenhum modelo está carregado."
            else -> "Seu PC está pronto."
        }
    }

    fun route(r: Route) = if (r == Route.LAN) "Rede local" else "Remoto"

    /** Código de erro de uma resposta → texto humano. */
    fun replyError(raw: String?): String {
        val code = raw?.substringBefore('|') ?: ""
        val detail = raw?.substringAfter('|', "") ?: ""
        return when {
            code == "lm_offline" || code == "lm_unreachable" -> "O LM Studio não está rodando no PC. Abra o LM Studio ou toque em “Iniciar LM Studio” na tela inicial."
            code == "model_not_found" -> "Esse modelo não existe mais no PC. Escolha outro modelo."
            code == "load_failed" -> "O PC não conseguiu carregar o modelo" + (if (detail.contains("memory", true) || detail.contains("VRAM", true)) " — falta memória de vídeo." else ".") + " Tente um modelo menor ou um contexto menor."
            code == "busy" -> "O PC já está gerando várias respostas. Aguarde uma terminar."
            code == "job_unknown" || code == "interrupted" -> "A resposta foi interrompida (o Companion reiniciou)."
            code == "not_llm" -> "Esse modelo não é de conversa."
            code == "lm_error" && (detail.contains("context", true) && detail.contains("length", true)) -> "A conversa ficou maior que o contexto do modelo. Aumente o contexto ou comece uma nova conversa."
            code == "lm_error" -> "O LM Studio recusou o pedido" + (if (detail.isNotBlank()) ": ${detail.take(140)}" else ".")
            code == "not_connected" -> "Sem conexão com o PC."
            else -> "Algo deu errado ao gerar a resposta."
        }
    }

    private val ptBR = Locale("pt", "BR")

    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = abs(now - ms) / 1000
        return when {
            d < 45 -> "agora"
            d < 3600 -> "há ${d / 60} min"
            d < 86_400 -> "há ${d / 3600} h"
            d < 172_800 -> "ontem"
            d < 7 * 86_400 -> "há ${d / 86_400} dias"
            else -> SimpleDateFormat("d MMM", ptBR).format(Date(ms))
        }
    }

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", ptBR).format(Date(ms))

    fun dayLabel(ms: Long): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.timeInMillis = ms
        val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        return when {
            that == today -> "Hoje"
            that.second == today.second && that.first == today.first - 1 -> "Ontem"
            else -> SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(Date(ms)).replaceFirstChar { it.uppercase() }
        }
    }

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(ptBR, b / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(ptBR, b / 1e6)
        else -> "%.0f KB".format(ptBR, b / 1e3)
    }

    fun tokens(n: Int): String = when {
        n >= 1024 && n % 1024 == 0 -> "${n / 1024}k"
        n >= 1000 -> "%.1fk".format(ptBR, n / 1000.0).replace(",0k", "k")
        else -> n.toString()
    }

    fun seconds(ms: Long): String = if (ms < 10_000) "%.1f s".format(ptBR, ms / 1000.0) else "${ms / 1000} s"

    fun gb(mb: Int): String = "%.1f".format(ptBR, mb / 1024.0)
}
