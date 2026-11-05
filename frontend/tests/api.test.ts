import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const token = (value: string) => json({ token: value, headerName: 'X-XSRF-TOKEN' })
const csrfFailure = () => json({ message: '令牌失效', code: 'CSRF_INVALID' }, 403)
let client: typeof import('../src/api')
beforeEach(async () => { vi.resetModules(); client = await import('../src/api') })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('CSRF synchronization', () => {
  it('shares an in-flight token request, then obtains a fresh token for the next operation', async () => {
    let resolve!: (response: Response) => void
    const fetchMock = vi.fn().mockImplementationOnce(() => new Promise<Response>(done => { resolve = done })).mockResolvedValueOnce(token('next'))
    vi.stubGlobal('fetch', fetchMock)
    const first = client.csrfToken(); const second = client.csrfToken()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    resolve(token('shared'))
    expect(await first).toEqual(await second)
    expect((await client.csrfToken()).token).toBe('next')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(call => call[1].cache === 'no-store')).toBe(true)
  })

  it('ignores an earlier in-flight token after authentication resets its generation', async () => {
    let resolveOld!: (response: Response) => void; let resolveNew!: (response: Response) => void
    const fetchMock = vi.fn().mockImplementationOnce(() => new Promise<Response>(done => { resolveOld = done })).mockImplementationOnce(() => new Promise<Response>(done => { resolveNew = done }))
    vi.stubGlobal('fetch', fetchMock)
    const old = client.csrfToken(); client.resetCsrf(); const fresh = client.csrfToken()
    resolveOld(token('before-login')); await Promise.resolve(); await Promise.resolve()
    resolveNew(token('after-login'))
    expect((await old).token).toBe('after-login')
    expect((await fresh).token).toBe('after-login')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('recovers once from an explicitly rejected CSRF token while retaining the original write body', async () => {
    const seen: { token: string | null; body: BodyInit | null | undefined }[] = []; let issued = 0
    const result = { id: 'file-1', permanent: true, expiresAt: null }
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/csrf')) return token(`token-${++issued}`)
      seen.push({ token: new Headers(init.headers).get('X-XSRF-TOKEN'), body: init.body })
      return seen.length === 1 ? csrfFailure() : json(result)
    }))
    const body = JSON.stringify({ permanent: true })
    expect(await client.api('/files/file-1/retention', { method: 'PATCH', body })).toEqual(result)
    expect(seen).toEqual([{ token: 'token-1', body }, { token: 'token-2', body }])
  })

  it('does not retry a genuine ownership or role rejection', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(token('fresh')).mockResolvedValueOnce(json({ message: '无权修改此文件', code: 'FORBIDDEN' }, 403))
    vi.stubGlobal('fetch', fetchMock)
    await expect(client.api('/files/other/retention', { method: 'PATCH', body: '{}' })).rejects.toMatchObject({ status: 403, code: 'FORBIDDEN' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not repeat writes after a network failure or an ambiguous server error', async () => {
    for (const failure of [new Error('Connection lost'), json({ message: '保存失败' }, 500)]) {
      const fetchMock = vi.fn().mockResolvedValueOnce(token('fresh'))
      if (failure instanceof Error) fetchMock.mockRejectedValueOnce(failure); else fetchMock.mockResolvedValueOnce(failure)
      vi.stubGlobal('fetch', fetchMock)
      await expect(client.api('/admin/users', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(Error)
      expect(fetchMock).toHaveBeenCalledTimes(2)
    }
  })

  it('stops after a second CSRF rejection instead of looping', async () => {
    let issued = 0; let writes = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/csrf') ? token(String(++issued)) : (writes++, csrfFailure())))
    await expect(client.api('/files/file-1', { method: 'DELETE' })).rejects.toMatchObject({ code: 'CSRF_INVALID' })
    expect(issued).toBe(2); expect(writes).toBe(2)
  })

  it('fetches files, account state and admin data without browser cache reuse', async () => {
    const fetchMock = vi.fn(async () => json([])); vi.stubGlobal('fetch', fetchMock)
    await client.api('/files'); await client.api('/auth/me'); await client.api('/admin/files?page=0&size=20')
    expect(fetchMock.mock.calls.every(call => (call as unknown as [string, RequestInit])[1].cache === 'no-store')).toBe(true)
  })
})

describe('multipart upload CSRF recovery', () => {
  class FakeXHR {
    static results: { status: number; body?: unknown; networkError?: boolean }[] = []
    static sent: { headers: Record<string, string>; body: FormData }[] = []
    headers: Record<string, string> = {}; status = 0; responseText = ''; withCredentials = false
    upload = { onprogress: null as null | ((event: ProgressEvent) => void) }; onerror: (() => void) | null = null; onload: (() => void) | null = null
    open() {} setRequestHeader(key: string, value: string) { this.headers[key] = value }
    send(body: FormData) {
      FakeXHR.sent.push({ headers: { ...this.headers }, body })
      const result = FakeXHR.results.shift()!
      queueMicrotask(() => { if (result.networkError) this.onerror?.(); else { this.status = result.status; this.responseText = JSON.stringify(result.body); this.onload?.() } })
    }
  }
  beforeEach(() => { FakeXHR.sent = []; FakeXHR.results = []; vi.stubGlobal('XMLHttpRequest', FakeXHR) })
  it('re-sends the upload only after explicit pre-write CSRF rejection', async () => {
    let issued = 0; vi.stubGlobal('fetch', vi.fn(async () => token(`upload-${++issued}`)))
    FakeXHR.results = [{ status: 403, body: { code: 'CSRF_INVALID' } }, { status: 200, body: { id: 'uploaded' } }]
    const file = new File(['hello'], 'example.txt'); const progress = vi.fn()
    expect(await client.uploadFile(file, true, progress)).toEqual({ id: 'uploaded' })
    expect(FakeXHR.sent.map(request => request.headers['X-XSRF-TOKEN'])).toEqual(['upload-1', 'upload-2'])
    expect(FakeXHR.sent.every(request => request.body.get('permanent') === 'true' && (request.body.get('file') as File).name === 'example.txt')).toBe(true)
    expect(progress).toHaveBeenLastCalledWith(100)
  })
  it.each([{ status: 403, body: { code: 'FORBIDDEN' } }, { status: 500 }, { status: 0, networkError: true }])('does not repeat an upload for $status without explicit CSRF rejection', async result => {
    vi.stubGlobal('fetch', vi.fn(async () => token('fresh'))); FakeXHR.results = [result]
    await expect(client.uploadFile(new File(['a'], 'a.txt'), false, vi.fn())).rejects.toBeInstanceOf(Error)
    expect(FakeXHR.sent).toHaveLength(1)
  })
})

describe('binary upload requests', () => {
  it('preserves an explicit binary content type and the exact Blob body', async () => {
    const body = new Blob(['binary chunk'])
    const fetchMock = vi.fn().mockResolvedValueOnce(token('chunk-token')).mockResolvedValueOnce(json({receivedChunks: [0]}))
    vi.stubGlobal('fetch', fetchMock)
    await client.api('/uploads/task/chunks/0', {method: 'PUT', body, headers: {'Content-Type': 'application/octet-stream'}})
    const request = fetchMock.mock.calls[1]![1] as RequestInit
    expect(new Headers(request.headers).get('Content-Type')).toBe('application/octet-stream')
    expect(request.body).toBe(body)
  })
})
