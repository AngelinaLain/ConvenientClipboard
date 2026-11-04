import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const config = (maxUploadBytes: number) => ({ retentionDays: 7, maxUploadBytes, registrationEnabled: true })
let client: typeof import('../src/api')
let policy: typeof import('../src/uploadPolicy')
beforeEach(async () => { vi.resetModules(); client = await import('../src/api'); policy = await import('../src/uploadPolicy') })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('dynamic upload configuration', () => {
  it('fetches the current server value on every refresh without retaining the old browser limit', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json(config(2 * policy.GIB))).mockResolvedValueOnce(json(config(4 * policy.GIB)))
    vi.stubGlobal('fetch', fetchMock)
    expect(client.session.config.maxUploadBytes).toBe(policy.GIB)
    await client.refreshPublicConfig(); expect(client.session.config.maxUploadBytes).toBe(2 * policy.GIB)
    await client.refreshPublicConfig(); expect(client.session.config.maxUploadBytes).toBe(4 * policy.GIB)
    expect(fetchMock.mock.calls).toHaveLength(2)
    expect(fetchMock.mock.calls.every(call => call[0] === '/api/public/config' && call[1].cache === 'no-store')).toBe(true)
  })

  it('shares concurrent refreshes but releases the in-flight request after completion', async () => {
    let resolve!: (response: Response) => void
    const fetchMock = vi.fn().mockImplementationOnce(() => new Promise<Response>(done => { resolve = done })).mockResolvedValueOnce(json(config(3 * policy.GIB)))
    vi.stubGlobal('fetch', fetchMock)
    const first = client.refreshPublicConfig(); const second = client.refreshPublicConfig()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    resolve(json(config(2 * policy.GIB))); await Promise.all([first, second])
    await client.refreshPublicConfig(); expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(client.session.config.maxUploadBytes).toBe(3 * policy.GIB)
  })

  it('uses a newly raised server limit before validating a large selection', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(config(3 * policy.GIB))))
    const file = { name: 'large.zip', size: 2 * policy.GIB }
    expect(policy.fileUploadError(file, client.session.config.maxUploadBytes)).toContain('超过')
    const checked = await policy.validateUploadBatch([file])
    expect(checked[0].error).toBeNull()
    expect(client.session.config.maxUploadBytes).toBe(3 * policy.GIB)
  })

  it('checks every queued file against a lowered limit with one configuration request for the whole batch', async () => {
    client.applyUploadLimit(8 * policy.GIB)
    const fetchMock = vi.fn(async () => json(config(2 * policy.GIB))); vi.stubGlobal('fetch', fetchMock)
    const checked = await policy.validateUploadBatch([{ name: 'allowed.zip', size: 2 * policy.GIB }, { name: 'too-large.zip', size: 2 * policy.GIB + 1 }, { name: 'small.txt', size: 50 }])
    expect(checked.map(entry => !!entry.error)).toEqual([false, true, false])
    expect(checked[1].error).toContain('2 GiB')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('stops batch preparation if the current configuration cannot be read', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('offline')); vi.stubGlobal('fetch', fetchMock)
    await expect(policy.validateUploadBatch([{ name: 'file.txt', size: 10 }])).rejects.toThrow('offline')
    expect(client.session.config.maxUploadBytes).toBe(policy.GIB)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('applies the saved limit immediately and prevents an older read from undoing the saved setting', async () => {
    let resolveOld!: (response: Response) => void
    const saved = { localCapacityBytes: 10 * policy.GIB, offloadThreshold: 50, maxUploadBytes: 5 * policy.GIB }
    const fetchMock = vi.fn((url: string) => {
      if (url === '/api/public/config') return new Promise<Response>(done => { resolveOld = done })
      if (url === '/api/auth/csrf') return Promise.resolve(json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
      return Promise.resolve(json(saved))
    })
    vi.stubGlobal('fetch', fetchMock)
    const pending = client.refreshPublicConfig()
    expect(await client.saveAdminSettings(saved)).toEqual(saved)
    expect(client.session.config.maxUploadBytes).toBe(5 * policy.GIB)
    resolveOld(json(config(policy.GIB))); await pending
    expect(client.session.config.maxUploadBytes).toBe(5 * policy.GIB)
    const write = fetchMock.mock.calls.find(call => call[0] === '/api/admin/settings') as unknown as [string, RequestInit]
    expect(JSON.parse(write[1].body as string).maxUploadBytes).toBe(5 * policy.GIB)
  })

  it('keeps the existing limit when the administrator save is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url.endsWith('/csrf') ? json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }) : json({ message: '无效设置' }, 400))))
    await expect(client.saveAdminSettings({ localCapacityBytes: policy.GIB, offloadThreshold: 50, maxUploadBytes: 5 * policy.GIB })).rejects.toThrow('无效设置')
    expect(client.session.config.maxUploadBytes).toBe(policy.GIB)
  })
})

describe('upload limit entry and boundaries', () => {
  it('converts either unit into exact bytes, including fractional GiB and the full allowed range', () => {
    expect(policy.parseUploadLimit(1, 'MiB')).toBe(policy.MIB)
    expect(policy.parseUploadLimit(1.5, 'GiB')).toBe(1.5 * policy.GIB)
    expect(policy.parseUploadLimit('102400', 'MiB')).toBe(100 * policy.GIB)
    expect(policy.parseUploadLimit(100, 'GiB')).toBe(107374182400)
  })

  it.each(['', ' ', 'not-a-number', 'Infinity', '-1', '0', '100.000001'])('rejects invalid or out-of-range GiB input %j', value => {
    expect(() => policy.parseUploadLimit(value, 'GiB')).toThrow('1 MiB 至 100 GiB')
  })

  it('rejects numbers below one MiB and non-finite numeric values', () => {
    for (const value of [0.999, NaN, Infinity, -Infinity]) expect(() => policy.parseUploadLimit(value, 'MiB')).toThrow()
  })

  it('preserves saved byte values when displaying and re-saving using the selected unit', () => {
    for (const bytes of [policy.MIB, 500 * policy.MIB, policy.GIB + 37, 100 * policy.GIB]) {
      const input = policy.uploadLimitInput(bytes)
      expect(policy.parseUploadLimit(input.value, input.unit)).toBe(bytes)
    }
    expect(policy.uploadLimitInput(policy.GIB)).toEqual({ value: 1, unit: 'GiB' })
  })
})
