import {sha256} from '@noble/hashes/sha2.js'

export const UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024
export const abortError = () => new DOMException('操作已暂停', 'AbortError')
export function checkAbort(signal?: AbortSignal) {
    if (signal?.aborted) throw abortError()
}

export async function hashBlob(blob: Blob): Promise<string> {
    // A pure JS digest also works on HTTP origins where crypto.subtle is unavailable.
    const digest = sha256(new Uint8Array(await blob.arrayBuffer()))
    return Array.from(digest, value => value.toString(16).padStart(2, '0')).join('')
}

export async function fileChunkHashes(file: Blob, chunkSize = UPLOAD_CHUNK_SIZE,
                                      progress?: (done: number, total: number) => void, signal?: AbortSignal) {
    if (!Number.isSafeInteger(chunkSize) || chunkSize < 1) throw new Error('无效的分片大小')
    const total = Math.ceil(file.size / chunkSize)
    const hashes: string[] = []
    for (let index = 0; index < total; index++) {
        checkAbort(signal)
        hashes.push(await hashBlob(file.slice(index * chunkSize, Math.min(file.size, (index + 1) * chunkSize))))
        checkAbort(signal)
        progress?.(index + 1, total)
        // Let pause, navigation and rendering run between bounded reads.
        await new Promise<void>(resolve => setTimeout(resolve, 0))
    }
    checkAbort(signal)
    return hashes
}
