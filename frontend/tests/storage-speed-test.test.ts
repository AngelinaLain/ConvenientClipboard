import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {api, ApiError} from '../src/api'
import {speedRate, useStorageSpeedTests} from '../src/useStorageSpeedTests'
import type {StorageSpeedTest} from '../src/types'

vi.mock('../src/api', async importOriginal => ({
    ...await importOriginal<typeof import('../src/api')>(), api: vi.fn()
}))
const request = vi.mocked(api)
const snapshot = (status: StorageSpeedTest['status'], extra: Partial<StorageSpeedTest> = {}): StorageSpeedTest => ({
    id: 'test-1', providerId: 'provider-1', status, sampleBytes: 16 * 1024 ** 2,
    uploadBytesPerSecond: null, downloadBytesPerSecond: null,
    uploadDurationMillis: null, downloadDurationMillis: null,
    startedAt: '2026-11-09T01:00:00Z', finishedAt: null, error: null, warning: null,
    ...extra
})

function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (error: Error) => void
    const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
    return {promise, resolve, reject}
}

beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers() })
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

describe('remote storage speed tests', () => {
    it('starts once and polls the same test until it finishes, keeping separate rates', async () => {
        const tests = useStorageSpeedTests()
        request.mockResolvedValueOnce(snapshot('PREPARING'))
            .mockResolvedValueOnce(snapshot('DOWNLOADING', {uploadBytesPerSecond: 2 * 1024 ** 2}))
            .mockResolvedValueOnce(snapshot('COMPLETED', {
                uploadBytesPerSecond: 2 * 1024 ** 2, downloadBytesPerSecond: 5 * 1024 ** 2,
                uploadDurationMillis: 8000, downloadDurationMillis: 3200,
                finishedAt: '2026-11-09T01:00:12Z'
            }))
        await tests.start('provider-1')
        expect(request.mock.calls[0]![0]).toBe('/admin/providers/provider-1/speed-test')
        expect(request.mock.calls[0]![1]).toMatchObject({method: 'POST'})
        expect(tests.running.value).toBe(true)
        await vi.advanceTimersByTimeAsync(1000)
        expect(request.mock.calls[1]![0]).toBe('/admin/providers/provider-1/speed-test/test-1')
        expect(request.mock.calls[1]![1]?.method).toBeUndefined()
        expect(tests.states.value['provider-1']!.test!.uploadBytesPerSecond).toBe(2 * 1024 ** 2)
        await vi.advanceTimersByTimeAsync(1000)
        expect(tests.running.value).toBe(false)
        expect(tests.states.value['provider-1']!.test!.downloadBytesPerSecond).toBe(5 * 1024 ** 2)
        await vi.advanceTimersByTimeAsync(5000)
        expect(request).toHaveBeenCalledTimes(3)
    })

    it('blocks concurrent starts, including while the initial request is pending', async () => {
        const tests = useStorageSpeedTests()
        const pending = deferred<StorageSpeedTest>()
        request.mockReturnValueOnce(pending.promise)
        const first = tests.start('provider-1')
        await tests.start('provider-1')
        await tests.start('provider-2')
        expect(request).toHaveBeenCalledTimes(1)
        pending.resolve(snapshot('UPLOADING'))
        await first
        await tests.start('provider-2')
        expect(request).toHaveBeenCalledTimes(1)
        tests.dispose()
    })

    it('does not repeat a failed POST and allows an explicit retry', async () => {
        const tests = useStorageSpeedTests()
        request.mockRejectedValueOnce(new ApiError('已有测速正在运行', 409))
        await tests.start('provider-1')
        expect(tests.running.value).toBe(false)
        expect(tests.states.value['provider-1']!.error).toBe('已有测速正在运行')
        await vi.advanceTimersByTimeAsync(5000)
        expect(request).toHaveBeenCalledTimes(1)
        request.mockResolvedValueOnce(snapshot('COMPLETED'))
        await tests.start('provider-1')
        expect(request).toHaveBeenCalledTimes(2)
        expect(tests.states.value['provider-1']!.error).toBe('')
    })

    it('keeps an active job after a GET failure and resumes reading without uploading again', async () => {
        const tests = useStorageSpeedTests()
        request.mockResolvedValueOnce(snapshot('UPLOADING')).mockRejectedValueOnce(new Error('网络中断'))
        await tests.start('provider-1')
        await vi.advanceTimersByTimeAsync(1000)
        expect(tests.running.value).toBe(true)
        expect(tests.states.value['provider-1']!.error).toContain('网络中断')
        await vi.advanceTimersByTimeAsync(5000)
        await tests.start('provider-2')
        expect(request).toHaveBeenCalledTimes(2)
        request.mockResolvedValueOnce(snapshot('COMPLETED'))
        await tests.read('provider-1')
        expect(request.mock.calls[2]![0]).toBe('/admin/providers/provider-1/speed-test/test-1')
        expect(tests.running.value).toBe(false)
        expect(tests.states.value['provider-1']!.error).toBe('')
    })

    it('does not allow overlapping progress requests', async () => {
        const tests = useStorageSpeedTests()
        request.mockResolvedValueOnce(snapshot('UPLOADING'))
        await tests.start('provider-1')
        const pending = deferred<StorageSpeedTest>()
        request.mockReturnValueOnce(pending.promise)
        const first = tests.read('provider-1')
        await tests.read('provider-1')
        await vi.advanceTimersByTimeAsync(1000)
        expect(request).toHaveBeenCalledTimes(2)
        pending.resolve(snapshot('COMPLETED'))
        await first
    })

    it('allows a new test if the server no longer knows the old job', async () => {
        const tests = useStorageSpeedTests()
        request.mockResolvedValueOnce(snapshot('UPLOADING')).mockRejectedValueOnce(new ApiError('测速不存在', 404))
        await tests.start('provider-1')
        await vi.advanceTimersByTimeAsync(1000)
        expect(tests.running.value).toBe(false)
        expect(tests.states.value['provider-1']!.error).toContain('测速不存在')
        request.mockResolvedValueOnce(snapshot('COMPLETED', {id: 'test-2', providerId: 'provider-2'}))
        await tests.start('provider-2')
        expect(request).toHaveBeenCalledTimes(3)
    })

    it('retains partial results and cleanup warnings when a test fails', async () => {
        const tests = useStorageSpeedTests()
        const failed = snapshot('FAILED', {
            uploadBytesPerSecond: 1024 ** 2, error: '下载校验失败', warning: '测试对象清理失败'
        })
        request.mockResolvedValueOnce(snapshot('DOWNLOADING')).mockResolvedValueOnce(failed)
        await tests.start('provider-1')
        await vi.advanceTimersByTimeAsync(1000)
        expect(tests.states.value['provider-1']!.test).toEqual(failed)
        expect(tests.running.value).toBe(false)
        await vi.advanceTimersByTimeAsync(5000)
        expect(request).toHaveBeenCalledTimes(2)
    })

    it('aborts a pending start and ignores its late response after disposal', async () => {
        const tests = useStorageSpeedTests()
        const pending = deferred<StorageSpeedTest>()
        request.mockReturnValueOnce(pending.promise)
        const starting = tests.start('provider-1')
        const signal = request.mock.calls[0]![1]!.signal!
        tests.dispose()
        expect(signal.aborted).toBe(true)
        pending.resolve(snapshot('UPLOADING'))
        await starting
        await vi.advanceTimersByTimeAsync(5000)
        expect(tests.states.value['provider-1']!.test).toBeNull()
        expect(request).toHaveBeenCalledTimes(1)
        await tests.start('provider-2')
        expect(request).toHaveBeenCalledTimes(1)
    })

    it('clears pending timers and ignores late progress responses on disposal', async () => {
        const tests = useStorageSpeedTests()
        request.mockResolvedValueOnce(snapshot('UPLOADING'))
        await tests.start('provider-1')
        const pending = deferred<StorageSpeedTest>()
        request.mockReturnValueOnce(pending.promise)
        const reading = tests.read('provider-1')
        const signal = request.mock.calls[1]![1]!.signal!
        tests.dispose()
        expect(signal.aborted).toBe(true)
        pending.resolve(snapshot('COMPLETED'))
        await reading
        expect(tests.states.value['provider-1']!.test!.status).toBe('UPLOADING')
        await vi.advanceTimersByTimeAsync(5000)
        expect(request).toHaveBeenCalledTimes(2)
    })

    it('clears obsolete results without erasing the active job', async () => {
        const tests = useStorageSpeedTests()
        request.mockResolvedValueOnce(snapshot('COMPLETED'))
        await tests.start('provider-1')
        tests.clear('provider-1')
        expect(tests.states.value['provider-1']).toBeUndefined()
        request.mockResolvedValueOnce(snapshot('UPLOADING'))
        await tests.start('provider-1')
        tests.clear('provider-1')
        expect(tests.states.value['provider-1']!.test!.status).toBe('UPLOADING')
        tests.dispose()
    })

    it('formats binary transfer units and handles missing or invalid rates', () => {
        expect(speedRate(10 * 1024 ** 2)).toBe('10.00 MiB/s')
        expect(speedRate(0)).toBe('0.00 MiB/s')
        expect(speedRate(null)).toBe('—')
        expect(speedRate(Number.NaN)).toBe('—')
        expect(speedRate(-1)).toBe('—')
    })
})
