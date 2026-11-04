// Node.js 22+: destructive only to its own test records; run against an empty development deployment.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:8088').replace(/\/$/, '');
const s3Endpoint = (process.env.SMOKE_S3_ENDPOINT || 'http://s3mock:9090').replace(/\/$/, '');
const bucket = 'clipboard-tests';
const env = Object.fromEntries((await readFile(new URL('../.env', import.meta.url), 'utf8').catch(() => ''))
  .split(/\r?\n/).filter(line => line.trim() && !line.trim().startsWith('#')).map(line => {
    const separator = line.indexOf('=');
    return [line.slice(0, separator).trim(), line.slice(separator + 1).replace(/^(['"])(.*)\1$/, '$2')];
  }));
const username = process.env.APP_ADMIN_USERNAME || env.APP_ADMIN_USERNAME || 'admin';
const password = process.env.APP_ADMIN_PASSWORD || env.APP_ADMIN_PASSWORD;
assert.ok(password, 'Set APP_ADMIN_PASSWORD or initialize the local .env before this test.');

class Client {
  cookies = new Map();
  async request(path, options = {}) {
    const { json, ...requestOptions } = options;
    const headers = { ...requestOptions.headers, Cookie: [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ') };
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      requestOptions.body = JSON.stringify(json);
    }
    const response = await fetch(base + path, { ...requestOptions, headers, redirect: 'manual', signal: AbortSignal.timeout(15000) });
    for (const cookie of response.headers.getSetCookie()) {
      const [name, ...value] = cookie.split(';')[0].split('=');
      this.cookies.set(name, value.join('='));
    }
    return response;
  }
  async mutate(path, options = {}) {
    const response = await this.request('/api/auth/csrf');
    assert.equal(response.status, 200, 'CSRF token endpoint must be available.');
    const csrf = await response.json();
    return this.request(path, { method: 'POST', ...options, headers: { ...options.headers, [csrf.headerName]: csrf.token } });
  }
}
const admin = new Client();
const check = async (name, action) => { await action(); console.log(`PASS ${name}`); };
async function expectJson(response, expected, label) {
  assert.equal(response.status, expected, `${label}: expected HTTP ${expected}, received ${response.status}`);
  return response.json();
}
const payload = Buffer.alloc(700 * 1024);
for (let i = 0; i < payload.length; i++) payload[i] = i % 251;
function multipart() {
  const form = new FormData();
  form.set('file', new Blob([payload], { type: 'application/octet-stream' }), filename);
  form.set('permanent', 'true');
  return form;
}
const providerIds = [];
const createdFileIds = new Set();
const filename = `storage-smoke-${randomUUID()}.bin`;
let fileId;
let previousSettings;
let settingsChanged = false;
let healthy;
let bad;
let failure;
const cleanupFailures = [];

try {
  await check('administrator authentication and empty-development preflight', async () => {
    const identity = await expectJson(await admin.mutate('/api/auth/login', { json: { username, password } }), 200, 'Admin login');
    assert.equal(identity.role, 'ADMIN');
    const providers = await expectJson(await admin.request('/api/admin/providers'), 200, 'Provider listing');
    assert.ok(!providers.some(provider => provider.enabled), 'Abort: a storage provider is already enabled. Use a separate empty development deployment.');
    const overview = await expectJson(await admin.request('/api/admin/overview'), 200, 'Storage overview');
    assert.equal(overview.totalFiles, 0, 'Abort: this deployment contains files. Run this test only against an empty development deployment.');
    assert.equal(overview.localUsedBytes, 0, 'Abort: local storage is not empty. Let maintenance complete or use a fresh development deployment.');
    previousSettings = await expectJson(await admin.request('/api/admin/settings'), 200, 'Storage settings');
  });

  await check('local-first upload and hard 1 MiB quota', async () => {
    const saved = await expectJson(await admin.mutate('/api/admin/settings', { method: 'PUT', json: { localCapacityBytes: 1024 * 1024, offloadThreshold: 50 } }), 200, 'Set test capacity');
    settingsChanged = true;
    assert.equal(saved.localCapacityBytes, 1024 * 1024);
    const file = await expectJson(await admin.mutate('/api/files', { body: multipart() }), 200, 'First local upload');
    fileId = file.id;
    createdFileIds.add(file.id);
    assert.equal(file.location, 'LOCAL');
    assert.equal(file.permanent, true);
    assert.equal(file.expiresAt, null);
    assert.equal(file.sizeBytes, payload.length);
    const rejected = await admin.mutate('/api/files', { body: multipart() });
    if (rejected.ok) {
      // Keep every unexpectedly accepted file reviewable and removable before failing.
      const unexpected = await rejected.json();
      if (unexpected.id) createdFileIds.add(unexpected.id);
    }
    assert.equal(rejected.status, 507, 'A second 700 KiB upload must exceed the 1 MiB hard quota.');
    const overview = await expectJson(await admin.request('/api/admin/overview'), 200, 'Quota overview');
    assert.equal(overview.localUsedBytes, payload.length);
    assert.equal(overview.totalFiles, 1);
  });

  await check('failed remote provider preserves local file', async () => {
    const badEndpoint = new URL(s3Endpoint);
    badEndpoint.port = '1';
    bad = { name: `QA unavailable ${Date.now()}`, endpoint: badEndpoint.toString().replace(/\/$/, ''), region: 'us-east-1', bucket, accessKey: 'qa-access-key', secretKey: 'qa-secret-key', enabled: true, priority: 1 };
    const created = await expectJson(await admin.mutate('/api/admin/providers', { json: bad }), 200, 'Create unavailable provider');
    providerIds.push(created.id);
    // One failed cycle may still be running; local metadata and bytes must stay available throughout.
    await admin.mutate('/api/admin/maintenance');
    await delay(1500);
    const files = await expectJson(await admin.request('/api/files'), 200, 'Files after provider failure');
    assert.equal(files.find(file => file.id === fileId)?.location, 'LOCAL');
    const overview = await expectJson(await admin.request('/api/admin/overview'), 200, 'Failed-provider overview');
    assert.equal(overview.localUsedBytes, payload.length);
  });

  await check('priority fallback migrates to healthy S3-compatible storage', async () => {
    healthy = { name: `QA S3Mock ${Date.now()}`, endpoint: s3Endpoint, region: 'us-east-1', bucket, accessKey: 'qa-access-key', secretKey: 'qa-secret-key', enabled: true, priority: 100 };
    const created = await expectJson(await admin.mutate('/api/admin/providers', { json: healthy }), 200, 'Create S3Mock provider');
    healthy.id = created.id;
    providerIds.push(created.id);
    const deadline = Date.now() + 60000;
    let moved = false;
    while (Date.now() < deadline) {
      await admin.mutate('/api/admin/maintenance');
      const files = await expectJson(await admin.request('/api/files'), 200, 'Migration poll');
      if (files.find(file => file.id === fileId)?.location === 'S3') { moved = true; break; }
      await delay(1000);
    }
    assert.ok(moved, 'File did not migrate within 60 seconds. Check S3Mock reachability from the backend and backend maintenance logs.');
    const overview = await expectJson(await admin.request('/api/admin/overview'), 200, 'Remote overview');
    assert.equal(overview.remoteUsedBytes, payload.length);
    // The metadata commit precedes local deletion; wait briefly for the post-commit unlink.
    for (let i = 0; i < 10; i++) {
      const state = await expectJson(await admin.request('/api/admin/overview'), 200, 'Local duplicate cleanup');
      if (state.localUsedBytes === 0) break;
      await delay(200);
    }
    assert.equal((await expectJson(await admin.request('/api/admin/overview'), 200, 'Final local usage')).localUsedBytes, 0);
  });

  await check('remote download is byte-exact and provider credentials stay redacted', async () => {
    const files = await expectJson(await admin.request('/api/files'), 200, 'Remote file list');
    const file = files.find(item => item.id === fileId);
    const token = new URL(file.shareUrl).pathname.split('/').at(-1);
    const response = await admin.request(`/api/shares/${token}/download`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-disposition'), /attachment/i);
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), payload);
    const providers = await expectJson(await admin.request('/api/admin/providers'), 200, 'Redacted provider listing');
    const text = JSON.stringify(providers);
    for (const key of ['qa-access-key', 'qa-secret-key', 'accessKeyEncrypted', 'secretKeyEncrypted', 'secretKey']) assert.ok(!text.includes(key), `Provider listing exposed ${key}.`);
    assert.equal(providers.find(provider => provider.id === healthy.id)?.hasCredentials, true);
    assert.equal((await admin.mutate(`/api/admin/providers/${healthy.id}`, { method: 'DELETE' })).status, 409, 'A referenced provider must not be deletable.');
  });

  await check('disabled provider continues serving its existing files', async () => {
    const { id, ...configuration } = healthy;
    const disabled = await expectJson(await admin.mutate(`/api/admin/providers/${id}`, { method: 'PUT', json: { ...configuration, accessKey: '', secretKey: '', enabled: false } }), 200, 'Disable healthy provider');
    assert.equal(disabled.enabled, false);
    const files = await expectJson(await admin.request('/api/files'), 200, 'Existing remote file');
    const token = new URL(files.find(file => file.id === fileId).shareUrl).pathname.split('/').at(-1);
    const response = await admin.request(`/api/shares/${token}/download`);
    assert.equal(response.status, 200);
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), payload);
  });
} catch (error) {
  failure = error;
} finally {
  // Only IDs created by this run are touched. Keep a provider if its object could not be removed.
  if (settingsChanged) {
    try {
      const files = await expectJson(await admin.request('/api/admin/files'), 200, 'Locate this run\'s test files');
      for (const file of files) if (file.originalName === filename) createdFileIds.add(file.id);
    } catch (error) { cleanupFailures.push(`test-file discovery: ${error.message}`); }
  }
  for (const id of createdFileIds) {
    try {
      const removed = await admin.mutate(`/api/files/${id}`, { method: 'DELETE' });
      assert.ok(removed.status === 204 || removed.status === 404, `File cleanup returned HTTP ${removed.status}.`);
    } catch (error) { cleanupFailures.push(`test file ${id}: ${error.message}`); }
  }
  for (const id of [...providerIds].reverse()) {
    try {
      const removed = await admin.mutate(`/api/admin/providers/${id}`, { method: 'DELETE' });
      assert.ok(removed.status === 204 || removed.status === 404, `Provider cleanup returned HTTP ${removed.status}.`);
    } catch (error) { cleanupFailures.push(`test provider ${id}: ${error.message}`); }
  }
  if (settingsChanged && previousSettings) {
    try { await expectJson(await admin.mutate('/api/admin/settings', { method: 'PUT', json: previousSettings }), 200, 'Restore original settings'); }
    catch (error) { cleanupFailures.push(`original storage settings: ${error.message}`); }
  }
  if (!cleanupFailures.length && settingsChanged) console.log('PASS test files/providers removed and original settings restored');
}
if (failure) console.error(`FAIL ${failure.message}`);
for (const error of cleanupFailures) console.error(`CLEANUP REQUIRED ${error}`);
if (failure || cleanupFailures.length) process.exitCode = 1;
else console.log('All storage integration checks passed.');
