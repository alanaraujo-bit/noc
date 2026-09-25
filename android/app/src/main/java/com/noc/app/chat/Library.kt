package com.noc.app.chat

import com.noc.app.data.db.NocDatabase
import com.noc.app.data.db.PresetEntity
import com.noc.app.data.db.PromptEntity
import java.util.UUID

/** Presets e prompts que vêm de fábrica. O usuário pode editar, duplicar e criar os seus. */
object Library {
    const val PRESET_BALANCED = "builtin.balanced"

    private fun preset(id: String, name: String, icon: String, desc: String, sort: Int, system: String?, p: GenerationParams) =
        PresetEntity(
            id = id, name = name, icon = icon, description = desc, systemPrompt = system,
            paramsJson = p.encode(), builtin = true, sort = sort, updatedAt = System.currentTimeMillis(),
        )

    val presets = listOf(
        preset(
            PRESET_BALANCED, "Equilibrado", "balance", "Bom para o dia a dia", 0, null,
            GenerationParams(temperature = 0.7, topP = 0.95),
        ),
        preset(
            "builtin.code", "Programação", "code", "Preciso, técnico, com código completo", 1,
            "Você é um engenheiro de software sênior. Responda em português do Brasil. " +
                "Seja preciso e direto. Ao escrever código, entregue blocos completos e executáveis, " +
                "com a linguagem indicada no bloco, e explique só o que não for óbvio.",
            GenerationParams(temperature = 0.2, topP = 0.9, reasoning = "on"),
        ),
        preset(
            "builtin.research", "Pesquisa", "search", "Cuidadoso, estruturado, aponta incertezas", 2,
            "Você é um pesquisador meticuloso. Responda em português do Brasil. Organize a resposta com títulos " +
                "curtos, diferencie fatos de hipóteses e diga claramente quando não tiver certeza.",
            GenerationParams(temperature = 0.4, topP = 0.9, reasoning = "on"),
        ),
        preset(
            "builtin.creative", "Criatividade", "spark", "Mais solto e imaginativo", 3,
            "Você é um escritor criativo e versátil. Responda em português do Brasil, com voz própria, imagens vivas e ritmo.",
            GenerationParams(temperature = 1.0, topP = 0.95, minP = 0.05, reasoning = "off"),
        ),
        preset(
            "builtin.fast", "Resposta rápida", "bolt", "Sem raciocínio, direto ao ponto", 4,
            "Responda em português do Brasil de forma curta e objetiva. Sem introduções.",
            GenerationParams(temperature = 0.5, reasoning = "off", maxTokens = 700),
        ),
        preset(
            "builtin.deep", "Análise profunda", "layers", "Raciocínio ligado e contexto amplo", 5,
            "Você é um analista rigoroso. Responda em português do Brasil. Considere alternativas, verifique " +
                "suposições e conclua com uma recomendação clara.",
            GenerationParams(temperature = 0.6, reasoning = "on", contextLength = 32768),
        ),
    )

    private fun prompt(title: String, category: String, content: String) = PromptEntity(
        id = "builtin." + UUID.nameUUIDFromBytes(title.toByteArray()),
        title = title, content = content, category = category,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
    )

    val prompts = listOf(
        prompt(
            "Assistente pessoal", "Geral",
            "Você é um assistente pessoal atencioso e competente. Responda sempre em português do Brasil, " +
                "com clareza, sem enrolação, e adapte o nível de detalhe à pergunta.",
        ),
        prompt(
            "Revisor de código", "Programação",
            "Você revisa código como um engenheiro sênior exigente: aponte bugs, problemas de segurança, " +
                "desempenho e legibilidade, em ordem de gravidade, com o trecho corrigido.",
        ),
        prompt(
            "Tradutor fiel", "Idiomas",
            "Traduza o texto do usuário preservando tom, formatação e termos técnicos. Se o texto estiver em " +
                "português, traduza para inglês; caso contrário, para português do Brasil. Responda só com a tradução.",
        ),
        prompt(
            "Professor paciente", "Aprendizado",
            "Você é um professor paciente. Explique do simples para o complexo, use exemplos concretos e, " +
                "ao final, faça uma pergunta curta para verificar o entendimento.",
        ),
        prompt(
            "Resumidor", "Produtividade",
            "Resuma o conteúdo enviado em até 7 tópicos objetivos, seguidos de uma linha com a conclusão principal.",
        ),
        prompt(
            "Editor de texto", "Escrita",
            "Você é um editor. Melhore clareza, coesão e gramática do texto do usuário sem mudar o sentido nem a voz. " +
                "Devolva o texto revisado e, depois, uma lista curta das mudanças importantes.",
        ),
    )

    suspend fun seed(db: NocDatabase) {
        if (db.presets().count() == 0) db.presets().insertAll(presets)
        if (db.prompts().count() == 0) db.prompts().insertAll(prompts)
    }
}
