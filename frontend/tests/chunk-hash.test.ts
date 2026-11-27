import {createHash, randomBytes} from 'node:crypto'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {fileChunkHashes, hashBlob} from '../src/chunkHash'

class FakeHashWorker {
    static workers: FakeHashWorker[] = []
    static active = 0
    static peak = 0
    onmessage?: (event: MessageEvent<{index: number; hash: string}>) => void
    onerror?: () => void
    onmessageerror?: () => void
    terminated = false

    constructor() { FakeHashWorker.workers.push(this) }

    postMessage(message: {index: number; bytes: ArrayBuffer}, transfer: Transferable[]) {
        expect(transfer).toEqual([message.bytes])
        FakeHashWorker.active++
        FakeHashWorker.peak = Math.max(FakeHashWorker.peak, FakeHashWorker.active)
        setTimeout(() => {
            FakeHashWorker.active--
            if (this.terminated) return
            const hash = createHash('sha256').update(Buffer.from(message.bytes)).digest('hex')
            this.onmessage?.({data: {index: message.index, hash}} as MessageEvent<{index: number; hash: string}>)
        }, message.index % 2 === 0 ? 5 : 0)
    }

    terminate() { this.terminated = true }
}

afterEach(() => {
    vi.unstubAllGlobals()
    FakeHashWorker.workers = []
    FakeHashWorker.active = 0
    FakeHashWorker.peak = 0
})

describe('bounded upload hashing on HTTP origins', () => {
    it.each(['', 'abc', 'a'.repeat(65), '文件内容'])('matches SHA-256 for %s', async value => {
        expect(await hashBlob(new Blob([value]))).toBe(createHash('sha256').update(value).digest('hex'))
    })
    it('works without crypto.subtle and only reads bounded slices', async () => {
        const bytes = randomBytes(8193)
        const blob = new Blob([bytes])
        const readWhole = vi.spyOn(blob, 'arrayBuffer')
        const progress = vi.fn()
        const hashes = await fileChunkHashes(blob, 1024, progress)
        const expected = Array.from({length: 9}, (_, index) => createHash('sha256').update(bytes.subarray(index * 1024, (index + 1) * 1024)).digest('hex'))
        expect(hashes).toEqual(expected)
        expect(readWhole).not.toHaveBeenCalled()
        expect(progress).toHaveBeenLastCalledWith(9, 9)
    })
    it('stops between chunks when paused', async () => {
        const controller = new AbortController()
        await expect(fileChunkHashes(new Blob(['123456789']), 4, () => controller.abort(), controller.signal)).rejects.toMatchObject({name: 'AbortError'})
    })
    it('hashes bounded slices in parallel and returns them in manifest order', async () => {
        vi.stubGlobal('Worker', FakeHashWorker)
        const bytes = randomBytes(9 * 1024)
        const blob = new Blob([bytes])
        const readWhole = vi.spyOn(blob, 'arrayBuffer')
        const progress = vi.fn()
        const hashes = await fileChunkHashes(blob, 1024, progress)
        const expected = Array.from({length: 9}, (_, index) => createHash('sha256')
            .update(bytes.subarray(index * 1024, (index + 1) * 1024)).digest('hex'))
        expect(hashes).toEqual(expected)
        expect(FakeHashWorker.workers).toHaveLength(4)
        expect(FakeHashWorker.peak).toBe(4)
        expect(FakeHashWorker.workers.every(worker => worker.terminated)).toBe(true)
        expect(readWhole).not.toHaveBeenCalled()
        expect(progress).toHaveBeenLastCalledWith(9, 9)
    })
    it('terminates every hashing worker when paused', async () => {
        vi.stubGlobal('Worker', FakeHashWorker)
        const controller = new AbortController()
        await expect(fileChunkHashes(new Blob([randomBytes(9 * 1024)]), 1024,
            () => controller.abort(), controller.signal)).rejects.toMatchObject({name: 'AbortError'})
        expect(FakeHashWorker.workers).toHaveLength(4)
        expect(FakeHashWorker.workers.every(worker => worker.terminated)).toBe(true)
    })
})
