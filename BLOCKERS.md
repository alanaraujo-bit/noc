# Pendências que dependem de você

1. **Teste num celular físico fora de casa (4G/5G).** Não havia aparelho físico conectado. O caminho remoto foi validado de verdade: o emulador em modo "Sempre usar conexão remota" passou pela internet até o relay no Railway e voltou ao PC, com streaming, queda de rede no meio da resposta e retomada sem perdas.
   **Quando voltar:** instale o `Noc-1.0.0.apk`, pareie pelo QR, desligue o Wi-Fi do celular e converse.

2. **Guarde a chave de assinatura do Android.** Ela fica em `android/keystore/noc-release.jks`, e a senha está em `android/keystore.properties`. Os dois ficam fora do git de propósito. Sem eles, versões futuras do APK não conseguem atualizar a instalada: seria preciso desinstalar, o que apaga as conversas do celular. Faça um backup em local seguro.

3. **Avisos de segurança na primeira instalação** (normais para quem não compra certificado):
   - **Windows SmartScreen**: o instalador não tem assinatura de código paga. Clique em "Mais informações" e depois em "Executar assim mesmo".
   - **Android / Play Protect**: o APK vem de fora da Play Store. Permita "instalar apps desta fonte". O Play Protect pode pedir para "analisar o app"; pode confirmar.

4. **Caminho de imagens (visão) sem teste real.** Os seus modelos atuais (Qwen 27B) não têm visão, e o app corretamente desabilita o anexo de imagem para eles. Quando você baixar um modelo com visão no LM Studio, o envio de imagens fica disponível automaticamente. Esse fluxo ainda não foi testado ponta a ponta com um modelo real.
