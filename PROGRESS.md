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

### 2026-09-25 — Entrega 1.0.0 (pedido do usuário: "finaliza logo")
- Repositório: https://github.com/alanaraujo-bit/noc (público) · Release v1.0.0 com
  `Noc-Companion-Setup-1.0.0.exe` (Inno Setup, self-contained, firewall localsubnet, autostart) e `Noc-1.0.0.apk` (R8, assinado).
- Instalador testado: install/uninstall silencioso por usuário, app instalado sobe LAN+relay, desinstalação limpa Run key.
- Correções finais: tema do Companion (Light.xaml fixo no App.xaml vencia o ThemeManager), nomes de acessibilidade.
- Ainda NÃO validados na UI (código existe): pastas/arquivar-swipe/duplicar/exportar no histórico, criação de perfil/prompt e
  fluxo "Escolher da biblioteca", anexo de texto, carregar/descarregar modelo pelo celular, revogar no PC → celular,
  force-stop no meio da geração, Doze, conversa gigante (e a recarga do Room a cada 700 ms no streaming), troca entre 2 PCs.

---
# FASE 2 — Multimodalidade, modelos, voz, tarefas em segundo plano (iniciada 2026-09-25)

## Sondagens (de-risk) — resultados reais
- Importação: hard link para ~/.lmstudio/models/local/<Nome>-GGUF/ (sem cópia, sem mexer no Downloads). LM Studio indexa sozinho em ~3 s.
  mmproj na MESMA pasta do modelo => capabilities.vision=true (Gemma 4 confirmado).
- Qwen3.5-9B-abliterated-vision (Downloads) é um GGUF fora do padrão: rope.dimension_sections com 3 itens (llama.cpp quer 4),
  head_count_kv em array, tensor blk.N.ssm_dt sem ".bias", e 441 tensores de visão/MTP embutidos com nomes HF (v.*, mtp.*).
  LM Studio falhava ("Failed to load model"). REPARO validado: cópia saneada (pad 4, escalar, renomeia ssm_dt->ssm_dt.bias,
  remove v.*/mtp.*) + mmproj oficial lmstudio-community/Qwen3.5-9B-GGUF (sha256 conferido, projection_dim 4096 = embedding).
  Resultado: carrega em 18 s, 8,9 GB VRAM @32k, 111 tok/s, lê a imagem de teste perfeitamente.
- Gemma 4 26B-A4B + mmproj: 34 s para carregar, 20,7 GB @32k, ~120 tok/s, visão correta.
- REST /api/v1/models/load valida chaves estritamente: aceita context_length, flash_attention, offload_kv_cache_to_gpu,
  parallel, speculative_draft_mtp, echo_load_config. NÃO aceita gpu, ttl. Cargas pela API não têm TTL.
- `lms load <key> --estimate-only -c N -y` dá a estimativa oficial de VRAM (Gemma 32k 19,85 GiB; Qwen27B 32k 21,02 GiB; 64k 24,74).
- LM Studio NÃO tem API HTTP de transcrição. STT próprio: Whisper.net 1.9.1 + runtime Vulkan (CUDA runtime exige toolkit: falha),
  ggml-large-v3-turbo-q8_0 (874 MB). 200–650 ms por frase depois do aquecimento (1ª inferência ~10 s: compilar shaders).
  PT-BR excelente: "VLAN 102 na porta 2 do MikroTik… 24 como trunk", "C# usando HttpClient", ruído e voz baixa OK.
  Silêncio devolve "." → precisa de porta de energia + filtro de frases-fantasma.
- Fixtures em scratchpad: fixture-vision.png, vlan/csharp/long/noisy/quiet/silence.wav (gerados com SAPI pt-BR).

## Decisões da fase 2
- Marca continua "Noc" (o usuário renomeou explicitamente; "AIONIX AI" do texto é exemplo).
- "Perfis" passam a ser Rápido/Inteligente/Profundo (níveis de modelo, guardados no PC). Os antigos "perfis"
  (Programação, Pesquisa…) viram "Estilos" na interface.
- O PC é a fonte da verdade de modelos/perfis/tarefas; o celular é interface e cache.
- STT roda no PC (GPU), áudio vai cifrado pelo mesmo canal E2E; nada de nuvem.
- Compatibilidade: welcome anuncia "features"; app novo degrada com Companion antigo e vice-versa. Room: migrações reais.

## Fase 2 — Marco A: Companion (núcleo) — 2026-09-25
- Library/: leitor GGUF, reparo de GGUF legado (cópia corrigida, original intacto), importação por hard link do Downloads
  (e pastas extras) para ~/.lmstudio/models/local/<Nome>-GGUF, pareamento automático de mmproj, busca do mmproj oficial no HF
  (cabeçalho conferido via Range + SHA-256), FileSystemWatcher (importa GGUF novo 20 s depois de parar de crescer).
- Catálogo (models.json): nomes amigáveis, perfis Rápido/Inteligente/Profundo automáticos (até o usuário escolher),
  modelo padrão + pré-carga, apelido/favorito/oculto, perfil de desempenho por modelo, estimativas de VRAM (lms --estimate-only, cacheadas).
- Planejador de carga: maior contexto (até o alvo do perfil: 32k; Profundo 64k) que cabe na VRAM medida na hora; MTP quando o
  GGUF tem cabeça MTP; nova tentativa com menos contexto se faltar memória; 1 nova tentativa em falha genérica.
- Fila (JobManager v2): FIFO, até 2 simultâneas do mesmo modelo, outro modelo espera; fases reais
  queued(ahead)/starting/loading/preparing(images)/thinking/generating; diário DPAPI com checkpoint a cada 3 s;
  reinício do Companion => tarefa continua do ponto (continuação com a resposta parcial) — testado; queda do LM Studio => espera e retoma;
  watchdog de 3 min; cancelar na fila; priorizar; jobs.list/jobs.get; pausa durante benchmark.
- BlobStore: imagens por SHA-256 em pedaços (blob.has/blob.put), DPAPI, 7 dias; pedido cita image_ref (histórico não reenvia bytes).
- STT: Whisper large-v3-turbo q8 (Vulkan) no PC; stt.begin/chunk/end; porta de energia, filtro de frases-fantasma, ganho para voz baixa,
  dicionário pessoal no prompt + correção ortográfica/fonética. ~250 ms por frase.
- Benchmark real (carga, TTFT, tok/s x3, leitura de ~6k tokens, VRAM, visão com imagem de teste).
- Monitor de saúde: só religa o LM Studio com porta fechada (ou travado 2 min sem nada rodando). BUG REAL corrigido: timeout da
  listagem durante a carga de um modelo grande era lido como "parado" e o `lms server start` matava a carga.
- DiagLog (diag.log): ciclo de vida das tarefas e erros crus, sem conteúdo.
- Testes: 29 unitários; E2E fase 2 (8) + fase 1 (3) passando com os modelos reais.

## Fase 2 — Marco B: Android (2026-09-25)
- Perfis no topo do chat (⚡ Rápido / ◆ Inteligente / ◈ Profundo) com nome amigável, ícones de visão/raciocínio e estado de carga.
- Imagens: câmera, galeria (até 6), colar, compartilhar de outro app; EXIF, redução que preserva texto, JPEG 88/92, SHA-256;
  envio por blob com progresso real; miniaturas no composer e na mensagem; bloqueio elegante quando o modelo não enxerga.
- Ditado: gravação 16 kHz com forma de onda, toque ou segurar, cancelar arrastando; áudio vai em pedaços enquanto fala; Whisper no PC;
  dicionário pessoal; foco de áudio (ligação) e app em segundo plano param e transcrevem; falha de rede guarda o áudio para reenviar.
  Build debug aceita files/debug-mic.wav no lugar do microfone (emulador sem microfone). Release não tem esse caminho.
- Tarefas: fases reais no chat (fila/carregando ~s/analisando imagem/pensando/gerando/continuando no PC), notificação única de progresso
  (cronômetro, etapa, tokens, Parar), "X terminou" com prévia, Copiar/Tentar de novo, agrupamento, privacidade na tela bloqueada,
  deep link para a mensagem, sem aviso na conversa aberta; SyncWorker (WorkManager) após processo morto/reboot; Atividade; indicador global.
- Room v2 (migração real: messages.modelName).
### Testes reais no emulador (com o Companion/DevHost e os modelos reais)
- Imagem: galeria → Qwen 3.5 9B leu "link down na porta 7" ✔
- Voz: fala PT-BR real (WAV) → "Configura uma VLAN 102 na porta 2 do MikroTik e deixa a 24 como trunk." no campo ✔
- A (tela bloqueada): notificação de progresso e "Qwen 3.5 9B terminou" com a tela apagada; toque abre a conversa ✔
- D (sem internet no meio): "Continuando no seu PC…", PC terminou, rede voltou, resposta completa sincronizada, sem duplicar ✔
- I (processo morto com kill -9 em segundo plano): WorkManager reabriu, buscou e notificou ✔
- G (Parar pela notificação): cancelou no PC em 26 ms ✔
- C (Wi-Fi→dados→Wi-Fi): reassinou a mesma tarefa duas vezes, terminou ✔ — BUG corrigido: o laço principal apagava a sessão nova
  logo depois da troca de rede (corrida), deixando o app "conectado" sem sessão.
- H (Companion morto no meio): diário recuperado, continuação do ponto, celular reassinou; BUG corrigido: o diário ficava
  alguns segundos atrás do que o celular já tinha visto → numeração repetida e texto duplicado. Agora a recuperação pula a
  sequência (+10000) e manda um "retrato" completo (reset + texto) antes de continuar. Teste E2E cobre (reassina do último seq).
- BUG corrigido: títulos automáticos (tarefas de fundo) trocavam de modelo (20–36 s cada troca). Agora tarefas de fundo usam o
  modelo carregado e ficam atrás das do usuário. Teste E2E: Background_job_never_swaps_models.
- BUG corrigido: item da conversa ficava "gerando" depois do fim (Compose pulava a recomposição). Agora o item depende de liveIds
  e o status final do banco manda.
- F (duas conversas): "Gerando" + "Na fila · 1 tarefa antes desta" na Atividade ✔
- J (imagem com a tela bloqueada, Gemma): envio, troca de modelo, análise e notificação com a tela apagada ✔
- E (tarefa longa, Profundo 27B): 268 s com a tela bloqueada, "Qwen 3.8 27B terminou · em 268 s" ✔
- Benchmark real (Companion): Qwen 3.5 9B 106,2 tok/s, 1º token 89 ms, carga 7,5 s, leitura 3645 tok/s (7272 tokens), visão ok.
- Companion (janela): página Modelos, voz na visão geral, novos ajustes. Diagnóstico do celular: perfis, visão, voz, notificações.

## Fase 2 — Release 1.1.0 (2026-09-25)
Testes com o APK de release (R8, com.noc.app 1.1.0) pareado ao Companion 1.1.0 instalado pelo instalador:
- Atualização 1.0.0 → 1.1.0 por cima: conversas intactas (migração Room v1→v2) ✔
- Imagem pelo relay, ditado (silêncio → "Ditado transcrito"), processo morto → WorkManager notificou "Qwen 3.5 9B terminou" ✔
- Câmera (câmera virtual do emulador) → foto anexada (39 KB) ✔
- Duas imagens numa mensagem (foto + print): "Imagem 1… Imagem 2… link down na porta 7" ✔
- Compartilhar do app Arquivos → Noc: imagem chega anexada; com o Profundo (sem visão) aparece "Usar Qwen 3.5 9B"; resposta certa ✔
- Privacidade na tela bloqueada com PIN — BUG REAL corrigido: em "Só o aviso", a tela bloqueada mostrava modelo, título e trecho.
  Causa: VISIBILITY_PRIVATE só é respeitada quando o usuário escolheu "ocultar conteúdo sensível" no Android (o padrão é mostrar tudo),
  e o Android 14 ignora a visibilidade de canal definida pelo app (testado: mLockscreenVisibility=-1000). Correção: com o celular
  bloqueado a notificação já nasce genérica ("Noc · Sua resposta está pronta."); com o celular em uso ela é completa e, quando a tela
  apaga, é trocada pela genérica sem tocar de novo (receptor de SCREEN_OFF). Testado: bloqueado ✔, em uso → tela apagada ✔,
  "Ocultar" não mostra nada ✔. Padrão continua "Completo".
- Contexto do Profundo — melhoria: a estimativa do `lms --estimate-only` erra para mais (27B: 20,09 GiB estimados, 18,3 reais) e a
  espera pela VRAM do modelo anterior parava na primeira queda de 1 GB, contando o resto como "ocupado" → Profundo carregava com 24k.
  Agora a espera vai até a VRAM parar de cair e cada carga mede o uso real e guarda o fator (real/estimado, +2%, só entre 0,8 e 1,3;
  fora disso a medida é descartada). Resultado real: 9B fator 0,888; 27B 32k → medido 18,81 GiB → próxima carga 48k (19,91 GiB,
  22,35 de 24 GiB no total, tudo na GPU, TTFT 578 ms). Teste unitário do planejador com os números reais.
- BUG REAL no empacotamento corrigido: publicar sem `-p:IncludeNativeLibrariesForSelfExtract=true` (o comando do README) deixava as
  DLLs nativas do WPF fora do Noc.exe, e o instalador só copia o exe → o Companion instalado fechava ao abrir (DllNotFoundException).
  A propriedade agora está no .csproj; publicação feita em pasta limpa; reinstalado e conferido (voz pronta, modelo pré-carregado).
- BUG REAL (achado pela suíte E2E completa) corrigido: logo ao ligar, antes da primeira leitura da GPU, a distribuição automática
  dos perfis tratava a VRAM como infinita e punha o Q8_0 do 27B (29 GB) no Profundo; num PC sem nvidia-smi ficaria assim para sempre.
  Agora, sem saber a VRAM, cada modelo fica com a quantização mais leve, e a leitura da GPU refaz a distribuição. Teste unitário
  (VRAM desconhecida → Q4; 24 GB → Q4; 48 GB → Q8).
- Testes: Companion 46/46 (unitários + E2E com LM Studio e relay reais), Android 17/17 unitários.
