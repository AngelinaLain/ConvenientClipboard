import type {FileInfo} from './types'
import {UPLOAD_CHUNK_SIZE} from './chunkHash'

export interface UploadRecord {
    version: 1;
    id: string;
    token: string;
    ownerId: string | null;
    originalName: string;
    contentType: string;
    sizeBytes: number;
    permanent: boolean;
    chunkHashes: string[];
    createdAt: number;
    expiresAt?: string;
    confirmed: boolean;
    paused: boolean;
    cancelled?: boolean;
    file?: FileInfo
}

export const UPLOAD_STORAGE_PREFIX = 'clipboard.upload.v1.'
export const UPLOAD_RESUME_WINDOW_MS = 10 * 60 * 1000
export function saveUpload(record: UploadRecord) {
    try {
        localStorage.setItem(UPLOAD_STORAGE_PREFIX + record.id, JSON.stringify(record))
    } catch {
        throw new Error('浏览器无法保存恢复信息，请允许本站本地存储后重试。已接收的分片仍保留在服务器。')
    }
}

export function storedUploads(ownerId: string | null): UploadRecord[] {
    const records: UploadRecord[] = []
    try {
        for (let index = 0; index < localStorage.length; index++) {
            const key = localStorage.key(index)
            if (!key?.startsWith(UPLOAD_STORAGE_PREFIX)) continue
            try {
                const value = JSON.parse(localStorage.getItem(key) || 'null') as UploadRecord
                if (value?.version !== 1 || value.ownerId !== ownerId || typeof value.originalName !== 'string'
                    || !/^[a-f0-9-]{36}$/.test(value.id) || !/^[a-f0-9]{64}$/.test(value.token)
                    || !Number.isSafeInteger(value.sizeBytes) || value.sizeBytes < 1
                    || typeof value.permanent !== 'boolean' || typeof value.createdAt !== 'number'
                    || !Array.isArray(value.chunkHashes) || value.chunkHashes.length !== Math.ceil(value.sizeBytes / UPLOAD_CHUNK_SIZE)
                    || !value.chunkHashes.every(hash => /^[a-f0-9]{64}$/.test(hash))) continue
                records.push(value)
            } catch { /* Ignore an invalid record without discarding other recoverable tasks. */ }
        }
    } catch { /* Storage may be unavailable; starting a task will show an actionable error. */ }
    return records.sort((a, b) => a.createdAt - b.createdAt)
}

export function forgetUpload(id: string) {
    try { localStorage.removeItem(UPLOAD_STORAGE_PREFIX + id) } catch { /* Still release the source cache when browser storage becomes unavailable. */ }
}

function database(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        if (typeof indexedDB === 'undefined') { reject(new Error('IndexedDB unavailable')); return }
        const request = indexedDB.open('clipboard-upload-files', 1)
        request.onupgradeneeded = () => { request.result.createObjectStore('files') }
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
        request.onblocked = () => reject(new Error('File cache blocked'))
    })
}

async function fileCache<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await database()
    try {
        return await new Promise<T>((resolve, reject) => {
            const transaction = db.transaction('files', mode)
            const request = operation(transaction.objectStore('files'))
            let result: T
            request.onsuccess = () => { result = request.result }
            transaction.oncomplete = () => resolve(result)
            transaction.onerror = () => reject(transaction.error)
            transaction.onabort = () => reject(transaction.error || new Error('File cache aborted'))
        })
    } finally { db.close() }
}

export async function cacheUploadFile(id: string, file: Blob): Promise<boolean> {
    try { await fileCache('readwrite', store => store.put(file, id)); return true } catch { return false }
}
export async function cachedUploadFile(record: UploadRecord): Promise<File | undefined> {
    try {
        const blob = await fileCache<Blob | undefined>('readonly', store => store.get(record.id))
        return blob instanceof Blob ? new File([blob], record.originalName, {type: record.contentType}) : undefined
    } catch { return undefined }
}
export async function deleteCachedFile(id: string) {
    try { await fileCache('readwrite', store => store.delete(id)) } catch { /* Best effort cache cleanup. */ }
}
