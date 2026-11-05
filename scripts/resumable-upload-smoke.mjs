// Node 22+. Only run against an empty, isolated QA deployment.
import assert from 'node:assert/strict';
import {createHash, randomBytes, randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import http from 'node:http';
import {spawnSync} from 'node:child_process';
import {Readable} from 'node:stream';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:18088').replace(/\/$/, '');
const envPath = process.env.SMOKE_ENV_FILE || '.local/qa.env';
const settings = Object.fromEntries((await readFile(envPath, 'utf8')).split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => {
    const split = line.indexOf('='); let value = line.slice(split + 1);
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    return [line.slice(0, split).replace(/^\uFEFF/, ''), value];
  }));
const MiB = 1024 ** 2, CHUNK = 8 * MiB;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

class Client {
  cookies = new Map();
  cookie() { return [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; '); }
  async request(path, {json, ...options} = {}) {
    const headers = {...options.headers, Cookie: this.cookie()};
    if (json !== undefined) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(json); }
    const response = await fetch(`${base}/api${path}`, {...options, headers, signal: options.signal || AbortSignal.timeout(60000)});
    for (const cookie of response.headers.getSetCookie()) {
      const [key, ...value] = cookie.split(';')[0].split('='); this.cookies.set(key, value.join('='));
    }
    return response;
  }
  async json(path, options) {
    const response = await this.request(path, options);
    assert.equal(response.status, 200, await response.clone().text()); return response.json();
  }
  async csrf() { return this.json('/auth/csrf'); }
  async mutate(path, options = {}) {
    const csrf = await this.csrf();
    return this.request(path, {method: 'POST', ...options, headers: {...options.headers, [csrf.headerName]: csrf.token}});
  }
  async login(username, password) {
    const response = await this.mutate('/auth/login', {json: {username, password}});
    assert.equal(response.status, 200, await response.clone().text());
  }
}

const admin = new Client(), anon = new Client(), user = new Client();
await admin.login(settings.APP_ADMIN_USERNAME || 'admin', settings.APP_ADMIN_PASSWORD);
assert.equal((await admin.json('/admin/overview')).totalFiles, 0, 'Use an empty QA deployment; existing files must remain untouched.');
assert.equal((await admin.json('/admin/users?page=0&size=1')).total, 1, 'Use a fresh QA deployment with only its bootstrap administrator.');
assert.equal((await admin.json('/admin/providers')).length, 0, 'Use QA without remote providers.');
const original = await admin.json('/admin/settings');
const sessions = [], fileIds = new Set();
let userId;
const run = async (name, action) => { await action(); console.log(`PASS ${name}`); };

function fixture(size, permanent = false, client = anon) {
  const bytes = Buffer.alloc(size);
  for (let offset = 0; offset < size; offset += CHUNK) bytes.fill(Math.floor(offset / CHUNK) + 1, offset, Math.min(offset + CHUNK, size));
  const chunks = Array.from({length: Math.ceil(size / CHUNK)}, (_, index) => bytes.subarray(index * CHUNK, Math.min((index + 1) * CHUNK, size)));
  const task = {id: randomUUID(), token: randomBytes(32).toString('hex'), client, bytes, chunks,
    body: {originalName: `resume_${Date.now()}_${randomUUID()}.bin`, contentType: 'application/octet-stream', sizeBytes: size,
      permanent, chunkHashes: chunks.map(hash)}};
  task.body.id = task.id; sessions.push(task); return task;
}
const headers = task => ({'X-Upload-Token': task.token});
const create = task => task.client.mutate('/uploads', {json: task.body, headers: headers(task)});
const status = task => task.client.json(`/uploads/${task.id}`, {headers: headers(task)});
const put = (task, index, body = task.chunks[index]) => task.client.mutate(`/uploads/${task.id}/chunks/${index}`, {
  method: 'PUT', headers: {...headers(task), 'Content-Type': 'application/octet-stream'}, body});
const complete = task => task.client.mutate(`/uploads/${task.id}/complete`, {headers: headers(task)});
async function accepted(response) {
  assert.ok(response.ok, `${response.status}: ${await response.clone().text()}`); return response.json();
}
async function removeFile(id) {
  const response = await admin.mutate(`/files/${id}`, {method: 'DELETE'});
  assert.ok(response.ok || response.status === 404, `Could not clean QA file ${id}`); fileIds.delete(id);
}

try {
  const account = await admin.mutate('/admin/users', {json: {username: `qa_resume_${Date.now().toString(36)}`, password: 'Qa-resume-user-42', role: 'USER'}});
  if (account.ok) userId = (await account.clone().json()).id;
  assert.equal(account.status, 201, await account.clone().text());
  await user.login((await account.json()).username, 'Qa-resume-user-42');
  await anon.csrf();

  const task = fixture(2 * CHUNK + 123);
  await run('creation retries use one task and conflicting metadata is rejected', async () => {
    const [first, repeated] = await Promise.all([create(task).then(accepted), create(task).then(accepted)]);
    assert.equal(first.id, task.id); assert.equal(repeated.id, task.id); assert.equal(first.chunkSize, CHUNK);
    assert.equal(first.totalChunks, 3); assert.equal(first.uploadedBytes, 0);
    const beforeQuery = await status(task);
    const deadline = Date.parse(beforeQuery.expiresAt);
    assert.ok(deadline > Date.now() + 9 * 60 * 1000 && deadline <= Date.now() + 10 * 60 * 1000 + 5000,
      'An active upload must expire after ten minutes without successful activity');
    assert.equal((await status(task)).expiresAt, beforeQuery.expiresAt, 'Progress queries must not extend the deadline');
    const conflict = await task.client.mutate('/uploads', {json: {...task.body, originalName: 'different.bin'}, headers: headers(task)});
    assert.equal(conflict.status, 409);
    assert.equal((await anon.request(`/uploads/${task.id}`, {headers: {'X-Upload-Token': '0'.repeat(64)}})).status, 404);
  });

  await run('chunk integrity, exact length and concurrent duplicate writes preserve progress', async () => {
    assert.equal((await put(task, 0, Buffer.alloc(CHUNK, 99))).status, 422);
    assert.ok([400, 413].includes((await put(task, 2, Buffer.alloc(124))).status));
    assert.deepEqual((await status(task)).receivedChunks, []);
    const copies = await Promise.all([put(task, 0).then(accepted), put(task, 0).then(accepted)]);
    for (const copy of copies) assert.equal(copy.uploadedBytes, CHUNK);
    const progress = await status(task);
    assert.deepEqual(progress.receivedChunks, [0]); assert.equal(progress.uploadedBytes, CHUNK);
    assert.equal((await complete(task)).status, 409);
  });

  await run('interrupted request body does not acknowledge a partial chunk', async () => {
    assert.equal(new URL(base).protocol, 'http:', 'The intentional disconnect check uses the local HTTP QA endpoint.');
    const csrf = await task.client.csrf();
    await new Promise(resolve => {
      const request = http.request(`${base}/api/uploads/${task.id}/chunks/1`, {method: 'PUT', headers: {
        ...headers(task), Cookie: task.client.cookie(), [csrf.headerName]: csrf.token,
        'Content-Type': 'application/octet-stream', 'Content-Length': task.chunks[1].length
      }});
      request.on('error', resolve); request.on('response', response => { response.resume(); resolve(); });
      request.write(task.chunks[1].subarray(0, 128 * 1024), () => setTimeout(() => request.destroy(new Error('intentional test disconnect')), 30));
    });
    const progress = await status(task);
    assert.deepEqual(progress.receivedChunks, [0]); assert.equal(progress.uploadedBytes, CHUNK);
  });

  if (process.env.SMOKE_RESTART_PROJECT) {
    await run('backend restart preserves task metadata and committed chunks', async () => {
      const project = process.env.SMOKE_RESTART_PROJECT;
      assert.equal(project, 'convenient-clipboard-upload-qa', 'Restart is restricted to the dedicated upload QA project.');
      const restarted = spawnSync('docker', ['compose', '-p', project, '--env-file', envPath, '-f', 'compose.yaml', '-f', 'compose.integration.yaml', 'restart', 'backend'], {encoding: 'utf8'});
      assert.equal(restarted.status, 0, restarted.stderr);
      const deadline = Date.now() + 60000;
      while (true) {
        try {
          const response = await fetch(`${base}/api/public/config`, {signal: AbortSignal.timeout(2000)});
          if (response.ok) break;
        } catch {}
        assert.ok(Date.now() < deadline, 'Backend did not recover after restart'); await sleep(300);
      }
      const progress = await status(task);
      assert.deepEqual(progress.receivedChunks, [0]); assert.equal(progress.uploadedBytes, CHUNK);
    });
  }

  await run('resume sends only missing chunks and repeated completion returns one byte-exact file', async () => {
    const progress = await status(task);
    for (let index = 0; index < task.chunks.length; index++) if (!progress.receivedChunks.includes(index)) await accepted(await put(task, index));
    const ready = await status(task); assert.equal(ready.uploadedBytes, task.bytes.length);
    const [first, second] = await Promise.all([complete(task).then(accepted), complete(task).then(accepted)]);
    assert.equal(first.state, 'COMPLETED'); assert.equal(second.file.id, first.file.id);
    assert.equal(first.file.id, task.id); assert.equal(first.file.shareUrl, second.file.shareUrl); fileIds.add(first.file.id);
    assert.equal((await status(task)).file.shareUrl, first.file.shareUrl);
    assert.equal((await admin.json('/admin/overview')).totalFiles, 1);
    const shareToken = new URL(first.file.shareUrl).pathname.split('/').pop();
    const download = await anon.request(`/shares/${shareToken}/download`);
    assert.equal(download.status, 200); assert.equal(hash(Buffer.from(await download.arrayBuffer())), hash(task.bytes));
    await removeFile(first.file.id);
    const afterDelete = await complete(task);
    assert.ok(afterDelete.ok || afterDelete.status === 410, 'Completed task must never recreate a deleted file');
    assert.equal((await admin.json('/admin/overview')).totalFiles, 0);
  });

  await run('owner isolation, permanent-file permission, cancellation and malformed manifests', async () => {
    const owned = fixture(4096, true, user); await accepted(await create(owned));
    assert.ok([401, 403, 404].includes((await anon.request(`/uploads/${owned.id}`, {headers: headers(owned)})).status));
    assert.ok([403, 404].includes((await admin.request(`/uploads/${owned.id}`, {headers: headers(owned)})).status));
    const cancelled = await user.mutate(`/uploads/${owned.id}`, {method: 'DELETE', headers: headers(owned)}); assert.ok(cancelled.ok);
    assert.equal((await create(owned)).status, 410);
    assert.equal((await put(owned, 0)).status, 410);
    const forbidden = fixture(4, true); assert.equal((await create(forbidden)).status, 401);
    const malformed = fixture(4);
    const response = await anon.mutate('/uploads', {json: {...malformed.body, chunkHashes: []}, headers: headers(malformed)});
    assert.equal(response.status, 400);
    const beforeCreate = fixture(4);
    assert.ok((await anon.mutate(`/uploads/${beforeCreate.id}`, {method: 'DELETE', headers: headers(beforeCreate)})).ok);
    assert.equal((await create(beforeCreate)).status, 410);
    const racing = fixture(4);
    const [creating, cancelling] = await Promise.all([
      create(racing), anon.mutate(`/uploads/${racing.id}`, {method: 'DELETE', headers: headers(racing)})
    ]);
    assert.ok(creating.ok || creating.status === 410); assert.ok(cancelling.ok);
    assert.equal((await anon.request(`/uploads/${racing.id}`, {headers: headers(racing)})).status, 410);
  });

  await run('metadata and binary size ceilings also cover chunked request bodies', async () => {
    const oversized = fixture(4);
    const jsonBody = JSON.stringify({...oversized.body, originalName: 'x'.repeat(2 * MiB)});
    for (const path of ['/uploads', '/%75ploads']) {
      const rejected = await anon.mutate(path, {body: jsonBody, headers: {...headers(oversized), 'Content-Type': 'application/json'}});
      assert.equal(rejected.status, 413);
      const streamed = await anon.mutate(path, {
        body: Readable.from([jsonBody.slice(0, MiB), jsonBody.slice(MiB)]), duplex: 'half',
        headers: {...headers(oversized), 'Content-Type': 'application/json'}
      });
      assert.equal(streamed.status, 413);
    }
    const tiny = fixture(4); await accepted(await create(tiny));
    const longChunk = await anon.mutate(`/uploads/${tiny.id}/chunks/0`, {
      method: 'PUT', body: Readable.from([Buffer.alloc(3, 1), Buffer.alloc(2, 1)]), duplex: 'half',
      headers: {...headers(tiny), 'Content-Type': 'application/octet-stream'}
    });
    assert.equal(longChunk.status, 413); assert.deepEqual((await status(tiny)).receivedChunks, []);
    assert.ok((await anon.mutate(`/uploads/${tiny.id}`, {method: 'DELETE', headers: headers(tiny)})).ok);
  });

  await run('a client-selected task ID cannot overwrite or remove an existing file', async () => {
    const originalBytes = Buffer.from('existing object must survive UUID collisions');
    const body = new FormData(); body.set('file', new Blob([originalBytes]), 'collision-victim.txt');
    const victim = await accepted(await anon.mutate('/files', {body})); fileIds.add(victim.id);
    const collision = fixture(4); collision.id = victim.id; collision.body.id = victim.id;
    assert.equal((await create(collision)).status, 409);
    const shareToken = new URL(victim.shareUrl).pathname.split('/').pop();
    const download = await anon.request(`/shares/${shareToken}/download`);
    assert.equal(download.status, 200); assert.equal(hash(Buffer.from(await download.arrayBuffer())), hash(originalBytes));
    await removeFile(victim.id);
  });

  await run('reservations prevent both resumable and legacy uploads from exceeding the local budget', async () => {
    assert.ok((await admin.mutate('/admin/settings', {method: 'PUT', json: {...original, localCapacityBytes: MiB}})).ok);
    const reserved = fixture(700 * 1024); await accepted(await create(reserved));
    const another = fixture(700 * 1024); assert.equal((await create(another)).status, 507);
    const body = new FormData(); body.set('file', new Blob([reserved.bytes]), 'legacy-reservation.bin');
    assert.equal((await anon.mutate('/files', {body})).status, 507);
    assert.ok((await anon.mutate(`/uploads/${reserved.id}`, {method: 'DELETE', headers: headers(reserved)})).ok);
    const legacy = await accepted(await anon.mutate('/files', {body})); fileIds.add(legacy.id); await removeFile(legacy.id);
    assert.ok((await admin.mutate('/admin/settings', {method: 'PUT', json: original})).ok);
  });

  await run('lowered live upload limits apply on finalization without discarding resumable progress', async () => {
    const limited = fixture(2 * MiB); await accepted(await create(limited)); await accepted(await put(limited, 0));
    assert.ok((await admin.mutate('/admin/settings', {method: 'PUT', json: {...original, maxUploadBytes: MiB}})).ok);
    assert.equal((await complete(limited)).status, 413);
    assert.deepEqual((await status(limited)).receivedChunks, [0]);
    assert.ok((await admin.mutate('/admin/settings', {method: 'PUT', json: original})).ok);
    const saved = await accepted(await complete(limited)); fileIds.add(saved.file.id); await removeFile(saved.file.id);
  });
} finally {
  // Only the synthetic objects and account created by this run are eligible for cleanup.
  const cleanupErrors = [];
  for (const task of sessions) {
    try {
      const response = await task.client.mutate(`/uploads/${task.id}`, {method: 'DELETE', headers: headers(task)});
      if (!response.ok && ![404, 409, 410].includes(response.status)) cleanupErrors.push(`task ${task.id}: ${response.status}`);
    } catch (error) { cleanupErrors.push(`task ${task.id}: ${error.message}`); }
  }
  for (const id of [...fileIds]) {
    try { await removeFile(id); } catch (error) { cleanupErrors.push(error.message); }
  }
  try { assert.ok((await admin.mutate('/admin/settings', {method: 'PUT', json: original})).ok); } catch (error) { cleanupErrors.push(`settings: ${error.message}`); }
  if (userId) {
    try { assert.ok((await admin.mutate(`/admin/users/${userId}`, {method: 'DELETE'})).ok); } catch (error) { cleanupErrors.push(`account ${userId}: ${error.message}`); }
  }
  assert.deepEqual(cleanupErrors, [], 'Some QA fixtures require cleanup');
  console.log('QA upload tasks, files and account cleaned; original settings restored');
}
