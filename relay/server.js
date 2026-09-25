'use strict';
// Noc Relay — encaminha bytes cifrados entre o celular e o Companion do PC.
// Não guarda estado persistente, não conhece o conteúdo das conversas.

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const MAX_PAYLOAD = 24 * 1024 * 1024;       // imagens em base64 cabem com folga
const ACCEPT_TIMEOUT_MS = 12_000;
const PAIR_TTL_MS = 5 * 60_000;
const MAX_CHANNELS_PER_PC = 24;
const PENDING_BUFFER_LIMIT = 4 * 1024 * 1024;
const HEARTBEAT_MS = 25_000;
const VERSION = '1.0.0';

// ---------- util ----------
const b32alphabet = 'abcdefghijklmnopqrstuvwxyz234567';
function base32(buf) {
  let bits = 0, value = 0, out = '';
  for (const byte of buf) {
    value = (value << 8) | byte; bits += 8;
    while (bits >= 5) { out += b32alphabet[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += b32alphabet[(value << (5 - bits)) & 31];
  return out;
}
const idFromSpki = (spki) => base32(crypto.createHash('sha256').update(spki).digest()).slice(0, 26);
const now = () => Date.now();
const log = (event, fields = {}) => console.log(JSON.stringify({ ts: new Date().toISOString(), event, ...fields }));
const shortId = (id) => (id ? id.slice(0, 6) + '…' : '-');

function clientIp(req) {
  const xf = req.headers['x-forwarded-for'];
  if (typeof xf === 'string' && xf.length) return xf.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

// Token bucket por chave
class RateLimiter {
  constructor(capacity, refillPerSec) { this.capacity = capacity; this.refill = refillPerSec; this.buckets = new Map(); }
  take(key, cost = 1) {
    const t = now();
    let b = this.buckets.get(key);
    if (!b) { b = { tokens: this.capacity, t }; this.buckets.set(key, b); }
    b.tokens = Math.min(this.capacity, b.tokens + ((t - b.t) / 1000) * this.refill);
    b.t = t;
    if (b.tokens < cost) return false;
    b.tokens -= cost;
    return true;
  }
  sweep() {
    const t = now();
    for (const [k, b] of this.buckets) if (t - b.t > 10 * 60_000) this.buckets.delete(k);
  }
}
const connectLimiter = new RateLimiter(40, 0.5);   // celular abrindo canais
const pcAuthLimiter = new RateLimiter(12, 0.2);    // PCs autenticando
const pairLimiter = new RateLimiter(8, 0.1);       // consultas de código de pareamento
const httpLimiter = new RateLimiter(60, 1);

// ---------- estado em memória ----------
/** pcId -> { ws, since, channels:Set<token> } */
const pcs = new Map();
/** pcId -> lastSeen ms */
const lastSeen = new Map();
/** token -> { client, pcId, timer, queue:[], queued:number } */
const pending = new Map();
/** lookup -> { pcId, exp } */
const pairCodes = new Map();

// ---------- HTTP ----------
function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(data),
  });
  res.end(data);
}

const server = http.createServer((req, res) => {
  const ip = clientIp(req);
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/health') return json(res, 200, { ok: true, version: VERSION, pcs: pcs.size, pending: pending.size });
  if (!httpLimiter.take(ip)) return json(res, 429, { error: 'rate' });

  let m = url.pathname.match(/^\/v1\/presence\/([a-z2-7]{26})$/);
  if (m && req.method === 'GET') {
    const id = m[1];
    const online = pcs.has(id);
    return json(res, 200, { online, lastSeen: online ? now() : (lastSeen.get(id) ?? null), relayVersion: VERSION });
  }
  m = url.pathname.match(/^\/v1\/pair\/([A-Z0-9]{4})$/);
  if (m && req.method === 'GET') {
    if (!pairLimiter.take(ip)) return json(res, 429, { error: 'rate' });
    const entry = pairCodes.get(m[1]);
    if (!entry || entry.exp < now() || !pcs.has(entry.pcId)) {
      log('pair.lookup.miss', { ip });
      return json(res, 404, { error: 'not_found' });
    }
    pairCodes.delete(m[1]); // uso único
    log('pair.lookup.hit', { pc: shortId(entry.pcId) });
    return json(res, 200, { pcId: entry.pcId });
  }
  json(res, 404, { error: 'not_found' });
});

// ---------- WebSocket ----------
const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD, perMessageDeflate: false });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  const ip = clientIp(req);
  const reject = (code, text) => { socket.write(`HTTP/1.1 ${code} ${text}\r\nConnection: close\r\n\r\n`); socket.destroy(); };

  if (url.pathname === '/v1/pc') {
    if (!pcAuthLimiter.take(ip)) return reject(429, 'Too Many Requests');
    return wss.handleUpgrade(req, socket, head, (ws) => onPcControl(ws, ip));
  }
  if (url.pathname === '/v1/connect') {
    if (!connectLimiter.take(ip)) return reject(429, 'Too Many Requests');
    const pcId = url.searchParams.get('pc') || '';
    if (!/^[a-z2-7]{26}$/.test(pcId)) return reject(400, 'Bad Request');
    return wss.handleUpgrade(req, socket, head, (ws) => onClient(ws, pcId, ip));
  }
  if (url.pathname === '/v1/accept') {
    const token = url.searchParams.get('ch') || '';
    const p = pending.get(token);
    if (!p) return reject(404, 'Not Found');
    return wss.handleUpgrade(req, socket, head, (ws) => onAccept(ws, token));
  }
  reject(404, 'Not Found');
});

function markAlive(ws) { ws.isAlive = true; }
function setupHeartbeat(ws) { ws.isAlive = true; ws.on('pong', () => markAlive(ws)); }

function onPcControl(ws, ip) {
  setupHeartbeat(ws);
  const nonce = crypto.randomBytes(32);
  let pcId = null;
  const authTimer = setTimeout(() => { if (!pcId) ws.close(4401, 'auth_timeout'); }, 10_000);
  ws.send(JSON.stringify({ t: 'challenge', n: nonce.toString('base64'), v: VERSION }));

  ws.on('message', (data, isBinary) => {
    if (isBinary) return ws.close(4400, 'binary_not_allowed');
    let msg;
    try { msg = JSON.parse(data.toString('utf8')); } catch { return ws.close(4400, 'bad_json'); }

    if (!pcId) {
      if (msg.t !== 'auth') return ws.close(4401, 'auth_required');
      try {
        const spki = Buffer.from(msg.pub, 'base64');
        const key = crypto.createPublicKey({ key: spki, format: 'der', type: 'spki' });
        if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') throw new Error('curve');
        const signed = Buffer.concat([Buffer.from('NOC/1/relay'), nonce]);
        const ok = crypto.verify('sha256', signed, { key, dsaEncoding: 'der' }, Buffer.from(msg.sig, 'base64'));
        if (!ok) throw new Error('sig');
        pcId = idFromSpki(spki);
      } catch (e) {
        log('pc.auth.fail', { ip, reason: e.message });
        return ws.close(4401, 'auth_failed');
      }
      clearTimeout(authTimer);
      const prev = pcs.get(pcId);
      if (prev) { prev.replaced = true; prev.ws.close(4409, 'replaced'); }
      pcs.set(pcId, { ws, since: now(), channels: new Set() });
      ws.pcId = pcId;
      log('pc.online', { pc: shortId(pcId) });
      ws.send(JSON.stringify({ t: 'ok', pcId }));
      return;
    }

    switch (msg.t) {
      case 'ping': ws.send(JSON.stringify({ t: 'pong', ts: now() })); break;
      case 'pair.register': {
        const lookup = String(msg.lookup || '');
        if (!/^[A-Z0-9]{4}$/.test(lookup)) return;
        const existing = pairCodes.get(lookup);
        if (existing && existing.exp > now() && existing.pcId !== pcId) {
          ws.send(JSON.stringify({ t: 'pair.conflict', lookup }));
          return;
        }
        // um código ativo por PC
        for (const [k, v] of pairCodes) if (v.pcId === pcId) pairCodes.delete(k);
        pairCodes.set(lookup, { pcId, exp: now() + PAIR_TTL_MS });
        ws.send(JSON.stringify({ t: 'pair.registered', lookup, ttl: PAIR_TTL_MS }));
        break;
      }
      case 'pair.cancel':
        for (const [k, v] of pairCodes) if (v.pcId === pcId) pairCodes.delete(k);
        break;
      case 'reject': {
        const p = pending.get(String(msg.ch || ''));
        if (p && p.pcId === pcId) closePending(msg.ch, 4403, 'rejected');
        break;
      }
      default: break;
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimer);
    if (!pcId) return;
    const entry = pcs.get(pcId);
    if (entry && entry.ws === ws) {
      pcs.delete(pcId);
      lastSeen.set(pcId, now());
      for (const [k, v] of pairCodes) if (v.pcId === pcId) pairCodes.delete(k);
      log('pc.offline', { pc: shortId(pcId) });
    }
  });
  ws.on('error', () => {});
}

function closePending(token, code, reason) {
  const p = pending.get(token);
  if (!p) return;
  pending.delete(token);
  clearTimeout(p.timer);
  const pc = pcs.get(p.pcId);
  pc?.channels.delete(token);
  try { p.client.close(code, reason); } catch {}
}

function onClient(client, pcId, ip) {
  setupHeartbeat(client);
  const pc = pcs.get(pcId);
  if (!pc) {
    const seen = lastSeen.get(pcId);
    client.close(4404, seen ? `pc_offline:${seen}` : 'pc_offline');
    return;
  }
  if (pc.channels.size >= MAX_CHANNELS_PER_PC) return client.close(4413, 'too_many_channels');

  const token = crypto.randomBytes(18).toString('base64url');
  const p = { client, pcId, queue: [], queued: 0, timer: null };
  p.timer = setTimeout(() => {
    log('channel.accept_timeout', { pc: shortId(pcId) });
    closePending(token, 4408, 'pc_timeout');
  }, ACCEPT_TIMEOUT_MS);
  pending.set(token, p);
  pc.channels.add(token);

  client.on('message', (data, isBinary) => {
    if (!pending.has(token)) return;
    p.queued += data.length;
    if (p.queued > PENDING_BUFFER_LIMIT) return closePending(token, 4413, 'buffer');
    p.queue.push({ data, isBinary });
  });
  client.on('close', () => {
    if (pending.has(token)) {
      pending.delete(token);
      clearTimeout(p.timer);
      pc.channels.delete(token);
    }
  });
  client.on('error', () => {});

  pc.ws.send(JSON.stringify({ t: 'incoming', ch: token }));
}

function onAccept(pcSide, token) {
  const p = pending.get(token);
  if (!p) return pcSide.close(4404, 'gone');
  pending.delete(token);
  clearTimeout(p.timer);
  setupHeartbeat(pcSide);
  const { client, pcId } = p;
  const pc = pcs.get(pcId);
  client.removeAllListeners('message');
  client.removeAllListeners('close');
  const t0 = now();
  let bytes = 0;

  for (const m of p.queue) pcSide.send(m.data, { binary: m.isBinary });
  p.queue = [];

  client.on('message', (data, isBinary) => { bytes += data.length; if (pcSide.readyState === 1) pcSide.send(data, { binary: isBinary }); });
  pcSide.on('message', (data, isBinary) => { bytes += data.length; if (client.readyState === 1) client.send(data, { binary: isBinary }); });

  const safeCode = (c) => (c >= 3000 && c <= 4999) || c === 1000 ? c : 1000;
  let closed = false;
  const finish = (from, code, reason) => {
    if (closed) return;
    closed = true;
    pc?.channels.delete(token);
    const other = from === client ? pcSide : client;
    try { other.close(safeCode(code), reason?.toString().slice(0, 100)); } catch {}
    log('channel.closed', { pc: shortId(pcId), secs: Math.round((now() - t0) / 1000), kb: Math.round(bytes / 1024) });
  };
  client.on('close', (code, reason) => finish(client, code, reason));
  pcSide.on('close', (code, reason) => finish(pcSide, code, reason));
  client.on('error', () => {});
  pcSide.on('error', () => {});
  log('channel.open', { pc: shortId(pcId) });
}

// Heartbeat: mantém o proxy do provedor acordado e derruba sockets mortos.
setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
}, HEARTBEAT_MS);

setInterval(() => {
  const t = now();
  for (const [k, v] of pairCodes) if (v.exp < t) pairCodes.delete(k);
  for (const [k, v] of lastSeen) if (t - v > 30 * 24 * 3600_000) lastSeen.delete(k);
  connectLimiter.sweep(); pcAuthLimiter.sweep(); pairLimiter.sweep(); httpLimiter.sweep();
}, 60_000);

server.listen(PORT, () => log('relay.start', { port: PORT, version: VERSION }));

process.on('SIGTERM', () => { log('relay.stop'); server.close(); process.exit(0); });
