import {hashBytes} from './chunkDigest'

export const UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024
export const abortError = () => new DOMException('操作已暂停', 'AbortError')
export function checkAbort(signal?: AbortSignal) {
    if (signal?.aborted) throw abortError()
}

export async function hashBlob(blob: Blob): Promise<string> {
    return hashBytes(await blob.arrayBuffer())
}

const MAX_HASH_WORKERS = 4
const MIN_PARALLEL_CHUNKS = 4
type HashResult = {index: number; hash?: string; error?: string}

async function sequentialHashes(file: Blob, chunkSize: number, total: number,
                                progress?: (done: number, total: number) => void, signal?: AbortSignal) {
    const hashes: string[] = []
    for (let index = 0; index < total; index++) {
        checkAbort(signal)
        hashes.push(await hashBlob(file.slice(index * chunkSize, Math.min(file.size, (index + 1) * chunkSize))))
        checkAbort(signal)
        progress?.(index + 1, total)
        await new Promise<void>(resolve => setTimeout(resolve, 0))
    }
    checkAbort(signal)
    return hashes
}

export async function fileChunkHashes(file: Blob, chunkSize = UPLOAD_CHUNK_SIZE,
                                      progress?: (done: number, total: number) => void, signal?: AbortSignal) {
    if (!Number.isSafeInteger(chunkSize) || chunkSize < 1) throw new Error('无效的分片大小')
    const total = Math.ceil(file.size / chunkSize)
    checkAbort(signal)
    if (total === 0) return []
    if (typeof Worker === 'undefined' || total < MIN_PARALLEL_CHUNKS)
        return sequentialHashes(file, chunkSize, total, progress, signal)

    const count = Math.min(MAX_HASH_WORKERS, total)
    const workers: Worker[] = []
    try {
        for (let index = 0; index < count; index++)
            workers.push(new Worker(new URL('./chunkHash.worker.ts', import.meta.url), {type: 'module'}))
    } catch {
        workers.forEach(worker => worker.terminate())
        return sequentialHashes(file, chunkSize, total, progress, signal)
    }

    return new Promise<string[]>((resolve, reject) => {
        const hashes = new Array<string>(total)
        let next = 0
        let done = 0
        let settled = false
        const assigned = new Map<Worker, number>()
        const cleanup = () => {
            signal?.removeEventListener('abort', abort)
            workers.forEach(worker => worker.terminate())
        }
        const fail = (error: Error) => {
            if (settled) return
            settled = true
            cleanup()
            reject(error)
        }
        const abort = () => fail(abortError())
        const dispatch = async (worker: Worker) => {
            const index = next++
            if (index >= total || settled) return
            try {
                const bytes = await file.slice(index * chunkSize, Math.min(file.size, (index + 1) * chunkSize)).arrayBuffer()
                if (settled || signal?.aborted) return
                assigned.set(worker, index)
                worker.postMessage({index, bytes}, [bytes])
            } catch {
                fail(new Error('无法读取文件分片，请重新选择原文件'))
            }
        }
        signal?.addEventListener('abort', abort, {once: true})
        if (signal?.aborted) { abort(); return }
        workers.forEach(worker => {
            worker.onmessage = (event: MessageEvent<HashResult>) => {
                if (settled) return
                const {index, hash, error} = event.data
                if (error || index !== assigned.get(worker) || !hash || !/^[a-f0-9]{64}$/.test(hash)) {
                    fail(new Error(error || '文件分片校验结果无效'))
                    return
                }
                hashes[index] = hash
                done++
                try { progress?.(done, total) }
                catch { fail(new Error('文件校验进度更新失败')); return }
                if (signal?.aborted) { abort(); return }
                if (done === total) {
                    settled = true
                    cleanup()
                    resolve(hashes)
                } else void dispatch(worker)
            }
            worker.onerror = () => fail(new Error('文件校验线程异常，请重试'))
            worker.onmessageerror = () => fail(new Error('文件分片无法传入校验线程'))
            void dispatch(worker)
        })
    })
}
