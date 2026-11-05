// Node 22+. Run only against an empty, isolated QA deployment.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

const base = (process.env.SMOKE_BASE_URL || 'http://localhost:18088').replace(/\/$/, '');
const settings = Object.fromEntries((await readFile(process.env.SMOKE_ENV_FILE || '.local/qa.env', 'utf8'))
  .split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => {
    const split = line.indexOf('=');
    let value = line.slice(split + 1);
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    return [line.slice(0, split), value];
  }));

class Client {
  cookies = new Map();
  async request(path, {json, ...options} = {}) {
    const headers = {...options.headers, Cookie: [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; ')};
    if (json !== undefined) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(json); }
    const response = await fetch(`${base}/api${path}`, {...options, headers, signal: AbortSignal.timeout(30000)});
    for (const cookie of response.headers.getSetCookie()) {
      const [key, ...value] = cookie.split(';')[0].split('='); this.cookies.set(key, value.join('='));
    }
    return response;
  }
  async mutate(path, options = {}) {
    const csrf = await this.json('/auth/csrf');
    return this.request(path, {method: 'POST', ...options, headers: {...options.headers, [csrf.headerName]: csrf.token}});
  }
  async json(path) {
    const response = await this.request(path); assert.equal(response.status, 200, await response.clone().text()); return response.json();
  }
  async login(username, password) {
    const response = await this.mutate('/auth/login', {json: {username, password}});
    assert.equal(response.status, 200, await response.clone().text());
  }
}

const admin = new Client(), user = new Client(), anonymous = new Client();
await admin.login(settings.APP_ADMIN_USERNAME || 'admin', settings.APP_ADMIN_PASSWORD);
assert.equal((await admin.json('/admin/overview')).totalFiles, 0, 'Use an empty QA deployment; existing files must remain untouched.');
assert.equal((await admin.json('/admin/users?page=0&size=1')).total, 1, 'Use a fresh QA deployment with only its bootstrap administrator.');
assert.equal((await admin.json('/admin/providers')).length, 0, 'Use a QA deployment without remote storage providers.');

const runId = Date.now().toString(36), username = `qa_owner_${runId}`, password = 'Qa-list-password-42';
const files = [];
let userId;
let passed = false;
const page = params => admin.json(`/admin/files?${new URLSearchParams({page: '0', size: '100', ...params})}`);
try {
  const created = await admin.mutate('/admin/users', {json: {username, password, role: 'USER'}});
  if (created.ok) userId = (await created.clone().json()).id;
  assert.equal(created.status, 201, await created.clone().text());
  await user.login(username, password);
  for (let index = 0; index < 53; index++) {
    const client = index % 3 === 0 ? anonymous : index % 3 === 1 ? admin : user;
    const permanent = client !== anonymous && index % 2 === 0;
    const name = `qa_list_${runId}_${String(index).padStart(2, '0')}${index === 0 ? '_100%_' : ''}.txt`;
    const body = new FormData(); body.set('file', new Blob([`fixture ${index}`]), name); body.set('permanent', String(permanent));
    const response = await client.mutate('/files', {body});
    assert.equal(response.status, 200, await response.clone().text());
    files.push({...await response.json(), anonymous: client === anonymous, ownedByUser: client === user});
  }

  const seen = new Set();
  for (let index = 0; index < 6; index++) {
    const result = await page({page: String(index), size: '10'});
    assert.equal(result.total, 53); assert.equal(result.page, index); assert.equal(result.size, 10);
    assert.equal(result.items.length, index === 5 ? 3 : 10);
    for (const item of result.items) { assert.ok(!seen.has(item.id)); seen.add(item.id); }
  }
  assert.equal(seen.size, 53);
  assert.equal((await page({page: '99', size: '10'})).items.length, 0);
  console.log('PASS six pages contain every file exactly once, with accurate totals and empty out-of-range pages');

  for (const query of [runId.toUpperCase(), `  ${runId}  `]) assert.equal((await page({query})).total, 53);
  assert.equal((await page({query: username.toUpperCase()})).total, files.filter(file => file.ownedByUser).length);
  assert.equal((await page({query: '匿名用户'})).total, files.filter(file => file.anonymous).length);
  assert.equal((await page({query: files[0].id})).items[0].id, files[0].id);
  assert.equal((await page({query: '100%_'})).total, 1);
  assert.equal((await page({query: "' OR 1=1 --"})).total, 0);
  console.log('PASS case-insensitive filename/user/anonymous/ID search, whitespace and literal special characters');

  const permanentCount = files.filter(file => file.permanent).length;
  assert.equal((await page({location: 'LOCAL', retention: 'PERMANENT', query: runId})).total, permanentCount);
  assert.equal((await page({location: 'LOCAL', retention: 'TEMPORARY'})).total, 53 - permanentCount);
  assert.equal((await page({query: username, retention: 'PERMANENT'})).total, files.filter(file => file.ownedByUser && file.permanent).length);
  for (const location of ['S3', 'NFS', 'SMB']) assert.equal((await page({location})).total, 0);
  const defaults = await admin.json('/admin/files?retention=PERMANENT');
  assert.equal(defaults.page, 0); assert.equal(defaults.size, 20); assert.equal(defaults.total, permanentCount);
  assert.ok(Array.isArray(await admin.json('/admin/files')));
  console.log('PASS combined filters, default pagination for filtered requests and legacy list compatibility');

  for (const params of [{location: 'INVALID'}, {retention: 'INVALID'}, {page: '-1'}, {size: '0'}, {size: '101'}, {query: 'x'.repeat(201)}]) {
    assert.equal((await admin.request(`/admin/files?${new URLSearchParams({page: '0', size: '20', ...params})}`)).status, 400);
  }
  assert.equal((await user.request('/admin/files?page=0&size=20')).status, 403);
  assert.equal((await anonymous.request('/admin/files?page=0&size=20')).status, 401);
  console.log('PASS invalid parameters and non-administrator access are rejected');
  passed = true;
} finally {
  if (passed && process.env.SMOKE_KEEP_FIXTURES === '1') {
    console.log('QA fixtures retained for browser checks; remove the isolated QA project and its volumes afterward.');
  } else {
    for (const file of files) {
      const response = await admin.mutate(`/files/${file.id}`, {method: 'DELETE'});
      assert.ok(response.ok || response.status === 404, `Could not clean QA file ${file.id}`);
    }
    if (userId) assert.ok((await admin.mutate(`/admin/users/${userId}`, {method: 'DELETE'})).ok);
    console.log('QA files and test account cleaned');
  }
}
