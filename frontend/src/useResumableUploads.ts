import {computed, markRaw, reactive, ref} from 'vue'
import {ApiError} from './api'
import {abortError, checkAbort, fileChunkHashes, UPLOAD_CHUNK_SIZE} from './chunkHash'
import {createUpload, uploadRequest} from './resumableUpload'
import type {UploadStatus} from './resumableUpload'
import {cacheUploadFile, cachedUploadFile, deleteCachedFile, forgetUpload, saveUpload, storedUploads, UPLOAD_RESUME_WINDOW_MS} from './uploadStorage'
import type {UploadRecord} from './uploadStorage'
import type {FileInfo} from './types'

export type UploadStage = 'queued' | 'hashing' | 'checking' | 'uploading' | 'merging' | 'paused' | 'needs-file' | 'done' | 'error' | 'cancelling'
export interface UploadItem {
    id: string;
    ownerId: string | null;
    name: string;
    size: number;
    permanent: boolean;
    stage: UploadStage;
    savedBytes: number;
    hashProgress: number;
    error: string;
    cacheNotice: string;
    source?: File;
    verified: boolean;
    record?: UploadRecord;
    result?: FileInfo
}

function randomHex(bytes: number) {
    if (!globalThis.crypto?.getRandomValues) throw new Error('当前浏览器不支持安全随机数，请更新浏览器后上传')
    return Array.from(crypto.getRandomValues(new Uint8Array(bytes)), value => value.toString(16).padStart(2, '0')).join('')
}
function newId() {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6]! & 15) | 64
    bytes[8] = (bytes[8]! & 63) | 128
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
const matches = (left: readonly string[], right: readonly string[]) => left.length === right.length && left.every((value, index) => value === right[index])

export function useResumableUploads(owner: () => string | null, allowed: () => boolean = () => true) {
    const items = ref<UploadItem[]>([])
    const runners = new Map<string, AbortController>()
    const syncing = new Set<string>()
    let scope = 0
    let disposed = false
    let pollController = new AbortController()
    const active = computed(() => items.value.some(item => ['hashing', 'checking', 'uploading', 'merging', 'cancelling'].includes(item.stage)))
    const pending = computed(() => items.value.filter(item => ['queued', 'paused', 'error'].includes(item.stage) && item.source && !item.record?.cancelled))

    function requireAllowed() {
        if (!allowed()) throw new Error('匿名上传已关闭，请登录后上传')
    }
    function blocked(item: UploadItem) {
        if (allowed()) return false
        item.stage = 'error'
        item.error = '匿名上传已关闭，请登录后上传'
        return true
    }

    function alive(item: UploadItem, controller: AbortController, version: number) {
        checkAbort(controller.signal)
        if (disposed || scope !== version || item.ownerId !== owner() || runners.get(item.id) !== controller) throw abortError()
    }
    function fromRecord(record: UploadRecord): UploadItem {
        return reactive({id: record.id, ownerId: record.ownerId, name: record.originalName, size: record.sizeBytes,
            permanent: record.permanent, stage: record.file ? 'done' : 'checking', savedBytes: record.file ? record.sizeBytes : 0,
            hashProgress: 0, error: '', cacheNotice: '', verified: false, record, result: record.file})
    }
    function accept(item: UploadItem, status: UploadStatus) {
        if (!items.value.includes(item)) return
        item.savedBytes = status.uploadedBytes
        if (item.record) {
            item.record.confirmed = true
            item.record.expiresAt = status.expiresAt
            if (status.state === 'COMPLETED' && status.file) {
                item.record.file = status.file
                item.record.paused = false
                item.result = status.file
                item.error = ''
                item.stage = 'done'
                item.savedBytes = item.size
                item.source = undefined
                void deleteCachedFile(item.id)
            }
            try { saveUpload(item.record) }
            catch { item.cacheNotice = '浏览器未能更新本地记录，服务器进度已保存，可重新查询恢复' }
        }
    }

    async function release(item: UploadItem) {
        runners.get(item.id)?.abort()
        runners.delete(item.id)
        item.source = undefined
        item.verified = false
        forgetUpload(item.id)
        items.value = items.value.filter(value => value !== item)
        await deleteCachedFile(item.id)
    }

    function missingExpired(record: UploadRecord, error: unknown) {
        return error instanceof ApiError && (error.status === 410 || error.status === 404
            && (record.confirmed || Date.now() - record.createdAt >= UPLOAD_RESUME_WINDOW_MS))
    }

    async function prepare(item: UploadItem, controller: AbortController, version: number) {
        if (!item.source) throw new Error('请重新选择原文件以继续上传')
        if (item.source.size !== item.size || item.source.name !== item.name) {
            item.source = undefined
            item.stage = 'needs-file'
            throw new Error('文件名称或大小与原任务不一致，请选择原文件')
        }
        if (item.verified && item.record) return item.record
        item.stage = 'hashing'
        const hashes = await fileChunkHashes(item.source, UPLOAD_CHUNK_SIZE, (done, total) => {
            if (!controller.signal.aborted) item.hashProgress = Math.round(done / total * 100)
        }, controller.signal)
        alive(item, controller, version)
        if (item.record) {
            if (!matches(hashes, item.record.chunkHashes)) {
                item.source = undefined
                item.stage = 'needs-file'
                throw new Error('文件内容与原任务不一致，已停止续传。请选择原文件')
            }
        } else {
            const known = storedUploads(item.ownerId).find(record => !record.cancelled && !record.file && record.permanent === item.permanent
                && record.sizeBytes === item.size && record.originalName === item.name && matches(record.chunkHashes, hashes))
            if (known) {
                const existing = items.value.find(value => value.id === known.id && value !== item)
                if (existing) {
                    existing.source = item.source
                    existing.verified = true
                    items.value = items.value.filter(value => value !== item)
                    void start(existing)
                    throw abortError()
                }
                // Keep the UI id stable for its runner; new selections use their own id unless a stored task was restored.
                item.record = {...known}
                runners.delete(item.id)
                item.id = known.id
                runners.set(item.id, controller)
            } else {
                const createdAt = Date.now()
                item.record = {version: 1, id: item.id, token: randomHex(32), ownerId: item.ownerId,
                    originalName: item.name, contentType: item.source.type || 'application/octet-stream', sizeBytes: item.size,
                    permanent: item.permanent, chunkHashes: hashes, createdAt,
                    expiresAt: new Date(createdAt + UPLOAD_RESUME_WINDOW_MS).toISOString(), confirmed: false, paused: false}
            }
        }
        saveUpload(item.record)
        item.verified = true
        const cacheId = item.id
        void cacheUploadFile(cacheId, item.source).then(cached => {
            if (!items.value.includes(item) || item.record?.cancelled || item.stage === 'done') { void deleteCachedFile(cacheId); return }
            item.cacheNotice = cached ? '原文件已缓存在此浏览器，刷新后可自动续传' : '浏览器未缓存原文件；刷新后请重新选择原文件继续'
        })
        return item.record
    }

    async function start(item: UploadItem) {
        if (disposed || item.ownerId !== owner() || runners.has(item.id) || item.stage === 'done' || item.record?.cancelled) return
        if (blocked(item)) return
        if (!item.source) { item.stage = 'needs-file'; return }
        const controller = new AbortController()
        const version = scope
        let leaseTimer: ReturnType<typeof setInterval> | undefined
        let leaseController: AbortController | undefined
        let leaseFailure: unknown
        let renewing = false
        let leaseActive = false
        const abortLease = () => leaseController?.abort()
        function stopLease() {
            leaseActive = false
            clearInterval(leaseTimer)
            leaseController?.abort()
            controller.signal.removeEventListener('abort', abortLease)
        }
        function maintainLease(record: UploadRecord) {
            leaseActive = true
            leaseController = new AbortController()
            controller.signal.addEventListener('abort', abortLease, {once: true})
            leaseTimer = setInterval(async () => {
                if (renewing || controller.signal.aborted) return
                renewing = true
                try {
                    requireAllowed()
                    const status = await createUpload(record, leaseController!.signal)
                    if (!leaseActive || leaseController?.signal.aborted) return
                    alive(item, controller, version)
                    accept(item, status)
                    if (status.state === 'COMPLETED') controller.abort()
                } catch (error) {
                    if (!leaseController?.signal.aborted && !controller.signal.aborted) {
                        leaseFailure = error
                        controller.abort()
                    }
                } finally { renewing = false }
            }, 60 * 1000)
        }
        runners.set(item.id, controller)
        item.error = ''
        try {
            // Returning to a known task is explicit activity. Ordinary status polling never renews it.
            if (item.record) {
                let previous: UploadStatus | undefined
                try { previous = await uploadRequest<UploadStatus>(item.record, '', {}, controller.signal) }
                catch (error) {
                    alive(item, controller, version)
                    if (missingExpired(item.record, error)) { await release(item); return }
                    if (!(error instanceof ApiError) || error.status !== 404) throw error
                }
                alive(item, controller, version)
                if (previous) {
                    accept(item, previous)
                    if (previous.state === 'COMPLETED') return
                    requireAllowed()
                    const renewed = await createUpload(item.record, controller.signal)
                    alive(item, controller, version)
                    accept(item, renewed)
                    if (renewed.state === 'COMPLETED') return
                    maintainLease(item.record)
                }
            }
            const record = await prepare(item, controller, version)
            stopLease()
            alive(item, controller, version)
            record.paused = false
            saveUpload(record)
            item.stage = 'checking'
            let status: UploadStatus
            try { status = await uploadRequest<UploadStatus>(record, '', {}, controller.signal) }
            catch (error) {
                alive(item, controller, version)
                if (!(error instanceof ApiError) || error.status !== 404) throw error
                if (record.confirmed || Date.now() - record.createdAt >= UPLOAD_RESUME_WINDOW_MS) {
                    await release(item)
                    return
                }
                requireAllowed()
                status = await createUpload(record, controller.signal)
            }
            alive(item, controller, version)
            accept(item, status)
            if (status.state === 'COMPLETED') return
            if (status.chunkSize !== UPLOAD_CHUNK_SIZE || status.totalChunks !== record.chunkHashes.length)
                throw new Error('服务器分片配置与此任务不一致，请重新上传')
            for (let index = 0; index < status.totalChunks; index++) {
                alive(item, controller, version)
                if (status.receivedChunks.includes(index)) continue
                requireAllowed()
                item.stage = 'uploading'
                const body = item.source!.slice(index * UPLOAD_CHUNK_SIZE, Math.min(item.size, (index + 1) * UPLOAD_CHUNK_SIZE))
                status = await uploadRequest<UploadStatus>(record, `/chunks/${index}`, {
                    method: 'PUT', body, headers: {'Content-Type': 'application/octet-stream'}
                }, controller.signal, attempt => { if (!controller.signal.aborted) item.error = `网络暂时中断，正在第 ${attempt} 次重试…` })
                alive(item, controller, version)
                item.error = ''
                accept(item, status)
                if (status.state === 'COMPLETED') return
            }
            alive(item, controller, version)
            requireAllowed()
            item.stage = 'merging'
            status = await uploadRequest<UploadStatus>(record, '/complete', {method: 'POST'}, controller.signal)
            alive(item, controller, version)
            accept(item, status)
            if (status.state !== 'COMPLETED') throw new Error('服务器尚未完成合并，请稍后继续查询')
        } catch (error) {
            if (disposed || scope !== version || item.ownerId !== owner()) return
            if (leaseFailure) {
                if (item.record && missingExpired(item.record, leaseFailure)) { await release(item); return }
                item.stage = 'error'
                item.error = `恢复校验已暂停：${(leaseFailure as Error).message}`
                return
            }
            if (controller.signal.aborted) return
            if (error instanceof Error && error.name === 'AbortError') return
            if (item.record && missingExpired(item.record, error)) { await release(item); return }
            if (item.record && error instanceof ApiError && error.status === 422) {
                item.source = undefined
                item.verified = false
                item.stage = 'needs-file'
                await deleteCachedFile(item.id)
            }
            if (item.stage !== 'needs-file') item.stage = 'error'
            item.error = (error as Error).message
        } finally {
            stopLease()
            if (runners.get(item.id) === controller) runners.delete(item.id)
        }
    }

    function pause(item: UploadItem) {
        const controller = runners.get(item.id)
        controller?.abort()
        runners.delete(item.id)
        item.stage = 'paused'
        item.error = ''
        if (item.record) {
            item.record.paused = true
            try { saveUpload(item.record) }
            catch { item.cacheNotice = '浏览器未能保存暂停状态，刷新后任务可能自动继续' }
        }
    }

    async function cancel(item: UploadItem) {
        pause(item)
        if (!item.record || item.result) {
            if (item.record) forgetUpload(item.id)
            void deleteCachedFile(item.id)
            items.value = items.value.filter(value => value !== item)
            return
        }
        const record = item.record
        record.cancelled = true
        try { saveUpload(record) }
        catch (error) { item.stage = 'error'; item.error = (error as Error).message; return }
        item.stage = 'cancelling'
        try {
            await uploadRequest(record, '', {method: 'DELETE'}, pollController.signal)
            if (item.ownerId !== owner() || disposed) return
            forgetUpload(item.id)
            void deleteCachedFile(item.id)
            items.value = items.value.filter(value => value !== item)
        } catch (error) {
            if (item.ownerId !== owner() || disposed) return
            if (error instanceof ApiError && error.status === 409) {
                try {
                    const status = await uploadRequest<UploadStatus>(record, '', {}, pollController.signal)
                    if (item.ownerId !== owner() || disposed) return
                    if (status.state === 'COMPLETED' && status.file) {
                        record.cancelled = false
                        accept(item, status)
                        return
                    }
                } catch { /* Leave the cancellation marker so an uncertain request can be reconciled later. */ }
                item.stage = 'error'
                item.error = '服务器仍在处理此任务，请稍后再次取消或查询'
            } else if (error instanceof ApiError && [404, 410].includes(error.status)) {
                forgetUpload(item.id)
                void deleteCachedFile(item.id)
                items.value = items.value.filter(value => value !== item)
            } else {
                item.stage = 'error'
                item.error = '取消尚未同步到服务器，请恢复网络后再次取消；任务不会继续上传'
            }
        }
    }

    async function attach(item: UploadItem, source: File) {
        if (runners.has(item.id)) return
        item.source = markRaw(source)
        item.verified = false
        await start(item)
    }

    function add(source: File, permanent: boolean) {
        const item: UploadItem = reactive({id: newId(), ownerId: owner(), name: source.name, size: source.size,
            permanent, stage: 'queued', savedBytes: 0, hashProgress: 0, error: '', cacheNotice: '', source: markRaw(source), verified: false})
        items.value.push(item)
        return item
    }
    async function startAll() {
        for (const item of [...pending.value]) {
            if (disposed) return
            await start(item)
        }
    }

    async function poll(forceCompleted = false) {
        const version = scope
        for (const record of storedUploads(owner())) {
            if (disposed || version !== scope) return
            let item = items.value.find(value => value.id === record.id)
            if (!item) { item = fromRecord(record); items.value.push(item) }
            if (record.cancelled) { if (!syncing.has(record.id)) { syncing.add(record.id); void cancel(item).finally(() => syncing.delete(record.id)) }; continue }
            if (runners.has(record.id) || syncing.has(record.id)) continue
            if (item.stage === 'done' && !forceCompleted && (!record.expiresAt || Date.parse(record.expiresAt) > Date.now())) continue
            syncing.add(record.id)
            try {
                const status = await uploadRequest<UploadStatus>(record, '', {}, pollController.signal)
                if (disposed || version !== scope || !items.value.includes(item) || runners.has(record.id) || item.record?.cancelled) continue
                accept(item, status)
                if (status.state !== 'COMPLETED' && blocked(item)) continue
                if (status.state !== 'COMPLETED' && ['checking', 'error'].includes(item.stage)) {
                    item.stage = record.paused ? 'paused' : 'needs-file'
                    item.error = ''
                }
            } catch (error) {
                if (disposed || version !== scope || !items.value.includes(item) || runners.has(record.id)) continue
                if (missingExpired(record, error)) {
                    await release(item)
                } else if (error instanceof ApiError && error.status === 404 && !record.confirmed && Date.now() - record.createdAt < UPLOAD_RESUME_WINDOW_MS) {
                    item.stage = item.source ? 'paused' : 'needs-file'
                } else {
                    item.stage = 'error'
                    item.error = error instanceof ApiError && [404, 410].includes(error.status) ? '上传任务已失效，请移除后重新上传' : (error as Error).message
                }
            } finally { syncing.delete(record.id) }
        }
    }

    function stop() {
        scope++
        for (const controller of runners.values()) controller.abort()
        runners.clear()
        syncing.clear()
        pollController.abort()
        pollController = new AbortController()
    }
    async function restore() {
        stop()
        const version = scope
        items.value = storedUploads(owner()).map(fromRecord)
        // Server state is authoritative: another tab may have extended an old local expiry.
        // Query before opening cached source files so expired tasks can release storage immediately.
        await poll(true)
        if (disposed || scope !== version) return
        for (const item of items.value) {
            if (!item.record || item.record.cancelled || item.result) continue
            const source = await cachedUploadFile(item.record)
            if (disposed || scope !== version) return
            if (source) {
                item.source = markRaw(source)
                // This immutable Blob was cached under this task id after manifest verification.
                // The server still validates every transmitted part against the original manifest.
                item.verified = true
            }
        }
        for (const item of [...items.value]) {
            if (disposed || scope !== version) return
            if (!item.result && !item.record?.cancelled && blocked(item)) continue
            if (!item.result && item.source && item.stage !== 'error' && !item.record?.paused && !item.record?.cancelled) await start(item)
        }
    }
    function dispose() { disposed = true; stop() }
    return {items, active, pending, add, start, startAll, pause, cancel, attach, restore, poll, suspend: stop, dispose}
}
