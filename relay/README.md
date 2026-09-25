# Noc Relay

Encaminhador sem estado persistente entre o celular e o Noc Companion. Só vê bytes cifrados de ponta a ponta.

- Deploy: Railway, projeto `noc-relay`, `wss://noc-relay-production.up.railway.app`.
- **Exatamente 1 réplica**: o registro dos PCs online fica em memória (a conexão de controle do PC e a do
  celular precisam cair na mesma instância). Região `us-east4` (a mais próxima do Brasil).
- Rodar localmente: `PORT=8080 node server.js` · Testes: `node test.js` (ou `RELAY=wss://... node test.js`).
- Publicar: `railway up --detach` nesta pasta.
