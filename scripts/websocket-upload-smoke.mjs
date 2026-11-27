// Node 22+. Run only against an empty isolated QA deployment with SMOKE_WS_QA=1.
import assert from 'node:assert/strict';
import {createHash, randomBytes, randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';

assert.equal(process.env.SMOKE_WS_QA, '1', 'Set SMOKE_WS_QA=1 for an isolated QA deployment');
const base = (process.env.SMOKE_BASE_URL || 'http://localhost:18088').replace(/\/$/, '');
const settings = Object.fromEntries((await readFile(process.env.SMOKE_ENV_FILE || '.local/qa.env', 'utf8'))
  .split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => {
    const split = line.indexOf('=');
    return [line.slice(0, split), line.slice(split + 1).replace(/^['"]|['"]$/g, '')];
  }));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const chunks = [Buffer.alloc(8 * 1024 * 1024, 7), Buffer.alloc(8 * 1024 * 1024, 8), Buffer.from('resume')];
const id = randomUUID(), token = randomBytes(32).toString('hex');
const manifest = {id, originalName: `ws-smoke-${id}.bin`, contentType: 'application/octet-stream',
  sizeBytes: chunks.reduce((sum, bytes) => sum + bytes.length, 0), permanent: false, chunkHashes: chunks.map(hash)};

class Client {
  cookies = new Map();
  async request(path, options = {}) {
    const headers = {...options.headers, Cookie: [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; ')};
    const response = await fetch(`${base}/api${path}`, {...options, headers, signal: AbortSignal.timeout(60000)});
    for (const cookie of response.headers.getSetCookie()) {
      const [key, value] = cookie.split(';')[0].split('='); this.cookies.set(key, value);
    }
    return response;
  }
  async mutate(path, method = 'POST', body, headers = {}) {
    const csrf = await (await this.request('/auth/csrf')).json();
    const response = await this.request(path, {method, body: body === undefined ? undefined : JSON.stringify(body),
      headers: {...headers, ...(body === undefined ? {} : {'Content-Type': 'application/json'}), [csrf.headerName]: csrf.token}});
    return response;
  }
}
const admin = new Client(), guest = new Client();
const login = await admin.mutate('/auth/login', 'POST',
  {username: settings.APP_ADMIN_USERNAME || 'admin', password: settings.APP_ADMIN_PASSWORD});
assert.equal(login.status, 200, await login.text());
const overview = await (await admin.request('/admin/overview')).json();
assert.equal(overview.totalFiles, 0, 'Use a fresh empty QA deployment');
const created = await guest.mutate('/uploads', 'POST', manifest, {'X-Upload-Token': token});
assert.equal(created.status, 200, await created.text());

async function browserHandshake() {
  const url = new URL('/api/upload-stream', base);
  const transport = url.protocol === 'https:' ? https : http;
  await new Promise((resolve, reject) => {
    const request = transport.request(url, {headers: {
      Origin: new URL(base).origin,
      Connection: 'Upgrade',
      Upgrade: 'websocket',
      'Sec-WebSocket-Version': '13',
      'Sec-WebSocket-Key': randomBytes(16).toString('base64')
    }});
    request.setTimeout(10000, () => request.destroy(new Error('Browser-style WebSocket handshake timed out')));
    request.on('upgrade', (response, socket) => {
      socket.destroy();
      response.statusCode === 101 ? resolve() : reject(new Error(`Handshake returned ${response.statusCode}`));
    });
    request.on('response', response => {
      response.resume();
      reject(new Error(`Browser-style WebSocket handshake returned ${response.statusCode}`));
    });
    request.on('error', reject);
    request.end();
  });
}

async function connection() {
  const url = base.replace(/^http/, 'ws') + '/api/upload-stream';
  const socket = new WebSocket(url);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WebSocket authentication timeout')), 20000);
    socket.addEventListener('open', () => socket.send(JSON.stringify({id, token})));
    socket.addEventListener('message', event => {
      const message = JSON.parse(String(event.data));
      clearTimeout(timer);
      if (message.type === 'ready') resolve();
      else reject(new Error(JSON.stringify(message)));
    }, {once: true});
    socket.addEventListener('error', event => {
      clearTimeout(timer);
      reject(new Error(event.error?.message || 'WebSocket handshake failed'));
    }, {once: true});
  });
  return socket;
}
async function part(socket, index) {
  const frame = Buffer.concat([Buffer.alloc(4), chunks[index]]);
  frame.writeInt32BE(index);
  const receipt = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Part acknowledgement timeout')), 60000);
    socket.addEventListener('message', event => {
      clearTimeout(timer);
      const message = JSON.parse(String(event.data));
      if (message.type === 'ack' && message.index === index) resolve();
      else reject(new Error(JSON.stringify(message)));
    }, {once: true});
    socket.addEventListener('error', event => {
      clearTimeout(timer);
      reject(new Error(event.error?.message || 'WebSocket part failed'));
    }, {once: true});
  });
  socket.send(frame);
  await receipt;
}
let fileId;
try {
  await browserHandshake();
  const [first, second] = await Promise.all([connection(), connection()]);
  await Promise.all([part(first, 0), part(second, 1)]);
  first.close(); second.close();
  const progress = await (await guest.request(`/uploads/${id}`, {headers: {'X-Upload-Token': token}})).json();
  assert.deepEqual(progress.receivedChunks, [0, 1]);
  const resumed = await connection();
  await part(resumed, 2);
  resumed.close();
  const completion = await guest.mutate(`/uploads/${id}/complete`, 'POST', undefined, {'X-Upload-Token': token});
  assert.equal(completion.status, 200, await completion.clone().text());
  const file = (await completion.json()).file;
  fileId = file.id;
  const shareToken = new URL(file.shareUrl).pathname.split('/').pop();
  const download = await (await guest.request(`/shares/${shareToken}/download`)).arrayBuffer();
  assert.equal(hash(Buffer.from(download)), hash(Buffer.concat(chunks)));
  console.log('PASS concurrent WebSocket parts, resume and download SHA-256');
} finally {
  if (fileId) await admin.mutate(`/files/${fileId}`, 'DELETE');
  else await guest.mutate(`/uploads/${id}`, 'DELETE', undefined, {'X-Upload-Token': token});
}
