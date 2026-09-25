# Protocolo Noc v1

Três partes falam entre si:

- **Celular** (Android): inicia as conexões.
- **Companion** (PC): atende as conexões e fala com o LM Studio.
- **Relay** (Railway): só encaminha bytes. Nunca vê o conteúdo.

O canal é cifrado de ponta a ponta entre celular e Companion, tanto na LAN quanto pelo relay.

## 1. Primitivas

| Uso | Algoritmo |
|---|---|
| Identidade (PC e celular) | ECDSA P-256 / SHA-256, assinatura **DER** (RFC 3279) |
| Troca de chaves por sessão | ECDH P-256 efêmero. O segredo é a coordenada X bruta (32 bytes) |
| Derivação | HKDF-SHA256 (RFC 5869) |
| Cifra | AES-256-GCM, tag de 16 bytes anexada ao texto cifrado (`ct‖tag`) |
| Chave pública no fio | SPKI DER (X.509 SubjectPublicKeyInfo) |
| Ponto efêmero no fio | não comprimido, 65 bytes (`04‖X‖Y`) |

`lp(x)` = tamanho em u32 big-endian seguido de `x`.
`id(pub)` = os 26 primeiros caracteres da base32 minúscula (RFC 4648, sem padding) de `SHA256(SPKI)`.
Isso vale para `pcId` e `deviceId`.

## 2. Frames (mensagens binárias de WebSocket)

O primeiro byte indica o tipo.

```
0x01 ClientHello  = 01 ‖ version(u8=1) ‖ mode(u8) ‖ ephC(65) ‖ nonceC(32)
0x02 ServerHello  = 02 ‖ ephS(65) ‖ nonceS(32) ‖ lp(pcPubSPKI) ‖ lp(sigS)
0x10 Encrypted    = 10 ‖ counter(u64 BE) ‖ ct‖tag
0x7F Error        = 7F ‖ utf8 código      (só antes da autenticação: "version", "busy")
```

`mode`: `0` = sessão de dispositivo já pareado, `1` = pareamento por QR, `2` = pareamento por código.

### Transcript e chaves

```
SH0 = 02 ‖ ephS ‖ nonceS ‖ lp(pcPubSPKI)                  (ServerHello sem a assinatura)
TH  = SHA256("NOC/1/transcript" ‖ lp(ClientHello) ‖ lp(SH0))
sigS = ECDSA(pcKey, "NOC/1/server" ‖ TH)
Z   = ECDH(eph, ephPeer)                                   (32 bytes)
OKM = HKDF(ikm=Z, salt=TH, info="NOC/1/keys", L=64)
kC2S = OKM[0..32], kS2C = OKM[32..64]
SAS = u32be(HKDF(ikm=Z, salt=TH, info="NOC/1/sas", L=4)) mod 1_000_000   → 6 dígitos
```

O celular verifica `sigS` com `pcPubSPKI`. Depois exige que:

- modo 0: `pcPub` seja a chave fixada no pareamento;
- modo 1: `pcPub` seja a chave que veio no QR;
- modo 2: o usuário confirme o SAS na tela do PC (TOFU protegido pelo SAS).

### Canal cifrado

`nonce(12) = dir(u32 BE) ‖ counter(u64 BE)`. `dir` é `1` para celular→PC e `2` para PC→celular.
AAD = `0x10`. O contador começa em 0 e sobe exatamente 1 por frame. Qualquer desvio encerra o canal.

### Autenticação do cliente (primeiro frame cifrado, celular→PC, JSON)

```
modo 0: {"k":"auth","device":"<deviceId>","sig":"<b64 ECDSA(deviceKey, "NOC/1/client" ‖ TH)>"}
modo 1: {"k":"pair","pairing":"<pairingId>","pub":"<b64 SPKI>","name":"...","model":"...",
         "mac":"<b64 HMAC-SHA256(secret, "NOC/1/pair" ‖ TH ‖ SPKI)>","sig":"..."}
modo 2: igual ao modo 1, com "code" no lugar de "pairing", e o segredo = bytes UTF-8 do segredo de 4 caracteres
```

Resposta do PC (primeiro frame cifrado, PC→celular):
`{"k":"welcome","device":"<deviceId>","pc":{...info...}}` ou `{"k":"denied","code":"revoked|unknown|expired|rejected|rate"}`.
No modo 2, o PC só responde depois que o usuário aprova no PC.

## 3. Mensagens da aplicação (JSON dentro do canal cifrado)

```
req  {"t":"req","id":N,"m":"<método>","p":{...}}
res  {"t":"res","id":N,"ok":true,"r":{...}} | {"t":"res","id":N,"ok":false,"e":{"code":"...","msg":"..."}}
evt  {"t":"evt","e":"<nome>","d":{...}}
```

Métodos: `status`, `models.list`, `models.load`, `models.unload`, `lms.start`,
`chat.start`, `chat.subscribe`, `chat.cancel`, `devices.list`, `devices.revoke`,
`devices.rename`, `security.log`, `ping`.

Eventos: `status` (mudou algo no PC ou no LM Studio), `job` (streaming), `model` (carregar/descarregar).

### Streaming retomável

O `jobId` é gerado pelo celular (UUID) para que um novo envio após uma queda seja idempotente.
O evento `job` tem o formato `{"job":id,"seq":n,"r":"<delta de raciocínio>","c":"<delta de conteúdo>"}`.
Os deltas são agrupados a cada ~40 ms.

O evento final tem `"end":{"reason":"stop|length|cancelled|error","usage":{...},"stats":{...}}`.
`chat.subscribe {job, from}` reenvia do buffer todos os eventos com `seq > from` e continua ao vivo.
O Companion guarda os jobs (cifrados com DPAPI em disco, para sobreviver a um reinício do PC) por 24 h depois do fim.

### Extensões da versão 1.1

O `welcome` traz `pc.features`; o app só usa o que o PC anuncia:

| Recurso | Métodos | O que faz |
|---|---|---|
| `tiers` | `tiers.set`, `models.default`, `models.update`, `models.plan` | Perfis Rápido/Inteligente/Profundo (`chat.start` aceita `"model":"tier:fast"`), modelo padrão, apelido/favorito/contexto por modelo, plano de carga pela VRAM. |
| `blobs` | `blob.has`, `blob.put` | Imagens enviadas uma vez, por SHA-256, em pedaços; a mensagem referencia `{"type":"image_ref","hash":…,"mime":…}`. |
| `stt` | `stt.status`, `stt.prepare`, `stt.begin`, `stt.chunk`, `stt.end`, `stt.cancel` | Ditado: PCM 16 kHz mono em pedaços durante a fala; `stt.end` devolve o texto. |
| `jobs2` | `jobs.list`, `jobs.get`, `jobs.prioritize` | Fila com fases, posição, prioridade e tarefas de fundo (`"background":true`, nunca trocam o modelo carregado). |
| `bench` | `models.bench` | Teste de desempenho real (TTFT, tok/s, carga, prefill, visão). |
| `library` | `models.library`, `models.scan` | Importação automática de GGUF da pasta Downloads. |

O evento `job` ganha `phase` (`queued` com `ahead`, `starting`, `loading` com `expectedSeconds`, `preparing`,
`thinking`, `generating`, `recovering`, `waiting_lm`) e `reset` (`{"reset":true,"why":…,"r":…,"c":"<texto completo até aqui>"}`): depois de
uma recuperação o Companion salta a sequência (+10000) e reenvia o retrato completo, e o app substitui em vez de
anexar. O `end` traz `model`, `name` e `stats` (`wallMs`, `queuedMs`, tok/s, TTFT).

## 4. Relay

```
wss://<relay>/v1/pc                      controle do PC (desafio-resposta com a chave do PC)
wss://<relay>/v1/accept?ch=<token>       PC aceita um canal de cliente
wss://<relay>/v1/connect?pc=<pcId>       celular pede um canal para o PC
GET  /v1/presence/<pcId>                 {"online":bool,"lastSeen":ms|null}
GET  /v1/pair/<lookup>                   {"pcId":...}   uso único, expira em 5 min
GET  /health
```

Autenticação do controle: o relay envia `{"t":"challenge","n":b64}`. O PC responde
`{"t":"auth","pub":b64 SPKI,"sig":b64 ECDSA(pc,"NOC/1/relay" ‖ n)}`. O relay deriva o `pcId` da chave.
O relay só vê chaves públicas, `pcId`, horários e o tamanho dos frames.
