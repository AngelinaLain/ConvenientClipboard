import {api, ApiError} from './api'
import {abortError, checkAbort} from './chunkHash'
import type {FileInfo} from './types'
import type {UploadRecord} from './uploadStorage'

export interface UploadStatus {
    id: string;
    originalName: string;
    contentType: string;
    sizeBytes: number;
    permanent: boolean;
    chunkSize: number;
    totalChunks: number;
    receivedChunks: number[];
    uploadedBytes: number;
    state: 'UPLOADING' | 'COMPLETED';
    expiresAt: string;
    file: FileInfo | null
}

export function retryable(error: unknown) {
    return error instanceof ApiError ? [408, 429, 502, 503, 504].includes(error.status) || error.status >= 500
        : error instanceof Error && error.name !== 'AbortError'
}

export function retryDelay(milliseconds: number, signal: AbortSignal) {
    return new Promise<void>((resolve, reject) => {
        checkAbort(signal)
        const abort = () => { clearTimeout(timer); reject(abortError()) }
        const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, milliseconds)
        signal.addEventListener('abort', abort, {once: true})
    })
}

export async function uploadRequest<T>(record: UploadRecord, suffix = '', options: RequestInit = {},
                                       signal?: AbortSignal, retryNotice?: (attempt: number) => void): Promise<T> {
    for (let attempt = 0; ; attempt++) {
        checkAbort(signal)
        const controller = new AbortController()
        const abort = () => controller.abort()
        signal?.addEventListener('abort', abort, {once: true})
        const timer = setTimeout(() => controller.abort(), 5 * 60 * 1000)
        try {
            return await api<T>(`/uploads${suffix === 'create' ? '' : `/${record.id}${suffix}`}`, {
                ...options, headers: {...Object.fromEntries(new Headers(options.headers)), 'X-Upload-Token': record.token},
                signal: controller.signal
            })
        } catch (error) {
            checkAbort(signal)
            // A timeout may occur after the server has committed. Repeating the same task is idempotent.
            const timeout = error instanceof Error && error.name === 'AbortError'
            if (attempt >= 2 || (!timeout && !retryable(error))) throw error
            retryNotice?.(attempt + 1)
        } finally {
            clearTimeout(timer)
            signal?.removeEventListener('abort', abort)
        }
        await retryDelay(1000 * 2 ** attempt, signal || new AbortController().signal)
    }
}

export function createUpload(record: UploadRecord, signal: AbortSignal) {
    const {id, originalName, contentType, sizeBytes, permanent, chunkHashes} = record
    return uploadRequest<UploadStatus>(record, 'create', {method: 'POST', body: JSON.stringify({id, originalName, contentType, sizeBytes, permanent, chunkHashes})}, signal)
}
