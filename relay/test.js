// Teste de integração do relay: PC autentica, celular conecta, bytes fluem nos dois sentidos.
const crypto = require('crypto');
const WebSocket = require('ws');
const BASE = process.env.RELAY || 'ws://127.0.0.1:8080';
const HTTP = BASE.replace(/^ws/, 'http');
const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const spki = publicKey.export({ type: 'spki', format: 'der' });
const assert = (c, m) => { if (!c) { console.error('FAIL', m); process.exit(1); } else console.log('ok', m); };

(async () => {
  const ctl = new WebSocket(BASE + '/v1/pc');
  const pcId = await new Promise((res, rej) => {
    ctl.on('message', (d) => {
      const m = JSON.parse(d);
      if (m.t === 'challenge') {
        const sig = crypto.sign('sha256', Buffer.concat([Buffer.from('NOC/1/relay'), Buffer.from(m.n, 'base64')]), { key: privateKey, dsaEncoding: 'der' });
        ctl.send(JSON.stringify({ t: 'auth', pub: spki.toString('base64'), sig: sig.toString('base64') }));
      } else if (m.t === 'ok') res(m.pcId);
      else if (m.t === 'incoming') {
        const a = new WebSocket(BASE + '/v1/accept?ch=' + m.ch);
        a.on('message', (data) => a.send(Buffer.concat([Buffer.from('echo:'), data]), { binary: true }));
      }
    });
    ctl.on('close', (c, r) => rej(new Error('ctl closed ' + c + r)));
  });
  assert(/^[a-z2-7]{26}$/.test(pcId), 'pc auth -> ' + pcId);
  const pres = await (await fetch(HTTP + '/v1/presence/' + pcId)).json();
  assert(pres.online === true, 'presence online');

  const cl = new WebSocket(BASE + '/v1/connect?pc=' + pcId);
  cl.on('open', () => cl.send(Buffer.from([1, 2, 3])));
  const got = await new Promise((res) => cl.on('message', (d) => res(d)));
  assert(Buffer.compare(got, Buffer.concat([Buffer.from('echo:'), Buffer.from([1, 2, 3])])) === 0, 'splice echo');
  // mensagem grande
  const big = crypto.randomBytes(3 * 1024 * 1024);
  cl.send(big);
  const got2 = await new Promise((res) => cl.once('message', (d) => res(d)));
  assert(got2.length === big.length + 5, 'big frame 3MB');

  ctl.send(JSON.stringify({ t: 'pair.register', lookup: 'K7M4' }));
  await new Promise((r) => setTimeout(r, 300));
  const p1 = await fetch(HTTP + '/v1/pair/K7M4');
  assert(p1.status === 200 && (await p1.json()).pcId === pcId, 'pair lookup');
  const p2 = await fetch(HTTP + '/v1/pair/K7M4');
  assert(p2.status === 404, 'pair single use');

  const off = new WebSocket(BASE + '/v1/connect?pc=' + 'a'.repeat(26));
  const code = await new Promise((res) => off.on('close', (c) => res(c)));
  assert(code === 4404, 'offline pc -> 4404');

  cl.close(); ctl.close();
  await new Promise((r) => setTimeout(r, 300));
  const pres2 = await (await fetch(HTTP + '/v1/presence/' + pcId)).json();
  assert(pres2.online === false && pres2.lastSeen > 0, 'presence offline + lastSeen');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
