// Node 22+. Uses only its own providers and account in an empty, isolated QA deployment.
import assert from 'node:assert/strict';
import {readFile, readdir, rename} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:18088').replace(/\/$/, '');
const envFile = resolve(process.env.SMOKE_ENV_FILE || '.local/qa.env');
const mountRoot = resolve(process.env.SMOKE_MOUNT_ROOT || '.local/qa-mounts');
const s3Endpoint = process.env.SMOKE_S3_ENDPOINT || 'http://s3mock:9090';
const env = Object.fromEntries((await readFile(envFile, 'utf8')).split(/\r?\n/)
    .filter(line => line.trim() && !line.trim().startsWith('#')).map(line => {
        const i = line.indexOf('=');
        return [line.slice(0, i).trim(), line.slice(i + 1).replace(/^(['"])(.*)\1$/, '$2')];
    }));

class Client {
    cookies = new Map();
    async request(path, options = {}) {
        const {json, ...init} = options;
        const headers = {...init.headers, Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')};
        if (json !== undefined) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(json); }
        const response = await fetch(`${base}/api${path}`, {
            ...init, headers, redirect: 'manual', cache: 'no-store', signal: AbortSignal.timeout(15000)
        });
        for (const cookie of response.headers.getSetCookie()) {
            const [key, ...value] = cookie.split(';')[0].split('=');
            this.cookies.set(key, value.join('='));
        }
        return response;
    }
    async mutate(path, options = {}) {
        const csrf = await json(await this.request('/auth/csrf'), 200);
        return this.request(path, {method: 'POST', ...options, headers: {...options.headers, [csrf.headerName]: csrf.token}});
    }
    async get(path) { return json(await this.request(path), 200); }
}
async function json(response, status) {
    assert.equal(response.status, status, `Expected HTTP ${status}: ${await response.clone().text()}`);
    return response.json();
}
const admin = new Client(), user = new Client(), anon = new Client();
const providerIds = [];
let userId, activeJob, failure;
const cleanupFailures = [];
const check = async (name, action) => { await action(); console.log(`PASS ${name}`); };
async function waitForTest(providerId, id) {
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
        const result = await admin.get(`/admin/providers/${providerId}/speed-test/${id}`);
        assert.equal(result.id, id);
        assert.equal(result.providerId, providerId);
        assert.equal(result.sampleBytes, 16 * 1024 * 1024);
        if (['COMPLETED', 'FAILED'].includes(result.status)) { activeJob = null; return result; }
        assert.ok(['PREPARING', 'UPLOADING', 'DOWNLOADING', 'CLEANING'].includes(result.status));
        await delay(200);
    }
    throw new Error(`Timed out waiting for speed test ${id} on provider ${providerId}`);
}
async function start(providerId) {
    const result = await json(await admin.mutate(`/admin/providers/${providerId}/speed-test`), 202);
    activeJob = {providerId, id: result.id};
    return waitForTest(providerId, result.id);
}
function verifyCompleted(result) {
    assert.equal(result.status, 'COMPLETED', result.error || 'Speed test failed');
    assert.equal(result.error, null);
    assert.equal(result.warning, null);
    for (const field of ['uploadBytesPerSecond', 'downloadBytesPerSecond']) {
        assert.ok(Number.isFinite(result[field]) && result[field] > 0, `Invalid ${field}`);
    }
    for (const field of ['uploadDurationMillis', 'downloadDurationMillis']) assert.ok(result[field] >= 0);
    assert.ok(Date.parse(result.startedAt) <= Date.parse(result.finishedAt));
}

try {
    await check('admin login and empty QA preflight', async () => {
        const identity = await json(await admin.mutate('/auth/login', {json: {
            username: env.APP_ADMIN_USERNAME || 'admin', password: env.APP_ADMIN_PASSWORD
        }}), 200);
        assert.equal(identity.role, 'ADMIN');
        assert.equal((await admin.get('/admin/overview')).totalFiles, 0, 'Use an empty, isolated QA deployment');
        assert.equal((await admin.get('/admin/overview')).localUsedBytes, 0);
        assert.equal((await admin.get('/admin/users?page=0&size=1')).total, 1);
        assert.equal((await admin.get('/admin/providers')).length, 0);
        for (const type of ['nfs', 'smb']) {
            const entries = await readdir(resolve(mountRoot, type));
            assert.deepEqual(entries, ['.clipboard-storage-id'], 'QA mount must contain only its marker');
        }
    });
    await check('create isolated providers and ordinary user', async () => {
        const username = `speed_${Date.now().toString(36)}`, password = randomUUID();
        const saved = await json(await admin.mutate('/admin/users', {json: {username, password, role: 'USER'}}), 201);
        userId = saved.id;
        await json(await user.mutate('/auth/login', {json: {username, password}}), 200);
        for (const type of ['S3', 'NFS', 'SMB']) {
            const configuration = type === 'S3'
                ? {endpoint: s3Endpoint, bucket: 'clipboard-tests', region: 'us-east-1', accessKey: 'qa-speed-access', secretKey: 'qa-speed-secret'}
                : {mountPath: `/mnt/remote/${type.toLowerCase()}`, mountId: `qa-${type.toLowerCase()}-share`};
            const provider = await json(await admin.mutate('/admin/providers', {json: {
                ...configuration, name: `Speed QA ${type}`, type, priority: 100, enabled: false
            }}), 200);
            providerIds.push(provider.id);
        }
    });
    await check('speed test requires administrator and CSRF', async () => {
        const path = `/admin/providers/${providerIds[0]}/speed-test`;
        const readPath = `${path}/${randomUUID()}`;
        assert.equal((await anon.request(readPath)).status, 401);
        assert.equal((await anon.mutate(path)).status, 401);
        assert.equal((await user.request(readPath)).status, 403);
        assert.equal((await user.mutate(path)).status, 403);
        const missingCsrf = await admin.request(path, {method: 'POST'});
        assert.equal(missingCsrf.status, 403);
        assert.equal((await missingCsrf.json()).code, 'CSRF_INVALID');
    });
    for (const [index, type] of ['S3', 'NFS', 'SMB'].entries()) {
        await check(`${type} upload/download throughput and temporary object cleanup`, async () => {
            const result = await start(providerIds[index]);
            verifyCompleted(result);
            assert.equal((await admin.request(`/admin/providers/${providerIds[(index + 1) % 3]}/speed-test/${result.id}`)).status, 404);
            const output = JSON.stringify(result);
            for (const secret of ['qa-speed-access', 'qa-speed-secret', 'accessKeyEncrypted', 'secretKeyEncrypted']) assert.ok(!output.includes(secret));
            if (type !== 'S3') assert.deepEqual(await readdir(resolve(mountRoot, type.toLowerCase())), ['.clipboard-storage-id']);
            if (type === 'S3' && process.env.SMOKE_S3_PUBLIC_URL) {
                const response = await fetch(`${process.env.SMOKE_S3_PUBLIC_URL}/clipboard-tests?list-type=2`, {signal: AbortSignal.timeout(15000)});
                assert.equal(response.status, 200);
                assert.ok(!(await response.text()).includes('<Contents>'), 'S3 sample must have been deleted');
            }
            console.log(`  ${type}: upload ${(result.uploadBytesPerSecond / 1024 ** 2).toFixed(2)} MiB/s, download ${(result.downloadBytesPerSecond / 1024 ** 2).toFixed(2)} MiB/s`);
        });
    }
    await check('disconnected mount reports failure and next test recovers', async () => {
        const marker = resolve(mountRoot, 'nfs', '.clipboard-storage-id');
        await rename(marker, `${marker}.offline`);
        try {
            const result = await start(providerIds[1]);
            assert.equal(result.status, 'FAILED');
            assert.ok(result.error);
            assert.equal(result.downloadBytesPerSecond, null);
        } finally { await rename(`${marker}.offline`, marker); }
        verifyCompleted(await start(providerIds[1]));
        assert.deepEqual(await readdir(resolve(mountRoot, 'nfs')), ['.clipboard-storage-id']);
    });
    await check('unknown providers/jobs return 404 and file accounting stays unchanged', async () => {
        assert.equal((await admin.mutate(`/admin/providers/${randomUUID()}/speed-test`)).status, 404);
        assert.equal((await admin.request(`/admin/providers/${providerIds[0]}/speed-test/${randomUUID()}`)).status, 404);
        const overview = await admin.get('/admin/overview');
        assert.equal(overview.totalFiles, 0);
        assert.equal(overview.localUsedBytes, 0);
        assert.equal(overview.remoteUsedBytes, 0);
    });
} catch (error) { failure = error; }
finally {
    if (activeJob) {
        try { await waitForTest(activeJob.providerId, activeJob.id); }
        catch (error) { cleanupFailures.push(error.message); }
    }
    for (const id of [...providerIds].reverse()) {
        try {
            const response = await admin.mutate(`/admin/providers/${id}`, {method: 'DELETE'});
            assert.ok([204, 404].includes(response.status), `Provider ${id} cleanup returned ${response.status}`);
        } catch (error) { cleanupFailures.push(error.message); }
    }
    if (userId) {
        try {
            const response = await admin.mutate(`/admin/users/${userId}`, {method: 'DELETE'});
            assert.ok([204, 404].includes(response.status), `User ${userId} cleanup returned ${response.status}`);
        } catch (error) { cleanupFailures.push(error.message); }
    }
}
if (failure) console.error(`FAIL ${failure.stack || failure.message}`);
for (const error of cleanupFailures) console.error(`CLEANUP REQUIRED ${error}`);
if (failure || cleanupFailures.length) process.exitCode = 1;
else console.log('All remote storage speed checks passed; test providers and account removed.');
