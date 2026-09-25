# Pendências que dependem de você

1. **Teste num celular físico fora de casa (4G/5G).** Não havia aparelho físico conectado ao PC durante o trabalho. O caminho remoto foi validado de verdade: o emulador em modo "Sempre usar conexão remota" passou pela internet até o relay no Railway e voltou ao PC, com streaming, imagens, ditado, queda de rede no meio da resposta, troca de rede e retomada sem perdas.
   **Quando voltar:** instale o `Noc-1.1.0.apk` por cima do 1.0 (as conversas continuam, a migração foi testada), desligue o Wi-Fi do celular e converse.

2. **Ditado com a sua voz, no microfone do celular.** A transcrição foi testada com o microfone real do emulador (silêncio, cancelar, segurar para falar, permissão negada, Companion fora do ar) e com gravações de fala em português (VLAN/MikroTik, C#, nomes próprios, fala baixa, ruído), sempre pelo mesmo caminho do app. O que não dava para fazer daqui é falar no microfone de um celular físico. Na primeira vez, confira se "VLAN 102", "MikroTik" e os seus termos saem certos; o que sair errado entra em **Ajustes → Voz e ditado → Dicionário**.

3. **Economia de bateria do fabricante (Motorola e outros).** Android puro deixa a tarefa terminar e a notificação chegar com o app fechado (testado, inclusive com o processo morto). Alguns fabricantes matam apps em segundo plano por conta própria. Se a notificação de "resposta pronta" não chegar com o app fechado: **Configurações → Apps → Noc → Bateria → Sem restrições**. "Forçar parada" nas configurações é um limite do Android: nada roda depois disso até você abrir o app de novo (a resposta continua no PC e aparece quando você abrir).

4. **Conexão direta na Wi-Fi neste PC.** O Companion deste computador foi (re)instalado só para o seu usuário, sem administrador, então **não há regra de firewall** para a conexão direta. Pelo relay tudo funciona. Para a conexão direta (mais rápida em casa), rode o `Noc-Companion-Setup-1.1.0.exe` e escolha "Instalar para todos os usuários" (pede administrador).

5. **Atualize os dois lados.** A combinação testada é app 1.1 + Companion 1.1. O app 1.1 só usa o que o PC anuncia (com um Companion antigo, o Diagnóstico mostra "Companion antigo" e perfis, imagens e voz ficam indisponíveis), mas versões misturadas não foram testadas de ponta a ponta. Um caso conhecido: o app 1.0 pode repetir um trecho da resposta se o Companion 1.1 reiniciar no meio de uma geração, porque ele não conhece o aviso de "retomada".

6. **Guarde a chave de assinatura do Android.** Ela fica em `android/keystore/noc-release.jks`, e a senha está em `android/keystore.properties`. Os dois ficam fora do git de propósito. Sem eles, versões futuras do APK não conseguem atualizar a instalada: seria preciso desinstalar, o que apaga as conversas do celular. Faça um backup em local seguro.

7. **Avisos de segurança na primeira instalação** (normais para quem não compra certificado):
   - **Windows SmartScreen**: o instalador não tem assinatura de código paga. Clique em "Mais informações" e depois em "Executar assim mesmo".
   - **Android / Play Protect**: o APK vem de fora da Play Store. Permita "instalar apps desta fonte". O Play Protect pode pedir para "analisar o app"; pode confirmar.

## Fase 4 (imagens: especialistas, LoRAs, edição) — versão 1.3.0

8. **Licenças dos especialistas (leia uma vez).** Tudo roda só no seu PC, para uso pessoal, o que todas permitem:
   - **Pony Diffusion V6 XL**: licença FAIPL modificada — é proibido oferecer geração paga com ele (vender acesso ao modelo).
   - **NoobAI XL**: FAIPL-1.0-SD — modelos derivados precisam manter a mesma licença.
   - **RealVisXL V5.0**: OpenRAIL++ (uso livre, inclusive comercial, respeitando as restrições de uso).
   - **FLUX.1 Dev**: não comercial. LoRAs do Civitai mostram as regras do autor em **Modelos → LoRAs → (LoRA)**.
9. **Espaço em disco.** Com os 5 motores e os componentes de edição, o motor de imagem ocupa ~115 GB em `F:\Noc\ImageEngine`
   (Z-Image 33 GB, FLUX 34 GB + 12 GB de otimização, 3 especialistas 21 GB, componentes 7 GB). Para liberar espaço: Companion →
   Imagens → Remover no modelo que não usar (as imagens criadas continuam).
10. **Consulta ao Civitai pelo hash.** Ao importar um LoRA/checkpoint, o PC envia só o SHA-256 do arquivo ao Civitai para descobrir
    nome, família, licença e palavras de ativação. Para desligar: `%LOCALAPPDATA%\Noc\settings.json` → `"ImageLookupCivitai": false`.
11. **Celular físico**: as telas novas (motor, LoRAs, guias, Estúdio, editor com pincel/expandir, galeria com filtros) foram testadas
    no emulador contra o PC real. No seu celular, confira o pincel com o dedo (espessura e precisão) e o "Expandir".
