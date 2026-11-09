// Node.js 22+: public clipboard checks against a running development deployment.
// Test entries expire automatically; no login or changes to application settings.
import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:8088').replace(/\/$/, '');
const cookies = new Map();
async function request(path, options = {}) {
  const headers = new Headers(options.headers);
  headers.set('Cookie', [...cookies].map(([name, value]) => `${name}=${value}`).join('; '));
  if (options.json !== undefined) {
    headers.set('Content-Type', 'application/json');
    options.body = JSON.stringify(options.json);
  }
  const response = await fetch(base + path, {...options, headers, redirect: 'manual'});
  for (const cookie of response.headers.getSetCookie()) {
    const [name, ...value] = cookie.split(';')[0].split('=');
    cookies.set(name, value.join('='));
  }
  return response;
}
async function create(content, customId) {
  const csrfResponse = await request('/api/auth/csrf');
  assert.equal(csrfResponse.status, 200);
  const csrf = await csrfResponse.json();
  return request('/api/clipboards', {
    method: 'POST', headers: {[csrf.headerName]: csrf.token}, json: {content, customId},
  });
}
async function run(name, check) {
  await check();
  console.log(`PASS ${name}`);
}

const content = '  粘贴板测试 🦋\n第二行\t保留空格  \n<script>alert("text only")</script>\n';
let clipboard;
await run('anonymous create requires CSRF', async () => {
  const response = await request('/api/clipboards', {method: 'POST', json: {content}});
  assert.equal(response.status, 403);
});
await run('anonymous save preserves text and fixes lifetime at ten minutes', async () => {
  const response = await create(content);
  assert.equal(response.status, 201, await response.clone().text());
  assert.match(response.headers.get('Cache-Control') || '', /no-store/);
  clipboard = await response.json();
  assert.equal(clipboard.content, content);
  assert.equal(Date.parse(clipboard.expiresAt) - Date.parse(clipboard.createdAt), 600_000);
  assert.ok(Math.abs(Date.parse(clipboard.createdAt) - Date.now()) < 30_000);
  assert.equal(new URL(clipboard.shareUrl).pathname, `/zt/${clipboard.id}`);
});
await run('another visitor can read without cookies and reads do not extend expiry', async () => {
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await fetch(`${base}/api/clipboards/${encodeURIComponent(clipboard.id)}`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Cache-Control') || '', /no-store/);
    const shared = await response.json();
    assert.equal(shared.content, content);
    assert.equal(shared.expiresAt, clipboard.expiresAt);
  }
  const page = await fetch(`${base}/zt/${encodeURIComponent(clipboard.id)}`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('Content-Type') || '', /text\/html/);
});
await run('blank and oversized input is rejected, exact length limit accepted', async () => {
  for (const invalid of [null, '', ' \t\n', 'a'.repeat(100_001)]) {
    const response = await create(invalid);
    assert.equal(response.status, 400, await response.clone().text());
  }
  const response = await create('a'.repeat(100_000));
  assert.equal(response.status, 201, await response.clone().text());
  const boundary = await response.json();
  assert.equal(boundary.content.length, 100_000);
});
await run('unknown or malformed links cannot be read', async () => {
  for (const id of ['00000000-0000-0000-0000-000000000000', 'invalid']) {
    assert.equal((await fetch(`${base}/api/clipboards/${id}`)).status, 404);
  }
});

const customId = `smoke-${crypto.randomUUID()}`;
await run('custom links preserve their name and reject duplicates without replacing content', async () => {
  const response = await create(content, `  ${customId}  `);
  assert.equal(response.status, 201, await response.clone().text());
  const original = await response.json();
  assert.equal(original.id, customId);
  assert.equal(new URL(original.shareUrl).pathname, `/zt/${customId}`);
  assert.equal(Date.parse(original.expiresAt) - Date.parse(original.createdAt), 600_000);
  const duplicate = await create('This must not replace the original text.', customId);
  assert.equal(duplicate.status, 409);
  assert.match((await duplicate.json()).message, /换|占用|使用/);
  const shared = await (await fetch(`${base}/api/clipboards/${customId}`)).json();
  assert.equal(shared.content, content);
  assert.equal(shared.expiresAt, original.expiresAt);
});
await run('concurrent requests for the same custom link have one winner', async () => {
  const csrf = await (await request('/api/auth/csrf')).json();
  const id = `race-${crypto.randomUUID()}`;
  const responses = await Promise.all(['first', 'second'].map(value => request('/api/clipboards', {
    method: 'POST', headers: {[csrf.headerName]: csrf.token}, json: {content: value, customId: id},
  })));
  assert.deepEqual(responses.map(response => response.status).sort(), [201, 409]);
  const winner = await responses.find(response => response.status === 201).json();
  const shared = await (await fetch(`${base}/api/clipboards/${id}`)).json();
  assert.equal(shared.content, winner.content);
  assert.equal(shared.expiresAt, winner.expiresAt);
});
await run('invalid custom links are rejected and leaving the name blank keeps random links', async () => {
  for (const invalid of ['ab', '-leading', '_leading', 'with space', 'with/slash', '中文链接', 'a'.repeat(65)]) {
    const response = await create('text', invalid);
    assert.equal(response.status, 400, await response.clone().text());
  }
  const response = await create('text', '   ');
  assert.equal(response.status, 201, await response.clone().text());
  assert.match((await response.json()).id, /^[A-Za-z0-9_-]{32}$/);
});

if (process.env.SMOKE_WAIT_FOR_EXPIRY === '1') {
  console.log(`Waiting for the original clipboard deadline: ${clipboard.expiresAt}`);
  while (Date.now() <= Date.parse(clipboard.expiresAt)) {
    await delay(Math.min(30_000, Date.parse(clipboard.expiresAt) - Date.now() + 50));
  }
  await run('clipboard is inaccessible after its original ten-minute deadline', async () => {
    const response = await fetch(`${base}/api/clipboards/${encodeURIComponent(clipboard.id)}`);
    assert.equal(response.status, 404);
  });
}
console.log('All clipboard checks passed. Test entries are removed automatically after ten minutes.');
