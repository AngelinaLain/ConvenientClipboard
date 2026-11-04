// Node.js 22+. Use an empty, isolated QA deployment. Never point this at user data.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:18088').replace(/\/$/, '');
const envPath = resolve(process.env.SMOKE_ENV_FILE || '.local/qa.env');
const env = Object.fromEntries((await readFile(envPath, 'utf8')).split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => {
    const position = line.indexOf('=');
    let value = line.slice(position + 1);
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    return [line.slice(0, position), value];
  }));
const MiB = 1024 ** 2, GiB = 1024 ** 3;

class Client {
  cookies = new Map();
  async request(path, { json, ...options } = {}) {
    const headers = { ...options.headers, Cookie: [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; ') };
    if (json !== undefined) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(json); }
    const response = await fetch(base + '/api' + path, { ...options, headers, signal: AbortSignal.timeout(180000) });
    for (const cookie of response.headers.getSetCookie()) {
      const [key, ...value] = cookie.split(';')[0].split('='); this.cookies.set(key, value.join('='));
    }
    return response;
  }
  async mutate(path, options = {}) {
    const response = await this.request('/auth/csrf'); assert.equal(response.status, 200);
    const csrf = await response.json();
    return this.request(path, { method: 'POST', ...options, headers: { ...options.headers, [csrf.headerName]: csrf.token } });
  }
  async json(path) { const response = await this.request(path); assert.equal(response.status, 200, await response.clone().text()); return response.json(); }
}
const admin = new Client(), anon = new Client();
const files = new Set();
const run = async (name, action) => { await action(); console.log(`PASS ${name}`); };
let original;

async function configure(maxUploadBytes, extra = {}) {
  const json = { ...original, localCapacityBytes: 4 * GiB, maxUploadBytes, ...extra };
  const response = await admin.mutate('/admin/settings', { method: 'PUT', json });
  assert.equal(response.status, 200, await response.clone().text());
  assert.equal((await response.json()).maxUploadBytes, maxUploadBytes);
  assert.equal((await anon.json('/public/config')).maxUploadBytes, maxUploadBytes);
}

// Generated chunks keep the >1 GiB check bounded in memory and avoid a large host fixture.
async function upload(bytes, { chunked = false } = {}) {
  const boundary = 'clipboard-upload-limit-smoke';
  const prefix = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="upload-limit-check.bin"\r\nContent-Type: application/octet-stream\r\n\r\n`);
  const suffix = Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="permanent"\r\n\r\nfalse\r\n--${boundary}--\r\n`);
  const chunk = Buffer.alloc(64 * 1024, 0x61);
  async function* body() {
    yield prefix;
    for (let remaining = bytes; remaining > 0;) {
      const size = Math.min(remaining, chunk.length); yield chunk.subarray(0, size); remaining -= size;
    }
    yield suffix;
  }
  const headers = { 'Content-Type': `multipart/form-data; boundary=${boundary}` };
  if (!chunked) headers['Content-Length'] = String(prefix.length + bytes + suffix.length);
  const response = await anon.mutate('/files', { headers, body: body(), duplex: 'half' });
  if (response.ok) {
    const file = await response.json(); files.add(file.id); assert.equal(file.sizeBytes, bytes);
    return { status: response.status, file };
  }
  assert.match(response.headers.get('content-type') || '', /application\/json/);
  return { status: response.status, error: await response.json() };
}

async function download(file, bytes) {
  const token = new URL(file.shareUrl).pathname.split('/').at(-1);
  const response = await anon.request(`/shares/${token}/download`); assert.equal(response.status, 200);
  assert.equal(Number(response.headers.get('content-length')), bytes);
  const actual = createHash('sha256'); let count = 0;
  for await (const chunk of response.body) { count += chunk.length; actual.update(chunk); }
  const expected = createHash('sha256'); const chunk = Buffer.alloc(64 * 1024, 0x61);
  for (let remaining = bytes; remaining > 0;) { const length = Math.min(remaining, chunk.length); expected.update(chunk.subarray(0, length)); remaining -= length; }
  assert.equal(count, bytes); assert.equal(actual.digest('hex'), expected.digest('hex'));
}

try {
  await run('administrator-only settings and empty QA preflight', async () => {
    const login = await admin.mutate('/auth/login', { json: { username: env.APP_ADMIN_USERNAME || 'admin', password: env.APP_ADMIN_PASSWORD } });
    assert.equal(login.status, 200); assert.equal((await login.json()).role, 'ADMIN');
    assert.equal((await admin.json('/admin/overview')).totalFiles, 0, 'Use an empty QA deployment.');
    assert.equal((await admin.json('/admin/providers')).filter(provider => provider.enabled).length, 0, 'Disable QA providers to keep files local.');
    original = await admin.json('/admin/settings');
    assert.equal((await anon.mutate('/admin/settings', { method: 'PUT', json: original })).status, 401);
  });
  let existing;
  await run('live limit, exact file boundary and byte-exact download', async () => {
    await configure(2 * MiB);
    const accepted = await upload(2 * MiB); assert.equal(accepted.status, 200); existing = accepted.file;
    await download(existing, 2 * MiB);
    const rejected = await upload(2 * MiB + 1); assert.equal(rejected.status, 413);
    assert.equal((await admin.json('/admin/overview')).totalFiles, 1);
  });
  await run('chunked streams enforce the live file limit and clean rejected uploads', async () => {
    assert.equal((await upload(2 * MiB + 1, { chunked: true })).status, 413);
    const overview = await admin.json('/admin/overview'); assert.equal(overview.totalFiles, 1); assert.equal(overview.localUsedBytes, 2 * MiB);
  });
  await run('raise and lower without restart; existing downloads remain available', async () => {
    await configure(3 * MiB);
    assert.equal((await upload(2 * MiB + 1, { chunked: true })).status, 200);
    await configure(MiB);
    assert.equal((await upload(MiB + 1)).status, 413);
    await download(existing, 2 * MiB);
  });
  await run('invalid limits are rejected and legacy settings preserve the saved limit', async () => {
    for (const maxUploadBytes of [0, -1, MiB - 1, 100 * GiB + 1, 1.5]) {
      const response = await admin.mutate('/admin/settings', { method: 'PUT', json: { ...original, maxUploadBytes } });
      assert.equal(response.status, 400, `Unexpected status for limit ${maxUploadBytes}`);
    }
    const response = await admin.mutate('/admin/settings', { method: 'PUT', json: { localCapacityBytes: 4 * GiB, offloadThreshold: 60 } });
    assert.equal(response.status, 200); assert.equal((await response.json()).maxUploadBytes, MiB);
    assert.equal((await anon.json('/public/config')).maxUploadBytes, MiB);
  });
  if (process.env.SMOKE_LARGE_UPLOAD === '1') {
    await run('real 1 GiB + 1 byte upload through Nginx, servlet parser and byte-exact download', async () => {
      await configure(2 * GiB);
      const accepted = await upload(GiB + 1); assert.equal(accepted.status, 200);
      await download(accepted.file, GiB + 1);
    });
  }
} finally {
  let cleanupFailed = false;
  for (const id of files) {
    try { const response = await admin.mutate(`/files/${id}`, { method: 'DELETE' }); assert.ok(response.ok || response.status === 404); }
    catch { cleanupFailed = true; console.error(`Cleanup required for QA file ${id}`); }
  }
  if (original) {
    try { const response = await admin.mutate('/admin/settings', { method: 'PUT', json: original }); assert.equal(response.status, 200); }
    catch { cleanupFailed = true; console.error('Restore the original QA storage settings.'); }
  }
  if (cleanupFailed) process.exitCode = 1;
}
