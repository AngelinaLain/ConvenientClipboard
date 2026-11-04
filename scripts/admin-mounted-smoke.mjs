// Node 22+. Run against the isolated QA project, not a deployment with user files.
import assert from 'node:assert/strict';
import { readFile, rename } from 'node:fs/promises';
import { resolve } from 'node:path';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:18088').replace(/\/$/, '');
const envPath = resolve(process.env.SMOKE_ENV_FILE || '.local/qa.env');
const settings = Object.fromEntries((await readFile(envPath, 'utf8')).split(/\r?\n/).filter(x => x && !x.startsWith('#')).map(x => { const i = x.indexOf('='); return [x.slice(0, i), x.slice(i + 1)]; }));
class Client {
  cookies = new Map();
  async request(path, options = {}) {
    const headers = { ...options.headers, Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (options.json !== undefined) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(options.json); }
    const response = await fetch(base + '/api' + path, { ...options, headers, cache: 'no-store' });
    for (const cookie of response.headers.getSetCookie()) { const [name, ...value] = cookie.split(';')[0].split('='); this.cookies.set(name, value.join('=')); }
    return response;
  }
  async csrf() { const r = await this.request('/auth/csrf'); assert.equal(r.status, 200); return r.json(); }
  async mutate(path, options = {}) { const csrf = await this.csrf(); return this.request(path, { method: 'POST', ...options, headers: { ...options.headers, [csrf.headerName]: csrf.token } }); }
  async login(username, password) { const r = await this.mutate('/auth/login', { json: { username, password } }); assert.ok(r.ok, await r.clone().text()); return r.json(); }
  async json(path) { const r = await this.request(path); assert.ok(r.ok, await r.clone().text()); return r.json(); }
}
const run = async (label, fn) => { await fn(); console.log('PASS ' + label); };
const admin = new Client(), user = new Client(), other = new Client(), anon = new Client();
await admin.login(settings.APP_ADMIN_USERNAME || 'admin', settings.APP_ADMIN_PASSWORD);
assert.equal((await admin.json('/admin/overview')).totalFiles, 0, 'Use an empty QA deployment; user files must remain untouched.');
assert.equal((await admin.json('/admin/users?page=0&size=1')).total, 1, 'Use a fresh QA deployment with only its bootstrap admin.');
assert.equal((await admin.json('/admin/providers')).length, 0, 'Use an empty QA provider configuration.');
const originalSettings = await admin.json('/admin/settings');
const runId = Date.now().toString(36);
const userIds = [], fileIds = [], providerIds = [];
const password = 'Qa-user-password-42';
const username = `qa_user_${runId}`, otherName = `qa_other_${runId}`;
let userId, otherId;
async function upload(client, suffix, content = Buffer.from('retention check'), permanent = false) {
  const form = new FormData(); form.set('file', new Blob([content]), `qa_${runId}_${suffix}.bin`); form.set('permanent', String(permanent));
  const r = await client.mutate('/files', { body: form }); assert.ok(r.ok, await r.clone().text()); const file = await r.json(); fileIds.push(file.id); return file;
}
async function removeFile(id) { const r = await admin.mutate(`/files/${id}`, { method: 'DELETE' }); assert.ok(r.ok || r.status === 404, await r.clone().text()); }
async function maintenance() { assert.ok((await admin.mutate('/admin/maintenance')).ok); }
async function waitRemote(id, type) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const page = await admin.json('/admin/files?page=0&size=100'); const file = page.items.find(x => x.id === id);
    if (file?.location === type && !(await admin.json('/admin/overview')).maintenanceRunning) return file;
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  throw new Error(`File ${id} did not move to ${type}`);
}
try {
  await run('admin user creation, pagination and role boundary', async () => {
    for (const name of [username, otherName]) {
      const r = await admin.mutate('/admin/users', { json: { username: name, password, role: 'USER' } }); assert.ok(r.ok, await r.clone().text()); userIds.push((await r.json()).id);
    }
    [userId, otherId] = userIds;
    await user.login(username, password); await other.login(otherName, password);
    const page = await admin.json(`/admin/users?page=0&size=1&query=${encodeURIComponent(runId)}`); assert.equal(page.total, 2); assert.equal(page.items.length, 1);
    const rejected = await user.request('/admin/users'); assert.equal(rejected.status, 403); assert.equal((await rejected.json()).code, 'FORBIDDEN');
    const list = await admin.json(`/admin/users?query=${encodeURIComponent(username)}`); assert.ok(!('passwordHash' in list.items[0]));
    const own = await admin.json('/auth/me'); assert.equal((await admin.mutate(`/admin/users/${own.id}`, { method: 'PATCH', json: { role: 'USER', enabled: true } })).status, 409);
  });
  await run('disable/re-enable and password reset invalidate old JWTs', async () => {
    assert.ok((await admin.mutate(`/admin/users/${userId}`, { method: 'PATCH', json: { role: 'USER', enabled: false } })).ok);
    const oldCookies = new Map(user.cookies); assert.equal((await user.request('/auth/me')).status, 401);
    assert.ok((await admin.mutate(`/admin/users/${userId}`, { method: 'PATCH', json: { role: 'USER', enabled: true } })).ok);
    user.cookies = oldCookies; assert.equal((await user.request('/auth/me')).status, 401);
    await user.login(username, password);
    assert.ok((await admin.mutate(`/admin/users/${userId}/password`, { json: { password: password + '-new' } })).ok);
    assert.equal((await user.request('/auth/me')).status, 401); await user.login(username, password + '-new');
  });
  await run('CSRF refresh allows repeated retention changes without reloading', async () => {
    const file = await upload(user, 'retention');
    const stale = await user.csrf(); user.cookies.delete('clipboard_csrf'); const fresh = await user.csrf();
    const rejected = await user.request(`/files/${file.id}/retention`, { method: 'PATCH', json: { permanent: true }, headers: { [stale.headerName]: stale.token } });
    assert.equal(rejected.status, 403); assert.equal((await rejected.json()).code, 'CSRF_INVALID');
    for (const permanent of [true, false, true, false]) { const r = await user.mutate(`/files/${file.id}/retention`, { method: 'PATCH', json: { permanent } }); assert.ok(r.ok, await r.clone().text()); assert.equal((await r.json()).permanent, permanent); }
    assert.ok(fresh.token);
    assert.equal((await other.mutate(`/files/${file.id}/retention`, { method: 'PATCH', json: { permanent: true } })).status, 403);
    const anonymous = await upload(anon, 'anonymous');
    const first = await admin.json('/admin/files?page=0&size=1'); const second = await admin.json('/admin/files?page=1&size=1');
    assert.equal(first.total, 2); assert.notEqual(first.items[0].id, second.items[0].id);
    assert.equal((await admin.mutate(`/admin/users/${userId}`, { method: 'DELETE' })).status, 409);
    assert.equal((await other.mutate(`/files/${anonymous.id}`, { method: 'DELETE' })).status, 403);
    await removeFile(file.id); await removeFile(anonymous.id);
  });
  await run('admin can promote/demote users and remove empty accounts', async () => {
    assert.ok((await admin.mutate(`/admin/users/${otherId}`, { method: 'PATCH', json: { role: 'ADMIN', enabled: true } })).ok);
    await other.login(otherName, password); assert.equal((await other.request('/admin/users')).status, 200);
    assert.ok((await admin.mutate(`/admin/users/${otherId}`, { method: 'PATCH', json: { role: 'USER', enabled: true } })).ok);
    assert.equal((await other.request('/auth/me')).status, 401);
    for (const id of userIds) assert.ok((await admin.mutate(`/admin/users/${id}`, { method: 'DELETE' })).ok);
  });
  assert.ok((await admin.mutate('/admin/settings', { method: 'PUT', json: { localCapacityBytes: 1048576, offloadThreshold: 50 } })).ok);
  for (const type of ['NFS', 'SMB']) {
    await run(`${type} mounted share offload, download, missing mount protection and cleanup`, async () => {
      const fixture = type.toLowerCase(); const provider = { name: 'qa_' + type, type, mountPath: '/mnt/remote/' + fixture, mountId: 'qa-' + fixture + '-share', enabled: true, priority: 0 };
      const response = await admin.mutate('/admin/providers', { json: provider }); assert.ok(response.ok, await response.clone().text()); const saved = await response.json(); providerIds.push(saved.id);
      assert.equal(saved.type, type); assert.ok(!saved.hasCredentials);
      const content = Buffer.alloc(700 * 1024, type === 'NFS' ? 0x4e : 0x53);
      const file = await upload(admin, fixture, content, true); assert.equal(file.location, 'LOCAL');
      await maintenance(); const migrated = await waitRemote(file.id, type); const token = new URL(migrated.shareUrl).pathname.split('/').at(-1);
      const download = await anon.request(`/shares/${token}/download`); assert.equal(download.status, 200); assert.deepEqual(Buffer.from(await download.arrayBuffer()), content);
      assert.equal((await admin.mutate(`/admin/providers/${saved.id}`, { method: 'DELETE' })).status, 409);
      const marker = resolve('.local/qa-mounts', fixture, '.clipboard-storage-id');
      await rename(marker, marker + '.offline');
      try {
        assert.equal((await anon.request(`/shares/${token}/download`)).status, 503);
        assert.equal((await admin.mutate(`/files/${file.id}`, { method: 'DELETE' })).status, 502);
        assert.ok((await admin.json('/admin/files?page=0&size=100')).items.some(x => x.id === file.id));
      } finally { await rename(marker + '.offline', marker); }
      await removeFile(file.id);
      assert.ok((await admin.mutate(`/admin/providers/${saved.id}`, { method: 'DELETE' })).ok);
    });
  }
  console.log('All administrator, retention and mounted-storage integration checks passed.');
} finally {
  let cleanupFailed = false;
  for (const id of fileIds) { try { await removeFile(id); } catch (e) { cleanupFailed = true; console.error('File cleanup failed: ' + id); } }
  for (const id of providerIds) { const r = await admin.mutate(`/admin/providers/${id}`, { method: 'DELETE' }).catch(() => null); if (r && !r.ok && r.status !== 404) cleanupFailed = true; }
  for (const id of userIds) { const r = await admin.mutate(`/admin/users/${id}`, { method: 'DELETE' }).catch(() => null); if (r && !r.ok && r.status !== 404) cleanupFailed = true; }
  const restored = await admin.mutate('/admin/settings', { method: 'PUT', json: originalSettings }).catch(() => null);
  if (!restored?.ok) cleanupFailed = true;
  if (cleanupFailed) { console.error('QA cleanup needs attention; only this run\'s test records were targeted.'); process.exitCode = 1; }
}
