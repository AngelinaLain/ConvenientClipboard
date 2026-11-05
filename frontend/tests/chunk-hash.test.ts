import {createHash, randomBytes} from 'node:crypto'
import {describe, expect, it, vi} from 'vitest'
import {fileChunkHashes, hashBlob} from '../src/chunkHash'

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
})
