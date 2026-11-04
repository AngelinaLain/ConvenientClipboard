// Node.js 22+: end-to-end checks against a running deployment. Creates test users.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:8088').replace(/\/$/, '');
const env = Object.fromEntries((await readFile(new URL('../.env', import.meta.url), 'utf8').catch(() => '')).split(/\r?\n/).filter(x => x && !x.startsWith('#')).map(x => { const i = x.indexOf('='); return [x.slice(0, i), x.slice(i + 1)]; }));
class Client {
  cookies = new Map();
  async request(path, options = {}) {
    const headers = { ...options.headers, Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (options.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(options.json);
    }
    const response = await fetch(base + path, { ...options, headers, redirect: 'manual' });
    for (const cookie of response.headers.getSetCookie()) {
      const [name, ...value] = cookie.split(';')[0].split('=');
      this.cookies.set(name, value.join('='));
    }
    return response;
  }
  async mutate(path, options = {}) {
    const csrfResponse = await this.request('/api/auth/csrf');
    assert.equal(csrfResponse.status, 200);
    const csrf = await csrfResponse.json();
    return this.request(path, { method: 'POST', ...options, headers: { ...options.headers, [csrf.headerName]: csrf.token } });
  }
}
const run = async (name, fn) => { await fn(); console.log(`PASS ${name}`); };
const anon = new Client();
const owner = new Client();
const other = new Client();
const created = [];
const suffix = Date.now().toString(36);
const payload = `Convenient Clipboard integration check ${suffix} 中文\n`;
function multipart(permanent = false) {
  const form = new FormData();
  form.set('file', new Blob([payload], { type: 'text/plain' }), '测试文件.txt');
  form.set('permanent', String(permanent));
  return form;
}
await run('public configuration and CSRF protection', async () => {
  const r = await anon.request('/api/public/config');
  assert.equal(r.status, 200);
  assert.equal((await r.json()).retentionDays, 7);
  assert.equal((await anon.request('/api/files', { method: 'POST', body: multipart() })).status, 403);
  assert.equal((await anon.request('/api/admin/overview')).status, 401);
});
await run('anonymous upload, seven-day expiry, share and byte-exact download', async () => {
  const r = await anon.mutate('/api/files', { body: multipart() });
  assert.ok(r.ok, `${r.status} ${await r.clone().text()}`);
  const file = await r.json();
  created.push(file);
  assert.equal(file.permanent, false);
  assert.ok(Math.abs(new Date(file.expiresAt) - Date.now() - 7 * 86400000) < 60000);
  const token = new URL(file.shareUrl).pathname.split('/').at(-1);
  assert.equal((await anon.request(`/api/shares/${token}`)).status, 200);
  const download = await anon.request(`/api/shares/${token}/download`);
  assert.equal(download.status, 200);
  assert.match(download.headers.get('content-disposition'), /attachment/i);
  assert.equal(await download.text(), payload);
});
await run('anonymous users cannot save permanently', async () => {
  assert.equal((await anon.mutate('/api/files', { body: multipart(true) })).status, 401);
});
await run('registration, JWT cookie and role boundary', async () => {
  for (const [client, username] of [[owner, `smoke_${suffix}`], [other, `other_${suffix}`]]) {
    const r = await client.mutate('/api/auth/register', { json: { username, password: 'Smoke-test-password-42' } });
    assert.ok(r.ok, `${r.status} ${await r.clone().text()}`);
    assert.equal((await r.json()).role, 'USER');
    assert.equal((await client.request('/api/auth/me')).status, 200);
    assert.equal((await client.request('/api/admin/overview')).status, 403);
  }
});
await run('explicit permanent retention, ownership checks and retention change', async () => {
  const r = await owner.mutate('/api/files', { body: multipart(true) });
  assert.ok(r.ok, await r.clone().text());
  const file = await r.json();
  created.push(file);
  assert.equal(file.permanent, true);
  assert.equal(file.expiresAt, null);
  assert.equal((await other.mutate(`/api/files/${file.id}`, { method: 'DELETE' })).status, 403);
  assert.equal((await other.mutate(`/api/files/${file.id}/retention`, { method: 'PATCH', json: { permanent: false } })).status, 403);
  const changed = await owner.mutate(`/api/files/${file.id}/retention`, { method: 'PATCH', json: { permanent: false } });
  assert.ok(changed.ok, await changed.clone().text());
  assert.equal((await changed.json()).permanent, false);
  const list = await (await owner.request('/api/files')).json();
  assert.ok(list.some(x => x.id === file.id));
  assert.ok(!list.some(x => x.id === created[0].id));
  assert.ok((await owner.mutate(`/api/files/${file.id}`, { method: 'DELETE' })).ok);
});
if (process.env.APP_ADMIN_PASSWORD || env.APP_ADMIN_PASSWORD) {
  const admin = new Client();
  await run('administrator login, capacity settings and maintenance', async () => {
    const r = await admin.mutate('/api/auth/login', { json: { username: process.env.APP_ADMIN_USERNAME || env.APP_ADMIN_USERNAME || 'admin', password: process.env.APP_ADMIN_PASSWORD || env.APP_ADMIN_PASSWORD } });
    assert.ok(r.ok, await r.clone().text());
    assert.equal((await r.json()).role, 'ADMIN');
    const overview = await admin.request('/api/admin/overview');
    assert.equal(overview.status, 200);
    assert.ok((await overview.json()).totalFiles >= 1);
    const settings = await (await admin.request('/api/admin/settings')).json();
    const saved = await admin.mutate('/api/admin/settings', { method: 'PUT', json: settings });
    assert.ok(saved.ok, await saved.clone().text());
    assert.equal((await admin.request('/api/admin/providers')).status, 200);
    assert.ok((await admin.mutate('/api/admin/maintenance')).ok);
    assert.ok((await admin.mutate(`/api/files/${created[0].id}`, { method: 'DELETE' })).ok);
  });
}
await run('logout revokes JWT and clears access', async () => {
  const previousCookies = new Map(owner.cookies);
  assert.ok((await owner.mutate('/api/auth/logout')).ok);
  assert.equal((await owner.request('/api/auth/me')).status, 401);
  owner.cookies = previousCookies;
  assert.equal((await owner.request('/api/auth/me')).status, 401);
});
console.log('All end-to-end checks passed. Test users remain; test files were removed when admin credentials were available.');
