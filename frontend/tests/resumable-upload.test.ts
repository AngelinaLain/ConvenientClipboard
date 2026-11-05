import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {cachedUploadFile, cacheUploadFile} from '../src/uploadStorage'
import type {UploadRecord} from '../src/uploadStorage'
import type {UploadStatus} from '../src/resumableUpload'
import type {FileInfo} from '../src/types'
import {useResumableUploads} from '../src/useResumableUploads'
import {api, ApiError} from '../src/api'
import {fileChunkHashes} from '../src/chunkHash'
import * as chunkHash from '../src/chunkHash'
import {uploadRequest} from '../src/resumableUpload'

const memory = vi.hoisted(() => ({records: new Map<string, UploadRecord>(), files: new Map<string, File>(), failSave: false}))
vi.mock('../src/chunkHash', async original => ({...await original<typeof import('../src/chunkHash')>(), UPLOAD_CHUNK_SIZE: 4}))
vi.mock('../src/api', async original => ({...await original<typeof import('../src/api')>(), api: vi.fn()}))
vi.mock('../src/uploadStorage', () => ({
    UPLOAD_RESUME_WINDOW_MS: 10 * 60 * 1000,
    storedUploads: (owner: string | null) => [...memory.records.values()].filter(record => record.ownerId === owner).map(record => JSON.parse(JSON.stringify(record))),
    saveUpload: (record: UploadRecord) => { if (memory.failSave) throw new Error('无法持久化任务'); memory.records.set(record.id, JSON.parse(JSON.stringify(record))) },
    forgetUpload: (id: string) => memory.records.delete(id),
    cacheUploadFile: vi.fn(async (id: string, file: File) => { memory.files.set(id, file); return true }),
    cachedUploadFile: vi.fn(async (record: UploadRecord) => memory.files.get(record.id)),
    deleteCachedFile: vi.fn(async (id: string) => { memory.files.delete(id) })
}))
const request = vi.mocked(api)
const source = () => new File(['abcdefghij'], 'original.txt', {type: 'text/plain'})
const result = (id: string): FileInfo => ({id, originalName: 'original.txt', sizeBytes: 10, contentType: 'text/plain', permanent: false,
    expiresAt: '2026-11-12T00:00:00Z', createdAt: '2026-11-05T00:00:00Z', location: 'LOCAL', shareUrl: `/s/${id}`})
const status = (id: string, receivedChunks: number[] = [], completed = false): UploadStatus => ({id, originalName: 'original.txt', sizeBytes: 10,
    contentType: 'text/plain', permanent: false, chunkSize: 4, totalChunks: 3, receivedChunks, uploadedBytes: receivedChunks.reduce((sum, index) => sum + (index === 2 ? 2 : 4), 0),
    state: completed ? 'COMPLETED' : 'UPLOADING', expiresAt: '2026-11-06T00:00:00Z', file: completed ? result(id) : null})
async function record(id = '11111111-1111-4111-8111-111111111111', ownerId: string | null = null): Promise<UploadRecord> {
    return {version: 1, id, token: 'a'.repeat(64), ownerId, originalName: 'original.txt', contentType: 'text/plain', sizeBytes: 10, permanent: false,
        chunkHashes: await fileChunkHashes(source(), 4), createdAt: Date.now(), confirmed: true, paused: false}
}
beforeEach(() => { vi.resetAllMocks(); memory.records.clear(); memory.files.clear(); memory.failSave = false })
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

describe('resumable upload lifecycle', () => {
    it('persists its id, token and complete manifest before any request, then uploads bounded parts', async () => {
        const list = useResumableUploads(() => null)
        const item = list.add(source(), false)
        const putSizes: number[] = []
        request.mockImplementation(async (path, options) => {
            expect(memory.records.has(item.id)).toBe(true)
            expect(memory.records.get(item.id)!.chunkHashes).toHaveLength(3)
            expect(new Headers(options?.headers).get('X-Upload-Token')).toHaveLength(64)
            if (path === `/uploads/${item.id}`) throw new ApiError('不存在', 404)
            if (path === '/uploads') return status(item.id)
            if (String(path).includes('/chunks/')) {
                expect(new Headers(options?.headers).get('Content-Type')).toBe('application/octet-stream')
                putSizes.push((options!.body as Blob).size)
                return status(item.id, Array.from({length: putSizes.length}, (_, index) => index))
            }
            return status(item.id, [0, 1, 2], true)
        })
        await list.start(item)
        expect(putSizes).toEqual([4, 4, 2])
        expect(item.result?.id).toBe(item.id)
        expect(item.stage).toBe('done')
        expect(memory.records.get(item.id)?.file?.id).toBe(item.id)
    })

    it('restores a cached source and sends only server-missing parts after refresh', async () => {
        const saved = await record()
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        const puts: string[] = []
        request.mockImplementation(async path => {
            if (String(path).endsWith('/complete')) return status(saved.id, [0, 1, 2], true)
            if (String(path).includes('/chunks/')) { puts.push(String(path)); return status(saved.id, puts.length === 1 ? [0, 1] : [0, 1, 2]) }
            return status(saved.id, [0])
        })
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(puts).toEqual([`/uploads/${saved.id}/chunks/1`, `/uploads/${saved.id}/chunks/2`])
        expect(list.items.value[0]!.result?.id).toBe(saved.id)
    })

    it('asks for the source when no file cache exists and rejects same-name wrong content before PUT', async () => {
        const saved = await record()
        memory.records.set(saved.id, saved)
        request.mockResolvedValue(status(saved.id, [0]))
        const list = useResumableUploads(() => null)
        await list.restore()
        const item = list.items.value[0]!
        expect(item.stage).toBe('needs-file')
        await list.attach(item, new File(['xxxxefghij'], 'original.txt'))
        expect(item.error).toContain('内容与原任务不一致')
        expect(item.stage).toBe('needs-file')
        expect(item.source).toBeUndefined()
        expect(request.mock.calls.every(call => !String(call[0]).includes('/chunks/'))).toBe(true)
    })

    it('recovers the original share link when completion succeeded but its response was lost', async () => {
        const saved = await record()
        memory.records.set(saved.id, saved)
        request.mockResolvedValue(status(saved.id, [0, 1, 2], true))
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(list.items.value[0]!.result?.shareUrl).toBe(`/s/${saved.id}`)
        expect(request).toHaveBeenCalledTimes(1)
        expect(request.mock.calls[0]![1]?.method).toBeUndefined()
    })

    it('does not reuse a completed task when selecting the same file for a new upload', async () => {
        const saved = await record()
        const previous = result(saved.id)
        previous.expiresAt = '2020-01-01T00:00:00Z'
        memory.records.set(saved.id, {...saved, file: previous})
        const list = useResumableUploads(() => null)
        const item = list.add(source(), false)
        request.mockImplementation(async path => {
            if (path === `/uploads/${item.id}`) throw new ApiError('不存在', 404)
            return status(item.id, [0, 1, 2], path !== '/uploads')
        })
        await list.start(item)
        expect(item.id).not.toBe(saved.id)
        expect(request.mock.calls.some(call => call[0] === '/uploads')).toBe(true)
        expect(item.result?.id).toBe(item.id)
    })

    it('does not recreate a confirmed server task that has disappeared', async () => {
        const saved = await record()
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        request.mockRejectedValue(new ApiError('不存在', 404))
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(request.mock.calls.every(call => call[0] !== '/uploads')).toBe(true)
        expect(list.items.value).toEqual([])
        expect(memory.records.has(saved.id)).toBe(false)
        expect(memory.files.has(saved.id)).toBe(false)
    })

    it('recovers completed status when cancellation races with successful completion', async () => {
        const saved = await record()
        memory.records.set(saved.id, {...saved, paused: true})
        request.mockResolvedValue(status(saved.id, [0, 1]))
        const list = useResumableUploads(() => null)
        await list.restore()
        request.mockImplementation(async (_path, options) => {
            if (options?.method === 'DELETE') throw new ApiError('已完成', 409)
            return status(saved.id, [0, 1, 2], true)
        })
        await list.cancel(list.items.value[0]!)
        expect(list.items.value[0]!.stage).toBe('done')
        expect(list.items.value[0]!.result?.id).toBe(saved.id)
        expect(memory.records.get(saved.id)?.cancelled).toBe(false)
    })

    it('never sends a task when its recovery metadata cannot be persisted', async () => {
        const list = useResumableUploads(() => null)
        memory.failSave = true
        const item = list.add(source(), false)
        await list.start(item)
        expect(item.stage).toBe('error')
        expect(item.error).toContain('持久化')
        expect(request).not.toHaveBeenCalled()
    })

    it('pauses an in-flight part and ignores its late acknowledgement, then resumes the same task', async () => {
        const list = useResumableUploads(() => null)
        const item = list.add(source(), false)
        let acknowledge!: (status: UploadStatus) => void
        request.mockImplementation(async path => {
            if (path === `/uploads/${item.id}`) throw new ApiError('不存在', 404)
            if (path === '/uploads') return status(item.id)
            return new Promise(resolve => { acknowledge = resolve })
        })
        const first = list.start(item)
        const duplicate = list.start(item)
        await vi.waitFor(() => expect(acknowledge).toBeTypeOf('function'))
        list.pause(item)
        acknowledge(status(item.id, [0]))
        await Promise.all([first, duplicate])
        expect(item.stage).toBe('paused')
        expect(item.savedBytes).toBe(0)
        expect(request.mock.calls.filter(call => call[0] === '/uploads')).toHaveLength(1)
        const missing: number[] = []
        request.mockImplementation(async path => {
            if (String(path).includes('/chunks/')) {
                missing.push(Number(String(path).split('/').at(-1)))
                return status(item.id, missing.length === 1 ? [0, 1] : [0, 1, 2])
            }
            return status(item.id, [0, 1, 2], String(path).endsWith('/complete'))
        })
        await list.start(item)
        expect(item.result?.id).toBe(item.id)
        // Explicit resume renews the existing lease using the same persisted task id.
        const creates = request.mock.calls.filter(call => call[0] === '/uploads')
        expect(creates).toHaveLength(2)
        expect(creates.every(call => JSON.parse(String(call[1]?.body)).id === item.id)).toBe(true)
    })

    it('isolates stored tasks by identity and ignores late results after switching users', async () => {
        const saved = await record('11111111-1111-4111-8111-111111111111', 'owner-a')
        const other = await record('22222222-2222-4222-8222-222222222222', 'owner-b')
        memory.records.set(saved.id, {...saved, paused: true})
        memory.records.set(other.id, {...other, paused: true})
        let owner: string | null = 'owner-a'
        let finish!: (value: UploadStatus) => void
        request.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue(status(other.id))
        const list = useResumableUploads(() => owner)
        const first = list.restore()
        await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
        owner = 'owner-b'
        await list.restore()
        finish(status(saved.id, [0, 1, 2], true))
        await first
        expect(list.items.value.map(item => item.id)).toEqual([other.id])
        expect(list.items.value[0]!.result).toBeUndefined()
    })
})

describe('bounded retries for idempotent upload requests', () => {
    it('retries a lost response with the same task and token, then stops after three attempts', async () => {
        const saved = await record()
        vi.useFakeTimers()
        request.mockRejectedValue(new TypeError('Network unavailable'))
        const operation = uploadRequest(saved, '/complete', {method: 'POST'}, new AbortController().signal)
        const rejection = expect(operation).rejects.toThrow('Network unavailable')
        await vi.advanceTimersByTimeAsync(4000)
        await rejection
        expect(request).toHaveBeenCalledTimes(3)
        expect(new Set(request.mock.calls.map(call => String(call[0])))).toEqual(new Set([`/uploads/${saved.id}/complete`]))
        expect(request.mock.calls.every(call => new Headers(call[1]?.headers).get('X-Upload-Token') === saved.token)).toBe(true)
        expect(vi.getTimerCount()).toBe(0)
    })
    it('aborts pending retry timers when paused or leaving the page', async () => {
        const saved = await record()
        vi.useFakeTimers()
        const controller = new AbortController()
        request.mockRejectedValue(new TypeError('Offline'))
        const operation = uploadRequest(saved, '', {}, controller.signal)
        const rejection = expect(operation).rejects.toMatchObject({name: 'AbortError'})
        await vi.advanceTimersByTimeAsync(100)
        controller.abort()
        await rejection
        await vi.advanceTimersByTimeAsync(10000)
        expect(request).toHaveBeenCalledTimes(1)
        expect(vi.getTimerCount()).toBe(0)
    })
})

describe('ten-minute interruption recovery and local cache release', () => {
    it('recreates an unconfirmed missing task within ten minutes using its persisted id', async () => {
        const saved = await record()
        saved.confirmed = false
        saved.createdAt = Date.now() - 9 * 60 * 1000
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        let created = false
        request.mockImplementation(async path => {
            if (path === '/uploads') { created = true; return status(saved.id) }
            if (path === `/uploads/${saved.id}` && !created) throw new ApiError('未创建', 404)
            if (String(path).includes('/chunks/')) {
                const last = Number(String(path).split('/').at(-1))
                return status(saved.id, Array.from({length: last + 1}, (_, index) => index))
            }
            return status(saved.id, [0, 1, 2], String(path).endsWith('/complete'))
        })
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(list.items.value[0]!.result?.id).toBe(saved.id)
        const create = request.mock.calls.find(call => call[0] === '/uploads')!
        expect(JSON.parse(String(create[1]?.body)).id).toBe(saved.id)
        expect(request.mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(cachedUploadFile).mock.invocationCallOrder[0]!)
    })

    it('queries before clearing an unconfirmed task older than ten minutes, without creating it again', async () => {
        const saved = await record()
        saved.confirmed = false
        saved.createdAt = Date.now() - 11 * 60 * 1000
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        request.mockRejectedValue(new ApiError('未创建', 404))
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(request).toHaveBeenCalledTimes(1)
        expect(request.mock.calls[0]![0]).toBe(`/uploads/${saved.id}`)
        expect(vi.mocked(cachedUploadFile)).not.toHaveBeenCalled()
        expect(memory.records.has(saved.id)).toBe(false)
        expect(memory.files.has(saved.id)).toBe(false)
        expect(list.items.value).toEqual([])
    })

    it('uses authoritative server expiry for an older task kept alive in another tab', async () => {
        const saved = await record()
        saved.createdAt = Date.now() - 60 * 60 * 1000
        saved.expiresAt = new Date(Date.now() - 30 * 60 * 1000).toISOString()
        saved.paused = true
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        const live = {...status(saved.id, [0]), expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString()}
        request.mockResolvedValue(live)
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(list.items.value[0]!.id).toBe(saved.id)
        expect(list.items.value[0]!.savedBytes).toBe(4)
        expect(memory.records.get(saved.id)?.expiresAt).toBe(live.expiresAt)
        expect(memory.files.has(saved.id)).toBe(true)
        expect(request.mock.calls.every(call => call[1]?.method !== 'DELETE')).toBe(true)
        expect(request.mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(cachedUploadFile).mock.invocationCallOrder[0]!)
    })

    it('releases the source and metadata when online polling confirms task expiry', async () => {
        const saved = await record()
        saved.paused = true
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        request.mockResolvedValue(status(saved.id, [0]))
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(list.items.value[0]!.source).toBeDefined()
        request.mockRejectedValue(new ApiError('过期', 410))
        await list.poll()
        expect(list.items.value).toEqual([])
        expect(memory.records.has(saved.id)).toBe(false)
        expect(memory.files.has(saved.id)).toBe(false)
    })

    it('cleans expired tasks before opening their cached files', async () => {
        const saved = await record()
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        request.mockRejectedValue(new ApiError('过期', 410))
        const list = useResumableUploads(() => null)
        await list.restore()
        expect(vi.mocked(cachedUploadFile)).not.toHaveBeenCalled()
        expect(memory.records.has(saved.id)).toBe(false)
        expect(memory.files.has(saved.id)).toBe(false)
    })

    it('does not revive an expired task when its asynchronous file cache finishes later', async () => {
        let finishCache!: (value: boolean) => void
        vi.mocked(cacheUploadFile).mockImplementationOnce(() => new Promise(resolve => { finishCache = resolve }))
        const list = useResumableUploads(() => null)
        const item = list.add(source(), false)
        request.mockImplementation(async path => {
            if (path === `/uploads/${item.id}`) throw new ApiError('未创建', 404)
            if (path === '/uploads') return status(item.id)
            throw new ApiError('过期', 410)
        })
        await list.start(item)
        expect(list.items.value).toEqual([])
        memory.files.set(item.id, source())
        finishCache(true)
        await vi.waitFor(() => expect(memory.files.has(item.id)).toBe(false))
        expect(memory.records.has(item.id)).toBe(false)
    })

    it('renews while a manually reselected file is verified for more than ten minutes, then stops when paused', async () => {
        const saved = await record()
        saved.paused = true
        memory.records.set(saved.id, saved)
        request.mockImplementation(async () => ({...status(saved.id, [0]), expiresAt: new Date(Date.now() + 600000).toISOString()}))
        const list = useResumableUploads(() => null)
        await list.restore()
        vi.useFakeTimers()
        let hashing = false
        vi.spyOn(chunkHash, 'fileChunkHashes').mockImplementationOnce(async (_file, _size, _progress, signal) => {
            hashing = true
            return await new Promise<string[]>((_resolve, reject) => {
                signal!.addEventListener('abort', () => reject(new DOMException('paused', 'AbortError')), {once: true})
            })
        })
        const item = list.items.value[0]!
        const operation = list.attach(item, source())
        await vi.advanceTimersByTimeAsync(0)
        expect(hashing).toBe(true)
        await vi.advanceTimersByTimeAsync(11 * 60 * 1000)
        const renewals = request.mock.calls.filter(call => call[0] === '/uploads')
        expect(renewals).toHaveLength(12)
        expect(renewals.every(call => JSON.parse(String(call[1]?.body)).id === saved.id)).toBe(true)
        expect(item.stage).toBe('hashing')
        list.pause(item)
        await operation
        const pausedCalls = request.mock.calls.length
        await vi.advanceTimersByTimeAsync(11 * 60 * 1000)
        expect(request.mock.calls).toHaveLength(pausedCalls)
        await list.poll()
        expect(request.mock.calls.filter(call => call[0] === '/uploads')).toHaveLength(12)
        expect(vi.getTimerCount()).toBe(0)
    })

    it('stops long verification with a retryable error when its explicit renewal fails', async () => {
        const saved = await record()
        saved.paused = true
        memory.records.set(saved.id, saved)
        request.mockResolvedValue(status(saved.id, [0]))
        const list = useResumableUploads(() => null)
        await list.restore()
        vi.useFakeTimers()
        vi.spyOn(chunkHash, 'fileChunkHashes').mockImplementationOnce(async (_file, _size, _progress, signal) => {
            return await new Promise<string[]>((_resolve, reject) => {
                signal!.addEventListener('abort', () => reject(new DOMException('paused', 'AbortError')), {once: true})
            })
        })
        let renewals = 0
        request.mockImplementation(async path => {
            if (path === '/uploads' && ++renewals > 1) throw new ApiError('无权续租', 403)
            return status(saved.id, [0])
        })
        const item = list.items.value[0]!
        const operation = list.attach(item, source())
        await vi.advanceTimersByTimeAsync(61000)
        await operation
        expect(item.stage).toBe('error')
        expect(item.error).toContain('恢复校验已暂停')
        expect(item.source).toBeDefined()
        expect(memory.records.has(saved.id)).toBe(true)
        expect(vi.getTimerCount()).toBe(0)
    })

    it('releases a corrupt cached source on an explicit part hash rejection so the original can be selected again', async () => {
        const saved = await record()
        memory.records.set(saved.id, saved)
        memory.files.set(saved.id, source())
        request.mockImplementation(async path => {
            if (String(path).includes('/chunks/')) throw new ApiError('分片校验失败，请选择原文件', 422)
            return status(saved.id, [0])
        })
        const list = useResumableUploads(() => null)
        await list.restore()
        const item = list.items.value[0]!
        expect(item.stage).toBe('needs-file')
        expect(item.source).toBeUndefined()
        expect(item.verified).toBe(false)
        expect(memory.files.has(saved.id)).toBe(false)
        expect(memory.records.has(saved.id)).toBe(true)
    })
})
