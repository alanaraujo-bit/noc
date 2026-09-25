# Noc — Progresso e decisões

> "Minha IA. Meu computador. Meus dados."
> Acesso remoto seguro (E2E) do Android aos modelos locais do LM Studio no PC.

## Estrutura
- `relay/` — Node.js + ws, deploy no Railway. Sem estado. Só encaminha bytes cifrados.
- `companion/` — .NET 10 WPF + Kestrel (Windows). Ponte LM Studio ↔ celular.
- `android/` — Kotlin, Compose, M3, Room, DataStore, OkHttp.
- `protocol/` — especificação do protocolo + vetores de teste.

## Decisões
- Cripto: ECDH P-256 efêmero + ECDSA P-256 (DER) + HKDF-SHA256 + AES-256-GCM (ct‖tag, nonce = dir‖contador).
  Transcript em binário com prefixo de tamanho (nunca JSON).
- Identidade PC: chave ECDSA protegida por DPAPI. pcId = base32(sha256(pubkey SPKI))[:26].
- Identidade celular: chave ECDSA no Android Keystore (não exportável).
- Pareamento: QR (chave do PC fixada) — imediato; código curto — requer aprovação no PC com SAS.
- Relay: PC mantém socket de controle; celular conecta → relay avisa PC → PC abre socket "accept" → relay une os dois.
- Jobs de geração retomáveis no Companion (seq + buffer com TTL).
- LAN: ws:// direto (conteúdo já é E2E) — endereços vêm do QR e do hello da sessão.

## Marcos
- [x] 0. Setup / sondagem LM Studio
- [x] 1. Protocolo + vetores (C# e Kotlin) — 6 testes C#, 5 Kotlin
- [x] 2. Relay no Railway — wss://noc-relay-production.up.railway.app
- [x] 3. Companion core — Noc.Core (sessões E2E, LAN Kestrel, relay, jobs retomáveis, ModelManager). 8 testes (2 E2E reais com NOC_E2E=1)
- [x] 4. Android fatia vertical — pareamento QR (deep link) na LAN, teste de conexão, chat com streaming, Markdown+highlight, título automático
- [~] 5. UI completa Android — todas as telas escritas (Home, Chat, Histórico, Modelos, Perfis, Prompts, Ajustes, Computadores, Segurança, Diagnóstico, Onboarding, Pareamento); falta validar cada uma no emulador
- [~] 6. UI Companion (WPF) pronta: visão geral, dispositivos, atividade/segurança, ajustes, pareamento QR/código, aprovação SAS, bandeja, instância única, autostart. Falta instalador.
- [ ] 7. Validação ponta a ponta + polimento

## Log

### 2026-09-24
- Nome do produto: **Noc** (pedido do usuário; renomeado de "Lume").
- LM Studio: OpenAI-compat /v1/chat/completions com stream. Raciocínio vem em delta.reasoning_content.
  reasoning_effort:"none" desliga o raciocínio. seed/stop/top_k/min_p/repeat_penalty/penalties aceitos.
  Continuação real funciona (mensagem assistant final no histórico). usage chega no último chunk
  (stream_options.include_usage). Load: POST /api/v1/models/load {model, context_length, flash_attention} (~32 s, q4 32k = 21,5 GB VRAM).
- Relay: conexão ociosa por 400 s via Railway sobreviveu (ping do servidor a cada 25 s).
- Companion core validado ponta a ponta (xunit E2E): pareamento QR e por código+SAS via relay, streaming 86 tok/s
  pelo Railway, retomada após queda (seq contínuo), cancelamento, revogação derruba sessão ativa.
- Rodar E2E: `cd companion && NOC_E2E=1 dotnet test tests/Noc.Core.Tests` (LM Studio ligado).

### 2026-09-25
- App Android compila e roda (emulador Orla_API34). Ferramentas: `tools/dev.sh host|qr|code|shot|install`
  (Companion headless em .devdata, auto-aprova código). `tools/devhost.ps1` reinicia o host desacoplado.
- Bug corrigido: mudar startDestination do NavHost reiniciava a pilha → start fixado com remember.
- Testes Android: 5 cripto (vetores C#) + 7 Markdown.
- Pareamento por código validado ponta a ponta com o Companion real: SAS 612 701 igual nos dois lados, aprovado no PC.
  Bug corrigido: campo de código com hífen embaralhava a digitação (agora VisualTransformation).
- Ferramentas de automação Windows: tools/companion.ps1 (rebuild+restart), tools/uia.ps1 (UI Automation: -Invoke/-Texts),
  tools/winshot.ps1 (captura da janela). Emulador às vezes lento: use esperas de 2–3 s entre toques.
- Validado no emulador: rota remota forçada (Railway) com streaming 51–72 tok/s; resposta longa com 0,45% de frames
  janky (p99 25 ms, build debug); app em segundo plano → notificação "resposta pronta"; queda total de rede no meio →
  retomada sem perdas (120/120 linhas, 0 duplicatas, verificado no banco).
- Bugs corrigidos: corrida pareamento×laço principal abria 2ª sessão; teste/título ouviam a sessão antiga
  (agora ChatEngine.quickGenerate reassina após troca de sessão); "sempre remoto" exigia reconnect().
- Validado: Companion morto no meio da geração → celular mostra "Seu PC está offline · visto por último…" → PC volta →
  resposta marcada "interrompida" com "Gerar de novo". LM Studio parado → Home explica e o botão religa via lms.
  Relay republicado → Companion e celular reconectam sozinhos.
- Bug corrigido: fechamento 4404 do relay chegava como "send failed" 1006 (agora espera o CloseInfo real).
- Relay: railway.json (1 réplica, us-east4, healthcheck). README em relay/.

## Pendências (ordem de ataque) — atualizado 2026-09-25 01:45
- [x] Bug troca de PC (activePc com flatMapLatest + sessionPcId)
- [x] Jobs terminados retidos 24 h em disco (DPAPI) — teste E2E "sobrevive ao reinício"
- [x] Release APK com R8 + keystore aleatório (android/keystore.properties, gitignored)
- [x] Chat no release: corte por tokens + Continuar, parar + Continuar, editar (ramo 2/2), regenerar, excluir, selecionar texto
- [x] Busca sem acento; LaTeX → Unicode (fórmulas)
- [ ] Histórico: fixar, pasta, duplicar, exportar, arquivar (swipe) + desfazer, renomear
- [ ] Bibliotecas: criar perfil, criar prompt, padrão, fluxo "Escolher da biblioteca"
- [ ] Anexo de texto; imagem desabilitada p/ Qwen; caminho de visão (modelo com visão?)
- [ ] Modelos pelo celular: carregar/descarregar/recarregar contexto
- [ ] Revogar no PC → celular "desvinculado"; telas Dispositivos/Segurança/Diagnóstico no celular
- [ ] Modo escuro nos dois apps
- [ ] Processo morto no meio (am force-stop) → resumePending; Doze
- [ ] Conversa enorme (seed debug) + reduzir recarga do Room durante o stream
- [ ] Trocar entre 2 PCs (DevHost como 2º PC)
- [ ] Firewall (regra no instalador + aviso de rede Pública); publish self-contained; instalador Inno Setup; teste install/uninstall
- [ ] README, BLOCKERS (SmartScreen/Play Protect, backup do keystore, celular físico)
